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

// FIX (live-count-not-updating-while-scrolling bug): MutationObserver only
// fires for actual DOM node insertions/removals (childList mutations).
// Instagram - like most infinite-scroll feeds - is known to virtualize
// and recycle rendered rows for performance, which can mean a post
// becomes visible without any *new* <article> node ever being inserted
// under the observed root (an existing, already-seen node gets reused/
// repositioned instead). That would make the observer correctly fire
// zero times during a scroll session, even though more posts were
// genuinely viewed - matching exactly the reported symptom (counter is
// flat while scrolling, then jumps once on the next full page load/
// refresh, which does a fresh one-shot countNewPosts over whatever's
// actually in the DOM at that instant).
// This is not a confirmed root cause without live devtools inspection on
// a real device, so as a safety net - independent of whatever the real
// cause turns out to be - this adds a periodic full re-scan of the whole
// root alongside the existing observer, piggybacking on the same 10s
// interval already used to refresh the on-page badge. A full
// countNewPosts() pass is deduped by the same `seenPosts` WeakSet as the
// observer, so this is a safe, idempotent supplement, not a double-count.
function rescanForMissedPosts(root) {
  reportPostsAndRefreshBadge(countNewPosts([root]));
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
  // so refresh periodically too, not just on new posts. Also doubles as
  // the periodic rescan safety net above (see rescanForMissedPosts).
  setInterval(() => {
    feedLimiterRefreshBadge(badge, SITE);
    rescanForMissedPosts(root);
  }, 10000);

  observer.observe(root, { childList: true, subtree: true });
  // Count whatever's already rendered on load, not just future insertions.
  reportPostsAndRefreshBadge(countNewPosts([root]));
}

if (document.readyState === "complete" || document.readyState === "interactive") {
  start();
} else {
  window.addEventListener("DOMContentLoaded", start);
}
