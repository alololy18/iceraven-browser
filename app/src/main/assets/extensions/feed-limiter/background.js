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
      const hostname = new URL(tab.url).hostname;
      if (matchSite(hostname) === site) {
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
});

// --- hard block on return: catches reloads, new tabs, deep links ---
browser.webNavigation.onBeforeNavigate.addListener(async (details) => {
  if (details.frameId !== 0) return; // top-level frame only
  let hostname;
  try {
    hostname = new URL(details.url).hostname;
  } catch (e) {
    return;
  }
  const site = matchSite(hostname);
  if (!site) return;
  if (await isBlocked(site)) {
    browser.tabs.update(details.tabId, { url: INTERSTITIAL_URL });
  }
});

// --- timer tracking: is a configured site the active, focused tab? ---
async function currentForegroundSite() {
  if (!windowFocused) return null;
  const tabsFound = await browser.tabs.query({ active: true, currentWindow: true });
  const activeTab = tabsFound[0];
  if (!activeTab || !activeTab.url) return null;
  try {
    return matchSite(new URL(activeTab.url).hostname);
  } catch (e) {
    return null;
  }
}

browser.windows.onFocusChanged.addListener((windowId) => {
  windowFocused = windowId !== browser.windows.WINDOW_ID_NONE;
});

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
// CAVEAT - NOT YET VERIFIED AGAINST LIVE TRAFFIC: these URL patterns are
// a starting guess at each site's pagination/"load more" endpoint, based
// on publicly known API shapes as of my training data - not confirmed
// against what Instagram/X actually call today. Both sites change their
// API paths without notice. Before relying on this layer, open devtools
// Network tab (filtered to Fetch/XHR), scroll the real feed, and update
// the patterns below to match what you actually see. Until then, treat
// the content-script DOM cutoff as the real enforcement and this as a
// secondary layer that may currently be a no-op.
const PAGINATION_URL_PATTERNS = [
  "*://*.instagram.com/graphql/query*",
  "*://*.instagram.com/api/v1/feed/timeline/*",
  "*://x.com/i/api/graphql/*",
  "*://twitter.com/i/api/graphql/*"
];

browser.webRequest.onBeforeRequest.addListener(
  (details) => {
    let hostname;
    try {
      hostname = new URL(details.url).hostname;
    } catch (e) {
      return {};
    }
    const site = matchSite(hostname);
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
