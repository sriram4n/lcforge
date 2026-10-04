// One cached read of /api/stats, shared by everything that wants to know how your practice is going.
//
// The heatmap, the streak chip, the pattern grid and the palette picker's unlock state are four
// readings of one response. Fetching it four times would be four walks over the same log and four
// chances to render a page where the chip and the grid disagree about today.

import { api } from './app.js';

let cache = null;
let inflight = null;

export async function stats({ fresh = false } = {}) {
  if (fresh) { cache = null; inflight = null; }
  if (cache) return cache;
  if (!inflight) {
    inflight = api('/api/stats', { days: 365 })
      .then(r => { cache = r; inflight = null; return r; })
      .catch(e => { inflight = null; throw e; });
  }
  return inflight;
}

/** Call after anything that could change the numbers — a run, a trace, a feed. */
export function invalidate() {
  cache = null;
  for (const fn of listeners) { try { fn(); } catch (e) { console.error(e); } }
}

const listeners = new Set();

/** Subscribe a persistent widget (the streak chip, the ember) to refresh itself. */
export function onChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
