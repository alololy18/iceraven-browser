// Facebook adapter: counts feed posts as they are inserted.
// Class names are randomised per deploy; role="article" is the accessibility
// hook Facebook keeps on each post. Unverified against the live site.
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
  browser.runtime
    .sendMessage({ type: "feed-limiter:posts-seen", site: SITE, count })
    .catch((e) => console.error("[feed-limiter] posts-seen report failed", SITE, e));
}

const observer = new MutationObserver((mutations) => {
  let total = 0;
  for (const m of mutations) total += countNewPosts(m.addedNodes);
  reportPosts(total);
});

function start() {
  const root = document.querySelector('div[role="main"]') || document.body;
  observer.observe(root, { childList: true, subtree: true });
  reportPosts(countNewPosts([root]));
}

if (document.readyState === "complete" || document.readyState === "interactive") {
  start();
} else {
  window.addEventListener("DOMContentLoaded", start);
}
