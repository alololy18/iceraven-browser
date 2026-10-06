// Instagram adapter: counts feed posts as they are inserted.
// "main article" is unverified against the live site.
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
  browser.runtime
    .sendMessage({ type: "feed-limiter:posts-seen", site: SITE, count })
    .catch((e) => console.error("[feed-limiter] posts-seen report failed", SITE, e));
}

// On a cold load Instagram can replace <main> itself while hydrating, which
// leaves an observer bound to the old node watching a detached subtree. The
// root is therefore re-checked periodically and the observer rebound; the
// WeakSet keeps rescans idempotent.
let root = null;

const observer = new MutationObserver((mutations) => {
  let total = 0;
  for (const m of mutations) total += countNewPosts(m.addedNodes);
  reportPosts(total);
});

function currentLiveRoot() {
  return document.querySelector("main") || document.body;
}

function bindObserverTo(node) {
  root = node;
  observer.disconnect();
  observer.observe(root, { childList: true, subtree: true });
}

// Rescans even when the root is unchanged, in case rows were recycled in
// place rather than inserted.
function healObserverIfRootChanged() {
  const live = currentLiveRoot();
  if (live !== root || !root.isConnected) bindObserverTo(live);
  reportPosts(countNewPosts([root]));
}

function start() {
  bindObserverTo(currentLiveRoot());
  reportPosts(countNewPosts([root]));

  // Fast checks while hydration swaps are likely, then a slow steady check.
  let healChecks = 0;
  const fastHeal = setInterval(() => {
    healObserverIfRootChanged();
    healChecks++;
    if (healChecks >= 10) clearInterval(fastHeal);
  }, 1000);
  setInterval(healObserverIfRootChanged, 10000);
}

if (document.readyState === "complete" || document.readyState === "interactive") {
  start();
} else {
  window.addEventListener("DOMContentLoaded", start);
}
