// Phase 1 adapter for Instagram - counts newly inserted feed post nodes
// via MutationObserver and reports them to the background script.
//
// SELECTOR CAVEAT: Instagram's DOM is unstable and undocumented. "main
// article" is a reasonable starting guess (Instagram wraps each feed
// post in an <article> inside the main content region) but has NOT been
// verified against the live site. Before trusting the counts: open the
// page, open devtools, scroll a few posts, and confirm this selector
// matches exactly one node per post - not per image, not per comment,
// not matching zero because the markup changed.
const SITE = "instagram.com";
const POST_SELECTOR = "main article";

const seenPosts = new WeakSet();

function countNewPosts(addedNodes) {
  let newCount = 0;
  for (const node of addedNodes) {
    if (node.nodeType !== Node.ELEMENT_NODE) continue;
    const candidates = [];
    if (node.matches?.(POST_SELECTOR)) candidates.push(node);
    if (node.querySelectorAll) candidates.push(...node.querySelectorAll(POST_SELECTOR));
    for (const post of candidates) {
      if (!seenPosts.has(post)) {
        seenPosts.add(post);
        newCount++;
      }
    }
  }
  return newCount;
}

function reportPosts(count) {
  if (count <= 0) return;
  browser.runtime.sendMessage({ type: "feed-limiter:posts-seen", site: SITE, count });
}

const observer = new MutationObserver((mutations) => {
  let total = 0;
  for (const m of mutations) total += countNewPosts(m.addedNodes);
  reportPostsAndRefreshBadge(total);
});

let badge = null;

function reportPostsAndRefreshBadge(count) {
  reportPosts(count);
  if (badge) feedLimiterRefreshBadge(badge, SITE);
}

function start() {
  const root = document.querySelector("main") || document.body;
  feedLimiterSetupVisibilityReporting(SITE);
  badge = feedLimiterCreateBadge();
  feedLimiterRefreshBadge(badge, SITE);
  // Timer-cap minutes tick in the background independent of scrolling,
  // so refresh periodically too, not just on new posts.
  setInterval(() => feedLimiterRefreshBadge(badge, SITE), 10000);

  observer.observe(root, { childList: true, subtree: true });
  // Count whatever's already rendered on load, not just future insertions.
  reportPostsAndRefreshBadge(countNewPosts([root]));
}

if (document.readyState === "complete" || document.readyState === "interactive") {
  start();
} else {
  window.addEventListener("DOMContentLoaded", start);
}
