// Phase 1 adapter for LinkedIn - counts newly inserted feed post nodes
// via MutationObserver and reports them to the background script.
//
// SELECTOR CAVEAT: feed-shared-update-v2 is a long-standing class name
// for an individual feed post that has shown up consistently in
// LinkedIn's DOM for years, but it is a plain CSS class (not a stable
// attribute or custom element like Reddit's), so it's the lowest-
// confidence selector of the built-in adapters and the most likely to
// silently break on a redesign. Has NOT been verified against the live
// site. Before trusting the counts: open the page, open devtools, scroll
// a few posts, and confirm this selector matches exactly one node per
// post - not per comment, not per "People you may know" sidebar card, not
// matching zero because the markup changed.
const SITE = "linkedin.com";
const POST_SELECTOR = "div.feed-shared-update-v2";

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
  setInterval(() => feedLimiterRefreshBadge(badge, SITE), 10000);

  observer.observe(root, { childList: true, subtree: true });
  reportPostsAndRefreshBadge(countNewPosts([root]));
}

if (document.readyState === "complete" || document.readyState === "interactive") {
  start();
} else {
  window.addEventListener("DOMContentLoaded", start);
}
