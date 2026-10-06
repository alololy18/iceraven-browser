// Counts a regular YouTube video as watched once >= 90% of its duration has
// actually been played. Wrapped in an IIFE because this shares a content-script
// scope with youtube-shorts.js on /shorts/ pages.
(() => {
  const COMPLETION_RATIO = 0.9;
  // Media time may advance by at most the wall-clock time since the previous
  // timeupdate (times playback rate) plus this slack; anything larger is a
  // skip. Bounding by wall clock keeps late, throttled events in background
  // play or PiP counting.
  const STEP_SLACK_SECONDS = 1;
  const VIDEO_WAIT_MS = 15000;
  const POLL_MS = 1000;
  const VIDEO_ID_RE = /^[A-Za-z0-9_-]{11}$/;

  let videoId = null;
  let video = null;
  let playedSeconds = 0;
  let lastTime = null;
  let lastWallMs = 0;
  let reported = false;
  let watchStartedAt = 0;
  let missingLogged = false;

  function currentWatchId() {
    if (location.pathname !== "/watch") return null;
    const id = new URLSearchParams(location.search).get("v");
    return id && VIDEO_ID_RE.test(id) ? id : null;
  }

  // Matched anywhere in the page because the mobile and desktop players put
  // the class on different containers.
  function adShowing() {
    return document.querySelector(".ad-showing, .ad-interrupting") !== null;
  }

  function onTimeUpdate() {
    if (reported || !video) return;
    if (video.paused || adShowing()) {
      lastTime = null;
      return;
    }
    const now = video.currentTime;
    const wallMs = Date.now();
    if (lastTime !== null) {
      const step = now - lastTime;
      const maxStep = ((wallMs - lastWallMs) / 1000) * Math.max(video.playbackRate || 1, 1) + STEP_SLACK_SECONDS;
      if (step > 0 && step <= maxStep) playedSeconds += step;
    }
    lastTime = now;
    lastWallMs = wallMs;

    const duration = video.duration;
    if (Number.isFinite(duration) && duration > 0 && playedSeconds >= COMPLETION_RATIO * duration) {
      reported = true;
      browser.runtime
        .sendMessage({ type: "feed-limiter:video-watched", videoId })
        .catch((e) => console.error("[feed-limiter] video-watched report failed", videoId, e));
    }
  }

  function onSeeking() {
    lastTime = null;
  }

  // Ads play in the same <video> element under a different source; a new
  // source must never inherit the previous one's progress.
  function onLoadStart() {
    playedSeconds = 0;
    lastTime = null;
  }

  function bind(element) {
    if (video === element) return;
    if (video) {
      video.removeEventListener("timeupdate", onTimeUpdate);
      video.removeEventListener("seeking", onSeeking);
      video.removeEventListener("loadstart", onLoadStart);
    }
    video = element;
    lastTime = null;
    if (video) {
      video.addEventListener("timeupdate", onTimeUpdate);
      video.addEventListener("seeking", onSeeking);
      video.addEventListener("loadstart", onLoadStart);
    }
  }

  // YouTube is a single-page app, so the URL and the <video> element are
  // re-checked on an interval rather than once at load.
  function poll() {
    const id = currentWatchId();
    if (id !== videoId) {
      videoId = id;
      playedSeconds = 0;
      lastTime = null;
      reported = false;
      missingLogged = false;
      watchStartedAt = Date.now();
    }
    if (!videoId) {
      bind(null);
      return;
    }
    bind(document.querySelector(".html5-video-player video") || document.querySelector("video"));
    if (!video && !missingLogged && Date.now() - watchStartedAt > VIDEO_WAIT_MS) {
      missingLogged = true;
      console.error("[feed-limiter] no <video> element on a YouTube watch page; this video won't count", {
        url: location.href,
        videoId,
        waitedMs: VIDEO_WAIT_MS
      });
    }
  }

  setInterval(poll, POLL_MS);
  poll();
})();
