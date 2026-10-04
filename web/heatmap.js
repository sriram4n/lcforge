// The activity heatmap: a year of practice, one square a day.
//
// Deliberately not a chart of how *well* you did. It only says whether you turned up, because that
// is the thing a year-long habit is actually made of, and a grid that dimmed on your bad days would
// punish exactly the sessions that needed the most nerve.

import { el } from './app.js';
import { svgEl, svgTitle } from './svg.js';

const CELL = 11;
const GAP = 3;
const ROWS = 7;
const DAYS = ['Mon', 'Wed', 'Fri'];

/**
 * `from` and `today` are "YYYY-MM-DD" as the server's local day — the browser is the same machine,
 * but building Date objects from those strings would reintroduce a UTC parse and slide every square
 * by one for anyone west of Greenwich. So the grid is walked as plain dates and never as timestamps.
 */
export function heatmap({ from, counts }, today) {
  const start = mondayOnOrBefore(parseDay(from));
  const end = parseDay(today);

  const weeks = Math.floor(daysBetween(start, end) / 7) + 1;
  const width = weeks * (CELL + GAP) + 30;
  const height = ROWS * (CELL + GAP) + 18;

  const svg = svgEl('svg', { class: 'heat', width, height, viewBox: `0 0 ${width} ${height}` });

  for (let i = 0; i < DAYS.length; i++) {
    const t = svgEl('text', { x: 0, y: (i * 2 + 1) * (CELL + GAP) + CELL - 1,
                              class: 'heat-label', 'font-size': 9 });
    t.textContent = DAYS[i];
    svg.append(t);
  }

  let total = 0, activeDays = 0;
  const cursor = new Date(start);
  let lastMonth = -1;

  for (let w = 0; w < weeks; w++) {
    for (let d = 0; d < ROWS; d++) {
      if (cursor > end) break;
      const key = dayKey(cursor);
      const n = counts[key] || 0;
      if (n > 0) { total += n; activeDays++; }

      const cls = ['heat-cell', level(n), key === today ? 'today' : ''].filter(Boolean).join(' ');
      svg.append(svgEl('rect', {
        x: w * (CELL + GAP) + 30, y: d * (CELL + GAP),
        width: CELL, height: CELL, rx: 2, class: cls,
      }, svgTitle(n === 0 ? `${key} — nothing` : `${key} — ${n} run${n === 1 ? '' : 's'}`)));

      cursor.setDate(cursor.getDate() + 1);
    }
    // Month initial above the first week that starts a new month.
    const m = cursor.getMonth();
    if (m !== lastMonth && w < weeks - 1) {
      const t = svgEl('text', { x: w * (CELL + GAP) + 30, y: height - 4,
                                class: 'heat-label', 'font-size': 9 });
      t.textContent = cursor.toLocaleString(undefined, { month: 'short' });
      svg.append(t);
      lastMonth = m;
    }
  }

  return { svg, total, activeDays };
}

/**
 * Four levels, fixed rather than relative to your busiest day.
 *
 * A relative scale rewrites history: one heroic Sunday would fade every ordinary day of the year to
 * the palest shade, so the grid would get *emptier*-looking the harder you worked once.
 */
function level(n) {
  if (n === 0) return '';
  if (n <= 2) return 'l1';
  if (n <= 5) return 'l2';
  if (n <= 10) return 'l3';
  return 'l4';
}

function parseDay(s) {
  const [y, m, d] = String(s).split('-').map(Number);
  return new Date(y, (m || 1) - 1, d || 1);
}

function dayKey(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function mondayOnOrBefore(d) {
  const out = new Date(d);
  const shift = (out.getDay() + 6) % 7;   // JS weeks start on Sunday; ours start on Monday
  out.setDate(out.getDate() - shift);
  return out;
}

function daysBetween(a, b) {
  return Math.round((b - a) / 86400000);
}

/** The whole card: heading, grid, and a one-line summary under it. */
export function heatmapCard(stats) {
  const { svg, total, activeDays } = heatmap(stats.heatmap, stats.today);
  const t = stats.totals || {};
  return el('div', { class: 'heat-card' },
    el('div', { class: 'heat-scroll' }, svg),
    el('div', { class: 'heat-foot' },
      el('span', {}, activeDays
        ? `${total} run${total === 1 ? '' : 's'} across ${activeDays} day${activeDays === 1 ? '' : 's'}`
        : 'No runs yet — press Run on anything and this fills in'),
      el('span', { class: 'muted' },
        `${t.solved || 0} solved · ${t.passes || 0} pass${(t.passes || 0) === 1 ? '' : 'es'}`)));
}
