// Phase 1 adapter for X/Twitter - counts newly inserted tweet nodes via
// MutationObserver and reports them to the background script.
//
// SELECTOR NOTE: article[data-testid="tweet"] has been a stable,
// widely-documented selector for an individual tweet in the feed for a
// long time, so this is more likely to hold up than the Instagram guess
// - but it is still worth a quick live check (devtools, scroll a few
// tweets) before trusting the counts, since X has changed data-testid
// values before without notice.
const SITE = "x.com";
const POST_SELECTOR = 'article[data-testid="tweet"]';

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
  const root = document.querySelector('[data-testid="primaryColumn"]') || document.body;
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
