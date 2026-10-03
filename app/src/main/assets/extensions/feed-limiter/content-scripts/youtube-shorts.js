// Phase 1 adapter for YouTube Shorts ONLY - counts Shorts actually
// swiped-to-and-viewed, not just rendered, and reports them to the
// background script. Scoped by manifest.json content_scripts "matches"
// to *://*.youtube.com/shorts/* paths only, and by config.js's path-aware
// matchSite() on the background side - regular YouTube watch pages are
// explicitly out of scope until Phase 3 (per the build plan), so this
// file must never run or count anything there.
//
// SELECTOR NOTE: YouTube's Shorts player keeps several
// <ytd-reel-video-renderer> custom elements in the DOM at once (the
// previous, current, and next Short are all pre-rendered for instant
// swiping), with the currently-playing one marked via an is-active
// attribute - confirmed via public documentation as of this writing,
// though not independently re-verified against the live site today.
// Because renderers for upcoming Shorts exist in the DOM before you've
// actually swiped to them, counting plain node insertions (like the other
// adapters do) would overcount - instead this watches for a renderer
// GAINING is-active, which only happens once you actually land on it.
const SITE = "youtube.com/shorts";
const RENDERER_TAG = "YTD-REEL-VIDEO-RENDERER";

const seenActive = new WeakSet();
let badge = null;

function reportPostsAndRefreshBadge(count) {
  if (count > 0) {
    browser.runtime.sendMessage({ type: "feed-limiter:posts-seen", site: SITE, count });
  }
  if (badge) feedLimiterRefreshBadge(badge, SITE);
}

function handleRenderer(renderer) {
  if (renderer.hasAttribute("is-active") && !seenActive.has(renderer)) {
    seenActive.add(renderer);
    reportPostsAndRefreshBadge(1);
  }
}

function scanAddedNode(node) {
  if (node.nodeType !== Node.ELEMENT_NODE) return;
  if (node.tagName === RENDERER_TAG) handleRenderer(node);
  node.querySelectorAll?.(RENDERER_TAG.toLowerCase()).forEach(handleRenderer);
}

const observer = new MutationObserver((mutations) => {
  for (const m of mutations) {
    if (m.type === "attributes" && m.target.tagName === RENDERER_TAG) {
      handleRenderer(m.target);
    }
    for (const node of m.addedNodes) scanAddedNode(node);
  }
});

function start() {
  feedLimiterSetupVisibilityReporting(SITE);
  badge = feedLimiterCreateBadge();
  feedLimiterRefreshBadge(badge, SITE);
  setInterval(() => feedLimiterRefreshBadge(badge, SITE), 10000);

  observer.observe(document.body, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ["is-active"]
  });
  // Whatever's already on screen on load (e.g. a direct /shorts/<id> link)
  // counts as one viewed Short, same as every subsequent swipe.
  document.querySelectorAll(RENDERER_TAG.toLowerCase()).forEach(handleRenderer);
}

if (document.readyState === "complete" || document.readyState === "interactive") {
  start();
} else {
  window.addEventListener("DOMContentLoaded", start);
}
