// Reports page visibility for every tab so the background timer only counts
// time while a page is actually on screen. On Android the window can stay
// "active" while the app is backgrounded or the screen is locked; Firefox
// fires visibilitychange on exactly those transitions.
(() => {
  function feedLimiterReportVisibility() {
    browser.runtime
      .sendMessage({ type: "feed-limiter:visibility", visible: document.visibilityState === "visible" })
      .catch((e) => console.error("[feed-limiter] visibility report failed", e));
  }
  document.addEventListener("visibilitychange", feedLimiterReportVisibility);
  feedLimiterReportVisibility();
})();
