// Phase 1 background logic: per-site/per-day post + timer counting, cap
// checking, daily reset (free, via date-keyed storage), hard-block on
// navigation back to a capped site, and a best-effort network-level
// cutoff. No friction/cooldown gate here - that only applies to
// *loosening* a cap from the Phase 2 settings screen, which doesn't
// exist yet.

const INTERSTITIAL_URL = browser.runtime.getURL("interstitial.html");

let windowFocused = true;

// In-memory mirror of which sites are blocked today. The webRequest
// listener further down must respond synchronously to block a request,
// but the real source of truth (storage.local) is only readable async -
// so this cache is updated inline wherever an entry flips to blocked,
// and hydrated from storage once at startup (see hydrateBlockedCache).
const blockedSitesCache = new Set();

// Per-tab visibility reports (document.visibilitychange, reported by
// each adapter via feedLimiterSetupVisibilityReporting in badge.js).
// windows.onFocusChanged's behavior in GeckoView's single-window Android
// model isn't well documented, and Android's own process-priority model
// can leave a "focused" window state stale when the whole app is
// backgrounded - e.g. home button, app switcher, screen lock. Firefox for
// Android is confirmed to fire visibilitychange reliably on exactly those
// transitions (unlike most mobile browsers), so it's the primary signal in
// currentForegroundSite() below; windowFocused is kept only as a fallback
// for the brief window before a tab's first visibility report arrives.
// Keyed by tabId, not globally, so the same site open in a second,
// background tab never counts time just because the foreground tab
// happens to match too.
const tabVisibility = new Map();

async function getCounts() {
  const { counts } = await browser.storage.local.get("counts");
  return counts || {};
}

async function saveCounts(counts) {
  await browser.storage.local.set({ counts });
}

async function getEntry(site) {
  const counts = await getCounts();
  const key = `${site}:${todayKey()}`;
  return counts[key] || { postsSeen: 0, minutesUsed: 0, blocked: false };
}

async function setEntry(site, entry) {
  const counts = await getCounts();
  const key = `${site}:${todayKey()}`;
  counts[key] = entry;
  await saveCounts(counts);
  return entry;
}

function checkCap(site, entry) {
  const cfg = FEED_LIMITER_CONFIG.sites[site];
  if (!cfg) return false;
  const postCapped = cfg.postLimit != null && entry.postsSeen >= cfg.postLimit;
  const timerCapped = cfg.timerMinutesLimit != null && entry.minutesUsed >= cfg.timerMinutesLimit;
  if (cfg.mode === "post") return postCapped;
  if (cfg.mode === "timer") return timerCapped;
  return postCapped || timerCapped; // "both" -> whichever trips first
}

async function registerPostsSeen(site, count) {
  const entry = await getEntry(site);
  entry.postsSeen += count;
  if (!entry.blocked && checkCap(site, entry)) {
    entry.blocked = true;
    blockedSitesCache.add(site);
    console.log(`[feed-limiter] ${site} capped on posts (${entry.postsSeen})`);
  }
  await setEntry(site, entry);
  sendCountsUpdate(site, entry);
  return entry;
}

async function tickTimer(site) {
  const entry = await getEntry(site);
  entry.minutesUsed += 1;
  if (!entry.blocked && checkCap(site, entry)) {
    entry.blocked = true;
    blockedSitesCache.add(site);
    console.log(`[feed-limiter] ${site} capped on time (${entry.minutesUsed}m)`);
    await redirectSiteTabsToInterstitial(site);
  }
  await setEntry(site, entry);
  sendCountsUpdate(site, entry);
}

async function isBlocked(site) {
  const entry = await getEntry(site);
  return entry.blocked;
}

async function redirectSiteTabsToInterstitial(site) {
  const tabs = await browser.tabs.query({});
  for (const tab of tabs) {
    if (!tab.url) continue;
    try {
      const url = new URL(tab.url);
      if (matchSite(url.hostname, url.pathname) === site) {
        browser.tabs.update(tab.id, { url: INTERSTITIAL_URL });
      }
    } catch (e) {
      // non-http(s) tab URL (about:, moz-extension:, etc.) - ignore
    }
  }
}

// --- messages from content scripts (post counts) ---
browser.runtime.onMessage.addListener(async (msg, sender) => {
  if (msg?.type === "feed-limiter:posts-seen") {
    const site = msg.site;
    if (!FEED_LIMITER_CONFIG.sites[site]) return;
    const entry = await registerPostsSeen(site, msg.count || 1);
    if (entry.blocked && sender.tab) {
      browser.tabs.update(sender.tab.id, { url: INTERSTITIAL_URL });
    }
    return entry;
  }

  // Lets the content script's on-page badge show live numbers without
  // any desktop console/debugger attached - the only way to see what's
  // happening when testing purely on-device.
  if (msg?.type === "feed-limiter:get-status") {
    const site = msg.site;
    const cfg = FEED_LIMITER_CONFIG.sites[site];
    if (!cfg) return null;
    const entry = await getEntry(site);
    return { entry, cfg };
  }

  // Per-tab visibility reports (document.visibilitychange) from each
  // adapter - see tabVisibility below for why this exists.
  if (msg?.type === "feed-limiter:visibility") {
    if (sender.tab) tabVisibility.set(sender.tab.id, !!msg.visible);
    return;
  }
});

// Removes a tab's stale visibility entry once it's gone, so a closed
// tab's last-known state can never be mistaken for a live one.
browser.tabs.onRemoved.addListener((tabId) => tabVisibility.delete(tabId));

// --- hard block on return: catches reloads, new tabs, deep links ---
browser.webNavigation.onBeforeNavigate.addListener(async (details) => {
  if (details.frameId !== 0) return; // top-level frame only
  let url;
  try {
    url = new URL(details.url);
  } catch (e) {
    return;
  }
  const site = matchSite(url.hostname, url.pathname);
  if (!site) return;
  if (await isBlocked(site)) {
    browser.tabs.update(details.tabId, { url: INTERSTITIAL_URL });
  }
});

// --- timer tracking: is a configured site genuinely on-screen right now? ---
async function currentForegroundSite() {
  const tabsFound = await browser.tabs.query({ active: true, currentWindow: true });
  const activeTab = tabsFound[0];
  if (!activeTab || !activeTab.url) return null;
  const reportedVisible = tabVisibility.get(activeTab.id);
  const visible = reportedVisible !== undefined ? reportedVisible : windowFocused;
  if (!visible) return null;
  try {
    const url = new URL(activeTab.url);
    return matchSite(url.hostname, url.pathname);
  } catch (e) {
    return null;
  }
}

// FIX (timer-stuck-at-zero bug): `browser.windows` is a UI-scoped API that
// GeckoView does NOT implement on Android (confirmed - windows/tabs-UI
// hooks are out of scope for GeckoView's WebExtension support, unlike
// tabs/alarms/webRequest which were specifically patched in). The old,
// unguarded `browser.windows.onFocusChanged.addListener(...)` call below
// threw a synchronous TypeError ("Cannot read properties of undefined")
// the instant this background script loaded on-device - and since that
// statement sat ABOVE browser.alarms.create(...) in file order, the
// uncaught exception aborted the rest of this top-level script before it
// ever got there. Net effect: the alarm was never created, onAlarm was
// never registered, tickTimer() was never called - the timer silently
// never ran at all, while everything registered earlier in the file
// (onMessage, onBeforeNavigate) kept working fine, which is exactly the
// "posts count, timer doesn't" symptom reported on-device.
// Fix: feature-detect before touching browser.windows, and fall back to
// tabVisibility as the sole signal (it's the trusted one on Android
// anyway per the comment above tabVisibility) - windowFocused just stays
// true as a harmless default when browser.windows isn't there.
if (browser.windows && browser.windows.onFocusChanged) {
  browser.windows.onFocusChanged.addListener((windowId) => {
    windowFocused = windowId !== browser.windows.WINDOW_ID_NONE;
  });
} else {
  console.log(
    "[feed-limiter] browser.windows unavailable (expected on GeckoView/Android) - " +
      "relying on tabVisibility only, windowFocused stays true"
  );
}

// 1-minute resolution via the alarms API rather than setInterval, so
// tracking survives the background page being suspended/restarted.
browser.alarms.create("feed-limiter-tick", { periodInMinutes: 1 });
browser.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name !== "feed-limiter-tick") return;
  const site = await currentForegroundSite();
  if (!site) return;
  const cfg = FEED_LIMITER_CONFIG.sites[site];
  if (cfg && cfg.timerMinutesLimit != null) {
    await tickTimer(site);
  }
});

// --- best-effort network-level cutoff (belt-and-braces for post cap) ---
// CAVEAT - NOT YET VERIFIED AGAINST LIVE TRAFFIC for any site below,
// Instagram/X included: these are starting guesses at each site's
// pagination/"load more" endpoint based on publicly documented API shapes,
// not confirmed against what each site actually calls today. All of these
// sites change their API paths without notice. Before relying on this
// layer for a given site, open devtools Network tab (filtered to
// Fetch/XHR), scroll the real feed, and update that site's pattern(s) to
// match what you actually see. Until then, treat the content-script DOM
// cutoff as the real enforcement and this whole layer as a secondary one
// that may currently be a no-op for some or all sites - TikTok and
// LinkedIn in particular are guesses with lower confidence than the rest,
// since their pagination calls are less consistently documented
// publicly. YouTube Shorts has no pattern at all for the same reason
// (Shorts prefetches via an internal batch endpoint that isn't stable
// enough to guess) - its post cap relies on the DOM cutoff alone.
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
    let url;
    try {
      url = new URL(details.url);
    } catch (e) {
      return {};
    }
    const site = matchSite(url.hostname, url.pathname);
    if (!site) return {};
    const cfg = FEED_LIMITER_CONFIG.sites[site];
    if (!cfg || cfg.postLimit == null) return {};
    // isBlocked() is async but this listener must respond synchronously
    // for blocking webRequest - Phase 1 keeps a tiny in-memory mirror of
    // the blocked flags, refreshed whenever registerPostsSeen/tickTimer
    // flips one, so this check doesn't need to await storage.
    if (blockedSitesCache.has(site)) {
      return { cancel: true };
    }
    return {};
  },
  { urls: PAGINATION_URL_PATTERNS },
  ["blocking"]
);

async function hydrateBlockedCache() {
  for (const site of Object.keys(FEED_LIMITER_CONFIG.sites)) {
    if (await isBlocked(site)) blockedSitesCache.add(site);
  }
}
hydrateBlockedCache();

// --- native bridge (Phase 2 Settings UI sync) ---
// FIX (settings-page-shows-no-counts bug): nothing in this file previously
// connected to the native side at all, so FeedLimiterExtensionBridge.kt's
// onConnect/onPortMessage never fired, _liveUsage stayed permanently
// empty, and the native Settings screen had nothing to display. This
// section establishes that connection, applies settings pushed down from
// the native Settings screen onto the (previously hardcoded-only)
// FEED_LIMITER_CONFIG, and reports live usage back up after every count
// change.
//
// Requires "nativeMessaging" and "geckoViewAddons" permissions in
// manifest.json (added alongside this fix) - without those,
// browser.runtime.connectNative throws / is undefined, even for a
// built-in extension. "feedlimiter" below MUST exactly match the
// nativeApp string GeckoProvider.kt passes to
// webExtension.setMessageDelegate(feedLimiterBridge, "feedlimiter").
let nativePort = null;

function connectNative() {
  try {
    nativePort = browser.runtime.connectNative("feedlimiter");
  } catch (e) {
    console.error("[feed-limiter] connectNative failed", e);
    return;
  }

  nativePort.onMessage.addListener((msg) => {
    if (msg?.type === "feed-limiter:settings-snapshot") {
      applySettingsSnapshot(msg.settings);
    }
  });

  nativePort.onDisconnect.addListener(() => {
    console.log("[feed-limiter] native port disconnected - retrying in 5s");
    nativePort = null;
    setTimeout(connectNative, 5000);
  });

  // Ask native for whatever it already has as soon as the port is up,
  // rather than waiting for the user to touch something in Settings.
  nativePort.postMessage({ type: "feed-limiter:request-settings" });
}

// Merges native-pushed per-site overrides (e.g. a new postLimit/
// timerMinutesLimit the user set in the Settings screen) onto the
// hardcoded defaults in config.js, so Phase 2's Settings UI actually
// takes effect here instead of being silently ignored.
function applySettingsSnapshot(settings) {
  if (!settings) return;
  for (const [site, override] of Object.entries(settings)) {
    if (!FEED_LIMITER_CONFIG.sites[site]) continue;
    Object.assign(FEED_LIMITER_CONFIG.sites[site], override);
  }
  console.log("[feed-limiter] applied native settings snapshot", settings);
}

function sendCountsUpdate(site, entry) {
  if (!nativePort) return;
  try {
    nativePort.postMessage({ type: "feed-limiter:counts-update", site, entry });
  } catch (e) {
    console.error("[feed-limiter] failed to send counts-update", e);
  }
}

connectNative();
