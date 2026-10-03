function pickRandom(list, n) {
  const copy = [...list];
  const out = [];
  while (out.length < n && copy.length) {
    const i = Math.floor(Math.random() * copy.length);
    out.push(copy.splice(i, 1)[0]);
  }
  return out;
}

const ul = document.getElementById("activities");
for (const activity of pickRandom(ACTIVITY_LIST, 3)) {
  const li = document.createElement("li");
  li.textContent = activity;
  ul.appendChild(li);
}
