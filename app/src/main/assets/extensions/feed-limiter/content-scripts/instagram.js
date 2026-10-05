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

// FIX (first-load-counter-not-updating-while-scrolling bug):
// Root cause: `start()` captured `document.querySelector("main") || document.body`
// ONCE into a local `root` variable and bound the MutationObserver to that exact
// node reference for the rest of the page's lifetime. On a first/cold load,
// Instagram's React app typically renders an initial skeleton/loading shell
// first and then replaces large chunks of the DOM - including, potentially,
// the very `<main>` node itself - once the real feed hydrates. If that
// swap replaces the observed node rather than mutating its children in place,
// the MutationObserver keeps watching a now-detached, dead subtree: it never
// fires again, and `countNewPosts` is never re-run, so the counter looks frozen
// even though real feed activity is happening live.
//
// On a second/warm visit (cached JS bundles, cached API responses, warm
// browser/render caches), hydration finishes fast enough that the node
// present at the time `start()` runs is already the final, stable `<main>`
// element - so the observer stays correctly bound and scrolling increments
// the counter as expected. This matches the reported symptom exactly:
// works after a refresh/second visit, not on first load.
//
// FIX: make the observer self-healing. Instead of trusting `root` forever,
// periodically check whether the live DOM still has the same node mounted
// (`root.isConnected` and still the same reference returned by a fresh
// querySelector). If it has changed or detached, disconnect the old
// observer, rebind to the new live node, and do a fresh full scan of it
// (deduped by the same `seenPosts` WeakSet, so this is safe/idempotent).
let root = null;
let badge = null;

const observer = new MutationObserver((mutations) => {
  let total = 0;
  for (const m of mutations) total += countNewPosts(m.addedNodes);
  reportPostsAndRefreshBadge(total);
});

function reportPostsAndRefreshBadge(count) {
  reportPosts(count);
  if (badge) feedLimiterRefreshBadge(badge, SITE);
}

function currentLiveRoot() {
  return document.querySelector("main") || document.body;
}

function bindObserverTo(node) {
  root = node;
  observer.disconnect();
  observer.observe(root, { childList: true, subtree: true });
}

// Full re-scan safety net, also used whenever we (re)bind to a node, so
// posts already present on the new/current root get counted even if no
// further mutation ever fires for them.
function rescanRoot() {
  reportPostsAndRefreshBadge(countNewPosts([root]));
}

// Periodically verify the observed root is still the live one. This is the
// core of the fix: on first load, if Instagram swaps out <main> during
// hydration, this check notices the stale/detached node and rebinds to
// the fresh one - instead of silently watching a dead subtree forever.
function healObserverIfRootChanged() {
  const live = currentLiveRoot();
  if (live !== root || !root.isConnected) {
    bindObserverTo(live);
    rescanRoot();
  } else {
    // Root is still correct; still worth a defensive full re-scan in case
    // Instagram virtualized/recycled rows without inserting new nodes
    // (existing node reused/repositioned rather than a true DOM insert).
    rescanRoot();
  }
}

function start() {
  bindObserverTo(currentLiveRoot());
  // Guarded rather than called directly: this zip's badge.js baseline does
  // not define feedLimiterSetupVisibilityReporting at all (see findings
  // below), and an optional-chained call on an undeclared identifier still
  // throws a ReferenceError - only a typeof check is safe here.
  if (typeof feedLimiterSetupVisibilityReporting === "function") {
    feedLimiterSetupVisibilityReporting(SITE);
  }
  badge = feedLimiterCreateBadge();
  feedLimiterRefreshBadge(badge, SITE);

  // Count whatever's already rendered on load, not just future insertions.
  reportPostsAndRefreshBadge(countNewPosts([root]));

  // Tight polling for the first stretch after load, when hydration-driven
  // root swaps are most likely to happen, then fall back to the steady
  // 10s cadence used for badge/timer refresh generally.
  let healChecks = 0;
  const fastHeal = setInterval(() => {
    healObserverIfRootChanged();
    healChecks++;
    if (healChecks >= 10) clearInterval(fastHeal); // ~10s of fast checks at 1s each
  }, 1000);

  setInterval(() => {
    feedLimiterRefreshBadge(badge, SITE);
    healObserverIfRootChanged();
  }, 10000);
}

if (document.readyState === "complete" || document.readyState === "interactive") {
  start();
} else {
  window.addEventListener("DOMContentLoaded", start);
}
