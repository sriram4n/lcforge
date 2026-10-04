// The streak chip in the topbar.
//
// Mounted once beside the palette picker rather than by a view, because it lives outside
// #topbar-actions — which the router clears on every navigation — and because a number that
// vanished when you opened a problem would be a strange thing to call persistent.

import { el } from './app.js';
import { stats, onChange } from './stats.js';

export function streakChip() {
  const flame = el('span', { class: 'flame' }, '▲');
  const count = el('b', {});
  const chip = el('div', { class: 'streak-chip', hidden: true }, flame, count);

  async function draw() {
    let s;
    try { s = (await stats()).streak || {}; } catch { return; }   // server down; say nothing
    const n = s.current || 0;

    // Zero is not a milestone, it is an accusation. Show nothing until there is something.
    chip.hidden = n === 0;
    if (n === 0) return;

    count.textContent = n;
    chip.classList.toggle('pending', !s.todayDone);
    chip.title = s.todayDone
      ? `${n} day streak · today is done` + (s.best > n ? ` · best ${s.best}` : '')
      : `${n} day streak · nothing yet today`;
  }

  draw();
  onChange(draw);
  return chip;
}
