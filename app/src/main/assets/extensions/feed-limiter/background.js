// Enforcement: per-site, per-day counts, cap checks, blocking, the native
// settings bridge, and the low-saturation stylesheet registration.

const INTERSTITIAL_URL = browser.runtime.getURL("interstitial.html");
const TICK_ALARM = "feed-limiter-tick";
const SNAPSHOT_STORAGE_KEY = "settingsSnapshot";
const MODES = ["post", "timer", "both"];
const LOW_SATURATION_MODES = ["off", "all", "selected"];
const MAX_LIMIT = 100000;
const MAX_POSTS_PER_MESSAGE = 1000;
const VIDEO_ID_RE = /^[A-Za-z0-9_-]{11}$/;
const HOSTNAME_RE = /^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$/;

// Captured before any snapshot can replace the defaults: these keys can never
// be removed, and each must keep its type.
const FIXED_SITE_TYPES = Object.fromEntries(
  Object.entries(FEED_LIMITER_CONFIG.sites).map(([key, cfg]) => [key, cfg.type])
);

// browser.windows doesn't exist on GeckoView, so this stays true there and
// per-tab visibility reports are the real signal.
let windowFocused = true;
let pictureInPicture = false;

// The blocking webRequest listener must answer synchronously, so it reads
// this mirror instead of storage. Only valid while blockedCacheDay is today.
const blockedSitesCache = new Set();
let blockedCacheDay = todayKey();

const tabVisibility = new Map();

let saturationRegistration = null;
let saturationSignature = JSON.stringify(null);
let saturationQueue = Promise.resolve();
let countsQueue = Promise.resolve();
let nativePort = null;

// Listeners must be registered synchronously, but they must not judge usage
// against config.js defaults before the persisted snapshot has loaded.
let ready = null;

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isLimit(value) {
  return Number.isInteger(value) && value > 0 && value <= MAX_LIMIT;
}

function isCount(value) {
  return Number.isInteger(value) && value >= 0;
}

// --- counts storage ----------------------------------------------------------

function normalizeEntry(raw, key) {
  const entry = { postsSeen: 0, minutesUsed: 0, blocked: false, watchedVideoIds: [] };
  if (raw === undefined) return entry;
  if (!isPlainObject(raw)) {
    console.error("[feed-limiter] discarding malformed counts entry", key, raw);
    return entry;
  }
  for (const field of ["postsSeen", "minutesUsed"]) {
    if (isCount(raw[field])) entry[field] = raw[field];
    else if (raw[field] !== undefined) console.error("[feed-limiter] bad counts field", key, field, raw[field]);
  }
  entry.blocked = raw.blocked === true;
  if (Array.isArray(raw.watchedVideoIds)) {
    entry.watchedVideoIds = raw.watchedVideoIds.filter((id) => typeof id === "string" && VIDEO_ID_RE.test(id));
  } else if (raw.watchedVideoIds !== undefined) {
    console.error("[feed-limiter] bad watchedVideoIds", key, raw.watchedVideoIds);
  }
  return entry;
}

async function readCounts() {
  const stored = await browser.storage.local.get("counts");
  if (stored.counts === undefined) return {};
  if (isPlainObject(stored.counts)) return stored.counts;
  console.error("[feed-limiter] stored counts are malformed; starting today from zero", stored.counts);
  return {};
}

// Serialised because posts, timer ticks and snapshot re-evaluation all
// read-modify-write the same `counts` object and would otherwise lose updates.
function updateCounts(mutate) {
  const run = countsQueue.then(async () => {
    const counts = await readCounts();
    const result = mutate(counts);
    await browser.storage.local.set({ counts });
    return result;
  });
  // Failures reach the caller through `run`; this only keeps the chain alive.
  countsQueue = run.then(() => undefined, () => undefined);
  return run;
}

function isCapped(cfg, entry) {
  if (!cfg || !cfg.enabled) return false;
  const postCapped = cfg.mode !== "timer" && cfg.postLimit != null && entry.postsSeen >= cfg.postLimit;
  const timerCapped = cfg.mode !== "post" && cfg.timerMinutesLimit != null && entry.minutesUsed >= cfg.timerMinutesLimit;
  const videoCapped = cfg.videoLimit != null && entry.watchedVideoIds.length >= cfg.videoLimit;
  return postCapped || timerCapped || videoCapped;
}

// Returns true when the site has just become blocked.
function refreshBlocked(site, entry) {
  const wasBlocked = entry.blocked;
  entry.blocked = isCapped(FEED_LIMITER_CONFIG.sites[site], entry);
  if (entry.blocked) blockedSitesCache.add(site);
  else blockedSitesCache.delete(site);
  return entry.blocked && !wasBlocked;
}

// Redirects whenever the site is blocked, not only on the transition, so a
// blocked page that slipped through (e.g. a restored tab) is caught on its
// next post or minute.
async function recordUsage(site, apply) {
  const { entry, newlyBlocked } = await updateCounts((counts) => {
    const key = `${site}:${todayKey()}`;
    const entry = normalizeEntry(counts[key], key);
    apply(entry);
    const newlyBlocked = refreshBlocked(site, entry);
    counts[key] = entry;
    return { entry, newlyBlocked };
  });
  sendCountsUpdate(site, entry);
  if (newlyBlocked) console.log(`[feed-limiter] ${site} capped`, entry);
  if (entry.blocked) await redirectSiteTabsToInterstitial(site);
}

// Recomputes today's blocked flag for every configured site against the
// current limits, so a confirmed loosen unblocks and a tighten below today's
// usage blocks immediately. Also rebuilds the cache when the day has changed.
async function reevaluateAll() {
  const today = todayKey();
  const { entries, newlyBlocked } = await updateCounts((counts) => {
    blockedSitesCache.clear();
    blockedCacheDay = today;
    const entries = {};
    const newlyBlocked = [];
    for (const site of Object.keys(FEED_LIMITER_CONFIG.sites)) {
      const key = `${site}:${today}`;
      const entry = normalizeEntry(counts[key], key);
      if (refreshBlocked(site, entry)) newlyBlocked.push(site);
      if (counts[key] !== undefined || entry.blocked) counts[key] = entry;
      entries[site] = entry;
    }
    return { entries, newlyBlocked };
  });
  for (const [site, entry] of Object.entries(entries)) sendCountsUpdate(site, entry);
  for (const site of newlyBlocked) await redirectSiteTabsToInterstitial(site);
}

// --- tabs --------------------------------------------------------------------

function siteForUrl(rawUrl) {
  if (typeof rawUrl !== "string" || !/^https?:/i.test(rawUrl)) return null;
  try {
    const url = new URL(rawUrl);
    return matchSite(url.hostname, url.pathname);
  } catch (e) {
    console.error("[feed-limiter] unparseable URL", rawUrl, e);
    return null;
  }
}

function redirectTab(tabId) {
  browser.tabs.update(tabId, { url: INTERSTITIAL_URL })
    .catch((e) => console.error("[feed-limiter] redirect to interstitial failed", tabId, e));
}

async function redirectSiteTabsToInterstitial(site) {
  const tabs = await browser.tabs.query({});
  for (const tab of tabs) {
    if (siteForUrl(tab.url) === site) redirectTab(tab.id);
  }
}

// --- messages from content scripts -------------------------------------------

async function handlePostsSeen(msg) {
  await ready;
  const cfg = FEED_LIMITER_CONFIG.sites[msg.site];
  if (!cfg || cfg.type !== "built-in") {
    console.error("[feed-limiter] posts-seen for a site without post counting", msg.site);
    return;
  }
  if (!Number.isInteger(msg.count) || msg.count < 1 || msg.count > MAX_POSTS_PER_MESSAGE) {
    console.error("[feed-limiter] posts-seen with invalid count", msg.site, msg.count);
    return;
  }
  if (!cfg.enabled) return;
  await recordUsage(msg.site, (entry) => {
    entry.postsSeen += msg.count;
  });
}

async function handleVideoWatched(msg) {
  await ready;
  if (typeof msg.videoId !== "string" || !VIDEO_ID_RE.test(msg.videoId)) {
    console.error("[feed-limiter] video-watched with invalid videoId", msg.videoId);
    return;
  }
  const cfg = FEED_LIMITER_CONFIG.sites["youtube.com"];
  if (!cfg || !cfg.enabled) return;
  await recordUsage("youtube.com", (entry) => {
    if (!entry.watchedVideoIds.includes(msg.videoId)) entry.watchedVideoIds.push(msg.videoId);
  });
}

function handleVisibility(msg, sender) {
  if (!sender.tab || typeof msg.visible !== "boolean") {
    console.error("[feed-limiter] malformed visibility report", msg, sender.url);
    return;
  }
  tabVisibility.set(sender.tab.id, msg.visible);
}

browser.runtime.onMessage.addListener((msg, sender) => {
  if (!isPlainObject(msg) || typeof msg.type !== "string") {
    console.error("[feed-limiter] malformed content-script message", msg);
    return;
  }
  switch (msg.type) {
    case "feed-limiter:posts-seen":
      return handlePostsSeen(msg).catch((e) => console.error("[feed-limiter] posts-seen failed", msg.site, e));
    case "feed-limiter:video-watched":
      return handleVideoWatched(msg).catch((e) => console.error("[feed-limiter] video-watched failed", e));
    case "feed-limiter:visibility":
      handleVisibility(msg, sender);
      return;
    default:
      console.error("[feed-limiter] unknown content-script message type", msg.type);
  }
});

browser.tabs.onRemoved.addListener((tabId) => tabVisibility.delete(tabId));

// --- hard block on return ----------------------------------------------------

async function blockIfCapped(details) {
  if (details.frameId !== 0) return;
  await ready;
  const site = siteForUrl(details.url);
  if (!site) return;
  const cfg = FEED_LIMITER_CONFIG.sites[site];
  if (!cfg || !cfg.enabled) return;
  const key = `${site}:${todayKey()}`;
  const counts = await readCounts();
  if (isCapped(cfg, normalizeEntry(counts[key], key))) redirectTab(details.tabId);
}

function onNavigation(details) {
  blockIfCapped(details).catch((e) => console.error("[feed-limiter] navigation check failed", details.url, e));
}

browser.webNavigation.onBeforeNavigate.addListener(onNavigation);
// Single-page apps (YouTube moving between /watch and /shorts/) navigate via
// pushState, which never fires onBeforeNavigate.
browser.webNavigation.onHistoryStateUpdated.addListener(onNavigation);

// --- timer -------------------------------------------------------------------

async function currentForegroundSite() {
  if (pictureInPicture) return null;
  const [activeTab] = await browser.tabs.query({ active: true, currentWindow: true });
  if (!activeTab) return null;
  const reported = tabVisibility.get(activeTab.id);
  const visible = reported !== undefined ? reported : windowFocused;
  return visible ? siteForUrl(activeTab.url) : null;
}

// Touching browser.windows unguarded throws on GeckoView and would abort the
// rest of this script, including the alarm setup below.
if (browser.windows && browser.windows.onFocusChanged) {
  browser.windows.onFocusChanged.addListener((windowId) => {
    windowFocused = windowId !== browser.windows.WINDOW_ID_NONE;
  });
}

async function onTick() {
  await ready;
  if (blockedCacheDay !== todayKey()) await reevaluateAll();
  const site = await currentForegroundSite();
  const cfg = site && FEED_LIMITER_CONFIG.sites[site];
  if (!cfg || !cfg.enabled) return;
  await recordUsage(site, (entry) => {
    entry.minutesUsed += 1;
  });
}

// alarms rather than setInterval so ticking survives the background page
// being suspended and restarted.
browser.alarms.create(TICK_ALARM, { periodInMinutes: 1 });
browser.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name !== TICK_ALARM) return;
  onTick().catch((e) => console.error("[feed-limiter] timer tick failed", e));
});

// --- network cutoff (backup for the post cap) --------------------------------
// Unverified guesses at each site's pagination endpoints; the DOM cutoff and
// the interstitial redirect are the real enforcement. YouTube Shorts has none.
const PAGINATION_URL_PATTERNS = [
  "*://*.instagram.com/graphql/query*",
  "*://*.instagram.com/api/v1/feed/timeline/*",
  "*://x.com/i/api/graphql/*",
  "*://twitter.com/i/api/graphql/*",
  "*://*.tiktok.com/api/recommend/item_list/*",
  "*://*.facebook.com/api/graphql/*",
  "*://*.reddit.com/svc/shreddit/*",
  "*://*.linkedin.com/voyager/api/graphql*"
];

browser.webRequest.onBeforeRequest.addListener(
  (details) => {
    // Yesterday's blocks must not keep cancelling requests after midnight,
    // even in the minute before the next tick rebuilds the cache.
    if (blockedCacheDay !== todayKey()) return {};
    const site = siteForUrl(details.url);
    return site && blockedSitesCache.has(site) ? { cancel: true } : {};
  },
  { urls: PAGINATION_URL_PATTERNS },
  ["blocking"]
);

// --- settings snapshots ------------------------------------------------------

function isValidCustomDomain(key) {
  return HOSTNAME_RE.test(key) &&
    !isUnderDomain(key, "youtube.com") &&
    !Object.keys(BUILT_IN_DOMAINS).some((domain) => isUnderDomain(key, domain));
}

function validateSite(key, raw) {
  const reject = (reason) => {
    console.error(`[feed-limiter] rejecting settings for ${key}: ${reason}`, raw);
    return null;
  };
  if (!isPlainObject(raw)) return reject("not an object");
  const expectedType = FIXED_SITE_TYPES[key] || "custom";
  if (raw.type !== expectedType) return reject(`type must be ${expectedType}`);
  if (expectedType === "custom" && !isValidCustomDomain(key)) return reject("not an allowed custom domain");
  if (typeof raw.enabled !== "boolean") return reject("enabled must be a boolean");
  if (!MODES.includes(raw.mode)) return reject("unknown mode");
  if (expectedType !== "built-in" && raw.mode !== "timer") return reject("timer-only site with a non-timer mode");
  if (expectedType === "built-in" ? !isLimit(raw.postLimit) : raw.postLimit !== null) return reject("bad postLimit");
  if (raw.timerMinutesLimit !== null && !isLimit(raw.timerMinutesLimit)) return reject("bad timerMinutesLimit");
  if (raw.mode !== "post" && raw.timerMinutesLimit === null) return reject("timer cap on without a limit");
  if (expectedType === "youtube-regular") {
    if (raw.videoLimit !== null && !isLimit(raw.videoLimit)) return reject("bad videoLimit");
  } else if (raw.videoLimit !== undefined) {
    return reject("videoLimit is only allowed on youtube.com");
  }
  if (typeof raw.desaturate !== "boolean") return reject("desaturate must be a boolean");

  const cfg = {
    type: raw.type,
    enabled: raw.enabled,
    mode: raw.mode,
    postLimit: raw.postLimit,
    timerMinutesLimit: raw.timerMinutesLimit,
    desaturate: raw.desaturate
  };
  if (expectedType === "youtube-regular") cfg.videoLimit = raw.videoLimit;
  return cfg;
}

// Native is the source of truth: sites absent from a valid snapshot are
// removed. Anything malformed keeps its current settings instead, so a bad
// message can never silently loosen enforcement.
function parseSnapshot(raw, source) {
  if (!isPlainObject(raw) || !isPlainObject(raw.settings)) {
    console.error(`[feed-limiter] ignoring malformed settings snapshot from ${source}`, raw);
    return null;
  }
  let lowSaturation = FEED_LIMITER_CONFIG.lowSaturation;
  if (LOW_SATURATION_MODES.includes(raw.lowSaturation)) {
    lowSaturation = raw.lowSaturation;
  } else {
    console.error(`[feed-limiter] invalid lowSaturation from ${source}; keeping "${lowSaturation}"`, raw.lowSaturation);
  }

  const current = FEED_LIMITER_CONFIG.sites;
  const sites = {};
  for (const [key, value] of Object.entries(raw.settings)) {
    const cfg = validateSite(key, value);
    if (cfg) sites[key] = cfg;
    else if (current[key]) sites[key] = current[key];
  }
  for (const key of Object.keys(FIXED_SITE_TYPES)) {
    if (sites[key]) continue;
    console.error(`[feed-limiter] snapshot from ${source} is missing ${key}; keeping current settings`);
    sites[key] = current[key];
  }
  return { sites, lowSaturation };
}

function applySnapshot(parsed) {
  FEED_LIMITER_CONFIG.sites = parsed.sites;
  FEED_LIMITER_CONFIG.lowSaturation = parsed.lowSaturation;
}

// Lets custom sites be enforced after a restart before native reconnects.
async function loadPersistedSnapshot() {
  const stored = await browser.storage.local.get(SNAPSHOT_STORAGE_KEY);
  const raw = stored[SNAPSHOT_STORAGE_KEY];
  if (raw === undefined) return;
  const parsed = parseSnapshot(raw, "storage");
  if (parsed) applySnapshot(parsed);
}

async function handleNativeSnapshot(msg) {
  const parsed = parseSnapshot(msg, "native");
  if (!parsed) return;
  applySnapshot(parsed);
  await reevaluateAll();
  await applyLowSaturation();
  try {
    await browser.storage.local.set({
      [SNAPSHOT_STORAGE_KEY]: { settings: parsed.sites, lowSaturation: parsed.lowSaturation }
    });
  } catch (e) {
    console.error("[feed-limiter] could not persist settings; they apply now but may not survive a restart", e);
  }
}

// --- low saturation ----------------------------------------------------------
// A fixed overlay with backdrop-filter instead of `filter` on <html>, because
// a filter on an ancestor breaks position:fixed on many sites. User origin +
// !important so page styles can't override it. Registered CSS is injected
// before first paint, so there's no flash of full colour.
const LOW_SATURATION_CSS = `html::after {
  content: "" !important;
  display: block !important;
  position: fixed !important;
  inset: 0 !important;
  z-index: 2147483647 !important;
  pointer-events: none !important;
  background: none !important;
  opacity: 1 !important;
  transform: none !important;
  visibility: visible !important;
  backdrop-filter: saturate(0.1) !important;
}`;

function matchPatternsFor(key) {
  if (key === "youtube.com/shorts") return ["*://*.youtube.com/shorts/*"];
  const aliases = Object.keys(BUILT_IN_DOMAINS).filter((domain) => BUILT_IN_DOMAINS[domain] === key);
  return (aliases.length ? aliases : [key]).map((domain) => `*://*.${domain}/*`);
}

function lowSaturationTargets() {
  const mode = FEED_LIMITER_CONFIG.lowSaturation;
  if (mode === "all") return { matches: ["<all_urls>"], excludeMatches: [] };
  if (mode !== "selected") return null;
  const selected = Object.keys(FEED_LIMITER_CONFIG.sites).filter((key) => FEED_LIMITER_CONFIG.sites[key].desaturate);
  if (!selected.length) return null;
  const excludeMatches = selected.includes("youtube.com") && !selected.includes("youtube.com/shorts")
    ? ["*://*.youtube.com/shorts/*"]
    : [];
  return { matches: selected.flatMap(matchPatternsFor), excludeMatches };
}

async function updateLowSaturationRegistration() {
  const targets = lowSaturationTargets();
  const signature = JSON.stringify(targets);
  if (signature === saturationSignature) return;
  if (saturationRegistration) {
    await saturationRegistration.unregister();
    saturationRegistration = null;
  }
  saturationSignature = JSON.stringify(null);
  if (!targets) return;
  const options = {
    matches: targets.matches,
    css: [{ code: LOW_SATURATION_CSS }],
    cssOrigin: "user",
    runAt: "document_start",
    allFrames: false
  };
  if (targets.excludeMatches.length) options.excludeMatches = targets.excludeMatches;
  saturationRegistration = await browser.contentScripts.register(options);
  saturationSignature = signature;
}

function applyLowSaturation() {
  saturationQueue = saturationQueue
    .then(updateLowSaturationRegistration)
    .catch((e) => console.error("[feed-limiter] low-saturation update failed", e));
  return saturationQueue;
}

// --- native bridge -----------------------------------------------------------
// "feedlimiter" must match the nativeApp name GeckoProvider.kt passes to
// setMessageDelegate().

function postToNative(message) {
  if (!nativePort) return;
  try {
    nativePort.postMessage(message);
  } catch (e) {
    console.error("[feed-limiter] failed to post to native", message.type, e);
  }
}

function sendCountsUpdate(site, entry) {
  postToNative({
    type: "feed-limiter:counts-update",
    site,
    entry: {
      postsSeen: entry.postsSeen,
      minutesUsed: entry.minutesUsed,
      blocked: entry.blocked,
      videosWatched: entry.watchedVideoIds.length
    }
  });
}

function handleNativeMessage(msg) {
  if (!isPlainObject(msg) || typeof msg.type !== "string") {
    console.error("[feed-limiter] malformed native message", msg);
    return;
  }
  switch (msg.type) {
    case "feed-limiter:settings-snapshot":
      handleNativeSnapshot(msg).catch((e) => console.error("[feed-limiter] failed to apply native settings", e));
      return;
    case "feed-limiter:pip":
      if (typeof msg.active !== "boolean") {
        console.error("[feed-limiter] malformed pip message", msg);
        return;
      }
      pictureInPicture = msg.active;
      return;
    default:
      console.error("[feed-limiter] unknown native message type", msg.type);
  }
}

function connectNative() {
  try {
    nativePort = browser.runtime.connectNative("feedlimiter");
  } catch (e) {
    console.error("[feed-limiter] connectNative failed; settings stay as last persisted", e);
    return;
  }
  nativePort.onMessage.addListener(handleNativeMessage);
  nativePort.onDisconnect.addListener((port) => {
    console.error("[feed-limiter] native port disconnected; retrying in 5s", port.error);
    nativePort = null;
    setTimeout(connectNative, 5000);
  });
  postToNative({ type: "feed-limiter:request-settings" });
}

async function start() {
  try {
    await loadPersistedSnapshot();
  } catch (e) {
    console.error("[feed-limiter] could not load persisted settings; using defaults until native connects", e);
  }
  try {
    await reevaluateAll();
  } catch (e) {
    console.error("[feed-limiter] startup re-evaluation failed", e);
  }
  await applyLowSaturation();
  connectNative();
}

ready = start();
