// Loaded by the background page and the interstitial page; content scripts
// run in their own context and never see this.

// First-run defaults only. Once native has sent a snapshot, the persisted
// snapshot replaces these at startup (see loadPersistedSnapshot in
// background.js). Keep in sync with DEFAULT_SETTINGS in FeedLimiterSettingsModel.kt.
const FEED_LIMITER_CONFIG = {
  lowSaturation: "off",
  sites: {
    "instagram.com": {
      type: "built-in", enabled: true, mode: "both",
      postLimit: 30, timerMinutesLimit: 45, desaturate: false
    },
    "x.com": {
      type: "built-in", enabled: true, mode: "post",
      postLimit: 20, timerMinutesLimit: null, desaturate: false
    },
    "tiktok.com": {
      type: "built-in", enabled: true, mode: "both",
      postLimit: 40, timerMinutesLimit: 30, desaturate: false
    },
    "facebook.com": {
      type: "built-in", enabled: true, mode: "both",
      postLimit: 25, timerMinutesLimit: 45, desaturate: false
    },
    "reddit.com": {
      type: "built-in", enabled: true, mode: "both",
      postLimit: 25, timerMinutesLimit: 45, desaturate: false
    },
    "linkedin.com": {
      type: "built-in", enabled: true, mode: "post",
      postLimit: 20, timerMinutesLimit: null, desaturate: false
    },
    "youtube.com/shorts": {
      type: "built-in", enabled: true, mode: "both",
      postLimit: 40, timerMinutesLimit: 30, desaturate: false
    },
    "youtube.com": {
      type: "youtube-regular", enabled: true, mode: "timer",
      postLimit: null, timerMinutesLimit: 60, videoLimit: 5, desaturate: false
    }
  }
};

// Hostname suffix -> site key for the built-in feed sites. YouTube is matched
// separately because one domain maps to two keys depending on the path.
const BUILT_IN_DOMAINS = {
  "instagram.com": "instagram.com",
  "x.com": "x.com",
  "twitter.com": "x.com",
  "tiktok.com": "tiktok.com",
  "facebook.com": "facebook.com",
  "reddit.com": "reddit.com",
  "linkedin.com": "linkedin.com"
};

// Locked 15-item list; 3 are chosen at random per interstitial view.
const ACTIVITY_LIST = [
  "Read a book",
  "Read the news",
  "Go for a walk",
  "Do a quick stretch or light exercise",
  "Call a friend or family member",
  "Journal for a few minutes",
  "Tidy up a small space",
  "Make tea or coffee mindfully",
  "Meditate or do a breathing exercise",
  "Water your plants",
  "Update your to-do list",
  "Play a round of chess or do a puzzle",
  "Review what you did till now",
  "Update your calendar",
  "Pet an animal"
];

// Local calendar day, not UTC, so counts roll over at local midnight.
function todayKey() {
  const d = new Date();
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${yyyy}-${mm}-${dd}`;
}

function isUnderDomain(hostname, domain) {
  return hostname === domain || hostname.endsWith("." + domain);
}

// Returns the configured site key for a page, or null. Hostnames from URL()
// are already lowercase punycode, which is the form custom keys are stored in.
function matchSite(hostname, pathname) {
  if (!hostname) return null;
  const host = hostname.replace(/\.$/, "");
  const sites = FEED_LIMITER_CONFIG.sites;

  if (isUnderDomain(host, "youtube.com")) {
    const key = pathname && pathname.startsWith("/shorts/") ? "youtube.com/shorts" : "youtube.com";
    return sites[key] ? key : null;
  }

  for (const [domain, key] of Object.entries(BUILT_IN_DOMAINS)) {
    if (isUnderDomain(host, domain)) return sites[key] ? key : null;
  }

  // Longest suffix wins so "news.example.com" beats "example.com".
  let best = null;
  for (const [key, cfg] of Object.entries(sites)) {
    if (cfg.type !== "custom" || !isUnderDomain(host, key)) continue;
    if (best === null || key.length > best.length) best = key;
  }
  return best;
}
