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
// of the x.com config entry.
function matchSite(hostname) {
  if (!hostname) return null;
  const aliases = { "twitter.com": "x.com" };
  for (const site of Object.keys(FEED_LIMITER_CONFIG.sites)) {
    if (hostname === site || hostname.endsWith("." + site)) return site;
  }
  for (const [alias, real] of Object.entries(aliases)) {
    if (hostname === alias || hostname.endsWith("." + alias)) return real;
  }
  return null;
}
