// Phase 1 adapter for TikTok - counts newly inserted For You feed video
// nodes via MutationObserver and reports them to the background script.
//
// SELECTOR CAVEAT: TikTok's web client uses data-e2e attributes for
// testing hooks, which tend to be more stable than class names, but this
// has NOT been verified against the live site (lower confidence than the
// X adapter, similar confidence to the Instagram one). Before trusting
// the counts: open the page, open devtools, scroll a few videos, and
// confirm this selector matches exactly one node per video in the feed -
// not per comment, not per sidebar recommendation, not matching zero
// because the markup changed.
const SITE = "tiktok.com";
const POST_SELECTOR = 'div[data-e2e="recommend-list-item-container"]';

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
  const root = document.body;
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
