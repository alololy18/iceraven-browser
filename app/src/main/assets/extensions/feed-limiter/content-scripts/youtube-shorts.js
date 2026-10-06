// YouTube Shorts adapter: counts Shorts actually swiped to.
// The player pre-renders the previous, current and next Short, so counting
// insertions would overcount; a renderer gaining is-active means you landed
// on it. Regular watch pages are tracked by youtube-watch.js instead.
const SITE = "youtube.com/shorts";
const RENDERER_TAG = "YTD-REEL-VIDEO-RENDERER";

const seenActive = new WeakSet();

function reportPosts(count) {
  if (count <= 0) return;
  browser.runtime
    .sendMessage({ type: "feed-limiter:posts-seen", site: SITE, count })
    .catch((e) => console.error("[feed-limiter] posts-seen report failed", SITE, e));
}

function handleRenderer(renderer) {
  if (renderer.hasAttribute("is-active") && !seenActive.has(renderer)) {
    seenActive.add(renderer);
    reportPosts(1);
  }
}

function scanAddedNode(node) {
  if (node.nodeType !== Node.ELEMENT_NODE) return;
  if (node.tagName === RENDERER_TAG) handleRenderer(node);
  node.querySelectorAll?.(RENDERER_TAG.toLowerCase()).forEach(handleRenderer);
}

const observer = new MutationObserver((mutations) => {
  for (const m of mutations) {
    if (m.type === "attributes" && m.target.tagName === RENDERER_TAG) {
      handleRenderer(m.target);
    }
    for (const node of m.addedNodes) scanAddedNode(node);
  }
});

function start() {
  observer.observe(document.body, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ["is-active"]
  });
  document.querySelectorAll(RENDERER_TAG.toLowerCase()).forEach(handleRenderer);
}

if (document.readyState === "complete" || document.readyState === "interactive") {
  start();
} else {
  window.addEventListener("DOMContentLoaded", start);
}
