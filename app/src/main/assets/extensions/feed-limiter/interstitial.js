function pickRandom(list, n) {
  const copy = [...list];
  const out = [];
  while (out.length < n && copy.length) {
    const i = Math.floor(Math.random() * copy.length);
    out.push(copy.splice(i, 1)[0]);
  }
  return out;
}

// Full ACTIVITY_LIST string -> shortened on-screen label + band background
// image. Keyed on the exact strings in config.js's ACTIVITY_LIST (untouched)
// so this stays correct no matter which 3 pickRandom() happens to return.
const ACTIVITY_DISPLAY = {
  "Read a book": {
    label: "Read a book",
    image: "images/activities/read-a-book.jpg"
  },
  "Read the news": {
    label: "Read the news",
    image: "images/activities/read-the-news.jpg"
  },
  "Go for a walk": {
    label: "Go for a walk",
    image: "images/activities/go-for-a-walk.jpg"
  },
  "Do a quick stretch or light exercise": {
    label: "Quick stretch",
    image: "images/activities/quick-stretch.jpg"
  },
  "Call a friend or family member": {
    label: "Call a friend",
    image: "images/activities/call-a-friend.jpg"
  },
  "Journal for a few minutes": {
    label: "Journal a bit",
    image: "images/activities/journal-a-bit.jpg"
  },
  "Tidy up a small space": {
    label: "Tidy a space",
    image: "images/activities/tidy-a-space.jpg"
  },
  "Make tea or coffee mindfully": {
    label: "Make tea or coffee",
    image: "images/activities/make-tea-or-coffee.jpg"
  },
  "Meditate or do a breathing exercise": {
    label: "Meditate briefly",
    image: "images/activities/meditate-briefly.jpg"
  },
  "Water your plants": {
    label: "Water your plants",
    image: "images/activities/water-your-plants.jpg"
  },
  "Update your to-do list": {
    label: "Update your to-dos",
    image: "images/activities/update-your-to-dos.jpg"
  },
  "Play a round of chess or do a puzzle": {
    label: "Chess or a puzzle",
    image: "images/activities/chess-or-a-puzzle.jpg"
  },
  "Review what you did till now": {
    label: "Review your day",
    image: "images/activities/review-your-day.jpg"
  },
  "Update your calendar": {
    label: "Update your calendar",
    image: "images/activities/update-your-calendar.jpg"
  },
  "Pet an animal": {
    label: "Pet an animal",
    image: "images/activities/pet-an-animal.jpg"
  }
};

// Renders the 3 randomly-picked activities (selection logic itself is
// untouched - see pickRandom(ACTIVITY_LIST, 3) above) into the 3 static
// band elements already present in interstitial.html. Each band gets a
// full-bleed background photo plus a single-line label; no other markup
// is generated, and there is no tap/scroll behavior to wire up.
function renderBands() {
  const picks = pickRandom(ACTIVITY_LIST, 3);
  const bandEls = [
    document.getElementById("band-0"),
    document.getElementById("band-1"),
    document.getElementById("band-2")
  ];

  picks.forEach((activity, i) => {
    const band = bandEls[i];
    const info = ACTIVITY_DISPLAY[activity];
    if (!band || !info) return;

    band.style.backgroundImage = `url("${info.image}")`;

    const label = band.querySelector(".band-label");
    if (label) label.textContent = info.label;
  });
}

renderBands();