// Phase 1 adapter for Reddit - counts newly inserted feed post nodes via
// MutationObserver and reports them to the background script.
//
// SELECTOR NOTE: the current (2026) Reddit web client renders each feed
// post as a <shreddit-post> custom element (a Web Component tag, not a
// class), which is a more stable kind of hook than a CSS class since it's
// part of the site's actual component architecture rather than
// deploy-generated styling - confirmed via public documentation as of
// this writing, though not independently re-verified against the live
// site today. This adapter only covers the new (shreddit) front end, not
// old.reddit.com, which uses a completely different DOM structure - if
// you use old.reddit.com day to day, this won't count anything there.
const SITE = "reddit.com";
const POST_SELECTOR = "shreddit-post";

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
