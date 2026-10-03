// Shared config loaded into the background page AND the interstitial
// page (via a <script> tag) - not available to content scripts, which
// run in an isolated context and only need to report raw post counts.
//
// Phase 1 hardcodes limits here because there's no native settings
// screen yet (that's Phase 2). Numbers below are starting defaults from
// the build plan's data model sketch, not tuned - change freely while
// testing.

const FEED_LIMITER_CONFIG = {
  sites: {
    "instagram.com": {
      type: "built-in",
      mode: "both", // "post" | "timer" | "both" - whichever cap hits first
      postLimit: 30,
      timerMinutesLimit: 45
    },
    "x.com": {
      type: "built-in",
      mode: "post",
      postLimit: 20,
      timerMinutesLimit: null
    },
    "tiktok.com": {
      type: "built-in",
      mode: "both",
      postLimit: 40, // videos are short, so a higher count than IG/X is deliberate
      timerMinutesLimit: 30
    },
    "facebook.com": {
      type: "built-in",
      mode: "both",
      postLimit: 25,
      timerMinutesLimit: 45
    },
    "reddit.com": {
      type: "built-in",
      mode: "both",
      postLimit: 25,
      timerMinutesLimit: 45
    },
    "linkedin.com": {
      type: "built-in",
      mode: "post",
      postLimit: 20,
      timerMinutesLimit: null
    },
    // Key is path-scoped, not just the hostname - matchSite() below
    // special-cases youtube.com so only /shorts/ paths resolve to this
    // entry. Regular YouTube watch pages are intentionally untouched
    // until Phase 3 (per the build plan, they get different, timer-only
    // "custom-style" treatment, not folded into the Shorts post-counter).
    "youtube.com/shorts": {
      type: "built-in",
      mode: "both",
      postLimit: 40,
      timerMinutesLimit: 30
    }
  }
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

// Local-calendar-day key, e.g. "2026-10-03" - not UTC, so the cap
// resets at local midnight with no separate reset job needed.
function todayKey() {
  const d = new Date();
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${yyyy}-${mm}-${dd}`;
}

// Maps a hostname (e.g. "www.instagram.com", "mobile.x.com") back to its
// config key ("instagram.com", "x.com"). Treats twitter.com as an alias
// of the x.com config entry. youtube.com is special-cased and path-aware:
// only /shorts/ paths resolve to the "youtube.com/shorts" config entry -
// regular watch pages return null (out of scope until Phase 3), so they
// never get hard-blocked or network-capped just because the Shorts
// counter tripped. Callers that have a pathname available (anything
// working from a full URL) should pass it; callers that only have a
// hostname (none currently) will simply never match YouTube, which is
// the safe default.
function matchSite(hostname, pathname) {
  if (!hostname) return null;
  const aliases = { "twitter.com": "x.com" };

  if (hostname === "youtube.com" || hostname.endsWith(".youtube.com")) {
    if (pathname && pathname.startsWith("/shorts/")) return "youtube.com/shorts";
    return null;
  }

  for (const site of Object.keys(FEED_LIMITER_CONFIG.sites)) {
    if (hostname === site || hostname.endsWith("." + site)) return site;
  }
  for (const [alias, real] of Object.entries(aliases)) {
    if (hostname === alias || hostname.endsWith("." + alias)) return real;
  }
  return null;
}
