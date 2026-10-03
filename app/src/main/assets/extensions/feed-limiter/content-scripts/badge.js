// Shared on-page debug badge. Loaded before each site adapter so you can
// see post/timer progress directly on the phone screen - there's no
// desktop debugger attached when testing purely on-device, so this is
// the only visibility into whether counting is actually working.
// Remove this file (and its manifest.json entries) once Phase 1 is
// validated and you no longer need visible debug output.

function feedLimiterCreateBadge() {
  const badge = document.createElement("div");
  badge.id = "feed-limiter-debug-badge";
  Object.assign(badge.style, {
    position: "fixed",
    bottom: "16px",
    right: "16px",
    zIndex: 2147483647,
    background: "rgba(0,0,0,0.75)",
    color: "#fff",
    font: "12px/1.4 system-ui, sans-serif",
    padding: "8px 10px",
    borderRadius: "8px",
    pointerEvents: "none",
    whiteSpace: "pre-line"
  });
  badge.textContent = "Feed Limiter: loading...";
  document.documentElement.appendChild(badge);
  return badge;
}

async function feedLimiterRefreshBadge(badge, site) {
  let status;
  try {
    status = await browser.runtime.sendMessage({ type: "feed-limiter:get-status", site });
  } catch (e) {
    badge.textContent = "Feed Limiter: bg script unreachable";
    return;
  }
  if (!status) {
    badge.textContent = `Feed Limiter: no config for ${site}`;
    return;
  }
  const { entry, cfg } = status;
  const lines = [`Feed Limiter - ${site}`];
  if (cfg.postLimit != null) lines.push(`Posts: ${entry.postsSeen} / ${cfg.postLimit}`);
  if (cfg.timerMinutesLimit != null) lines.push(`Time: ${entry.minutesUsed} / ${cfg.timerMinutesLimit} min`);
  lines.push(entry.blocked ? "BLOCKED" : "active");
  badge.textContent = lines.join("\n");
}
