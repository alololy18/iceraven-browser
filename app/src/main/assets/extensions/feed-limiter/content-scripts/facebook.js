// Phase 1 adapter for Facebook - counts newly inserted feed post nodes
// via MutationObserver and reports them to the background script.
//
// SELECTOR CAVEAT: Facebook's feed markup uses heavily randomized/
// obfuscated class names that change on every deploy, so counting by
// class is a non-starter. div[role="article"] is a longer-standing,
// accessibility-driven attribute Facebook uses to mark each feed post for
// screen readers, which tends to survive redesigns better than classes -
// but this has NOT been verified against the live site, and is a lower
// confidence selector than the X adapter's. Before trusting the counts:
// open the page, open devtools, scroll a few posts, and confirm this
// selector matches exactly one node per post - not per comment, not per
// "suggested for you" sidebar card, not matching zero because the markup
// changed.
const SITE = "facebook.com";
const POST_SELECTOR = 'div[role="article"]';

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
  const root = document.querySelector('div[role="main"]') || document.body;
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
