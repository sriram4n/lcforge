// Library: everything you have imported, the ember, and the paste importer.
//
// The list is a problem table in LeetCode's shape — status, title, tags, difficulty, acceptance —
// because that is the table your eyes already know how to scan. Every number in it was already in
// the reply from /api/problems; the old list just threw most of them away.

import { api, el, clear, toast, setCrumbs, setActions } from '../app.js';
import { stats } from '../stats.js';
import { heatmapCard } from '../heatmap.js';
import { hearthCard } from '../hearth.js';

const DIFF_ORDER = { Easy: 1, Medium: 2, Hard: 3 };

export async function renderLibrary(app) {
  setCrumbs();
  setActions(el('a', { class: 'btn', href: 'https://leetcode.com/problemset/', target: '_blank', rel: 'noopener' },
                'Open LeetCode ↗'));

  const { problems } = await api('/api/problems');
  const solvedCount = problems.filter(p => status(p) === 'solved').length;

  const page = el('div', { class: 'page narrow' });

  page.append(
    el('h1', {}, 'Your problems'),
    el('p', { class: 'sub' },
      problems.length
        ? `${problems.length} imported · ${solvedCount} solved`
        : 'Nothing here yet. Bring in your first problem below.'));

  // ---------- the ember, and the year behind it ----------
  // The hearth shows from the first visit — it is a companion, and one you only meet after proving
  // yourself is a worse companion. The heatmap waits until there is something in it, because an
  // empty year of grey squares is a progress bar at zero.
  try {
    const hearth = await hearthCard();
    if (hearth) page.append(hearth);

    const s = await stats();
    if ((s.totals?.runs || 0) > 0) page.append(heatmapCard(s));
  } catch (e) {
    console.error('stats unavailable', e);   // the library still works without either
  }

  // ---------- the table ----------

  if (problems.length) page.append(problemTable(problems));

  // ---------- importer ----------
  // Open and first when the library is empty, because then it is the only thing to do. Folded
  // away under the table once there is a table, because then it is the thing you do least.

  const importer = importCard();
  if (!problems.length) page.append(welcome());
  if (problems.length) {
    const fold = el('details', { class: 'import-fold' },
      el('summary', {}, '+ Add a problem by pasting it'),
      importer.node);
    fold.addEventListener('toggle', () => { if (fold.open) importer.focus(); });
    page.append(fold);
  } else {
    page.append(importer.node);
  }

  app.append(page);
  if (!problems.length) importer.focus();
}

/** The whole idea, in three steps — shown only until there is a library to show instead. */
function welcome() {
  const step = (n, title, text) => el('div', { class: 'welcome-step' },
    el('span', { class: 'n' }, String(n)), el('b', {}, title), el('span', {}, text));
  return el('div', { class: 'welcome' },
    step(1, 'Bring a problem', 'Paste one below, or press the ⚡ the browser extension adds to every leetcode.com problem.'),
    step(2, 'Run, then Submit', 'Run tries the case in front of you. Submit judges every case — and is what counts as solving it.'),
    step(3, 'Step through it', 'When an answer is wrong, Debug replays your code line by line, with every variable on screen.'));
}

/** solved: accepted at least once. attempted: tried, never accepted. todo: never run. */
function status(p) {
  if ((p.passes || 0) > 0) return 'solved';
  if ((p.runs || 0) > 0 || (p.submits || 0) > 0) return 'attempted';
  return 'todo';
}

function problemTable(problems) {
  let query = '';
  let statusFilter = 'all';
  let diffFilter = 'all';
  let sort = { key: 'recent', dir: 1 };

  const hasBest = problems.some(p => (p.bestSteps || 0) > 0);

  const search = el('input', {
    type: 'text', class: 'search', placeholder: 'Search titles and topics',
    'aria-label': 'Search problems', spellcheck: 'false',
    onInput: e => { query = e.target.value.trim().toLowerCase(); draw(); },
  });

  const chipRow = (options, get, set) => {
    const row = el('div', { class: 'filter-chips' });
    const paint = () => {
      clear(row);
      for (const [id, text] of options) {
        row.append(el('button', {
          class: `fchip ${get() === id ? 'on' : ''}`,
          'aria-pressed': String(get() === id),
          onClick: () => { set(id); paint(); draw(); },
        }, text));
      }
    };
    paint();
    return row;
  };

  const statusChips = chipRow(
    [['all', 'All'], ['solved', 'Solved'], ['attempted', 'Attempted'], ['todo', 'To do']],
    () => statusFilter, v => { statusFilter = v; });
  const diffChips = chipRow(
    [['all', 'Any'], ['Easy', 'Easy'], ['Medium', 'Medium'], ['Hard', 'Hard']],
    () => diffFilter, v => { diffFilter = v; });

  const count = el('span', { class: 'muted table-count' });
  const body = el('div', { class: 'ptable-body', role: 'rowgroup' });

  // Sortable headers. A second click on the same header flips the direction.
  const headCell = (key, text, cls = '') => {
    const b = el('button', { class: `ph ${cls}`, role: 'columnheader', onClick: () => {
      sort = sort.key === key ? { key, dir: -sort.dir } : { key, dir: 1 };
      draw();
    } }, text, el('span', { class: 'sort-mark' }));
    b.dataset.key = key;
    return b;
  };
  const head = el('div', { class: `ptable-head ${hasBest ? 'with-best' : ''}`, role: 'row' },
    el('span', { class: 'ph status', role: 'columnheader', 'aria-label': 'Status' }),
    headCell('title', 'Title'),
    el('span', { class: 'ph tags', role: 'columnheader' }, 'Topics'),
    headCell('difficulty', 'Difficulty'),
    headCell('acceptance', 'Acceptance', 'num'),
    hasBest ? headCell('best', 'Best', 'num') : null);

  const table = el('div', { class: 'ptable', role: 'table', 'aria-label': 'Your problems' }, head, body);

  function draw() {
    let rows = problems.filter(p => {
      if (statusFilter !== 'all' && status(p) !== statusFilter) return false;
      if (diffFilter !== 'all' && p.difficulty !== diffFilter) return false;
      if (!query) return true;
      const hay = `${p.title || p.slug} ${(p.topicTags || []).join(' ')}`.toLowerCase();
      return hay.includes(query);
    });

    const by = {
      recent: () => 0,                             // the server already sends newest first
      title: (a, b) => titleKey(a).localeCompare(titleKey(b), undefined, { numeric: true }),
      difficulty: (a, b) => (DIFF_ORDER[a.difficulty] || 9) - (DIFF_ORDER[b.difficulty] || 9),
      acceptance: (a, b) => rate(a) - rate(b),
      best: (a, b) => (a.bestSteps || Infinity) - (b.bestSteps || Infinity),
    }[sort.key];
    rows = rows.map((p, i) => ({ p, i }))
      .sort((x, y) => (by(x.p, y.p) * sort.dir) || (x.i - y.i))   // stable: ties keep server order
      .map(x => x.p);

    for (const h of head.querySelectorAll('.ph[data-key]')) {
      const on = h.dataset.key === sort.key;
      h.classList.toggle('sorted', on);
      h.querySelector('.sort-mark').textContent = on ? (sort.dir > 0 ? '↑' : '↓') : '';
    }

    clear(body);
    count.textContent = rows.length === problems.length
      ? `${problems.length} problem${problems.length === 1 ? '' : 's'}`
      : `${rows.length} of ${problems.length}`;

    if (!rows.length) {
      body.append(el('div', { class: 'empty-note' }, el('b', {}, 'No matches'),
        el('span', {}, 'Nothing fits those filters. Try fewer of them.')));
      return;
    }
    for (const p of rows) body.append(row(p));
  }

  function row(p) {
    const st = status(p);
    const acc = acceptance(p);
    return el('a', { class: `prow ${hasBest ? 'with-best' : ''}`, href: `#/solve/${encodeURIComponent(p.slug)}`, role: 'row' },
      el('span', { class: `pstatus ${st}`, title: { solved: 'Solved', attempted: 'Attempted, not yet accepted', todo: 'Not tried yet' }[st] },
        st === 'solved' ? '✓' : st === 'attempted' ? '◐' : ''),
      el('span', { class: 'ptitle' }, p.title || p.slug),
      el('span', { class: 'ptags' }, (p.topicTags || []).slice(0, 3).join(' · ')),
      el('span', {}, el('span', { class: `diff ${p.difficulty || 'none'}` }, p.difficulty || '—')),
      el('span', { class: 'pnum', title: acc.title }, acc.text),
      hasBest ? el('span', { class: 'pnum', title: 'fewest steps a correct trace has taken' },
                    p.bestSteps ? `${p.bestSteps} steps` : '') : null);
  }

  draw();

  return el('section', { class: 'library' },
    el('div', { class: 'library-head' },
      el('h2', {}, 'Library'),
      count),
    el('div', { class: 'ptable-tools' }, search, statusChips, diffChips),
    table);
}

function titleKey(p) { return String(p.title || p.slug); }

/** Accepted submissions over all submissions, or -1 when there is nothing to divide. */
function rate(p) {
  const s = p.submits || 0;
  return s ? Math.min(1, (p.passes || 0) / s) : -1;
}

function acceptance(p) {
  const s = p.submits || 0;
  if (!s) return { text: '—', title: 'not submitted yet' };
  // Clamped: records written mid-migration can briefly hold more passes than submissions, and a
  // rate above 100% would read as a bug in the arithmetic rather than in the history.
  const pct = Math.min(100, Math.round(((p.passes || 0) / s) * 100));
  return { text: `${pct}%`, title: `${p.passes || 0} accepted of ${s} submitted` };
}

function importCard() {
  const textarea = el('textarea', {
    spellcheck: 'false',
    placeholder:
      'Paste a problem here — title, description and the Example blocks.\n\n' +
      'Example:\n' +
      '1. Two Sum\n' +
      'Easy\n' +
      'Given an array of integers nums and an integer target, return indices...\n\n' +
      'Example 1:\n' +
      'Input: nums = [2,7,11,15], target = 9\n' +
      'Output: [0,1]'
  });

  const importBtn = el('button', { class: 'primary', onClick: doImport }, 'Import');

  async function doImport() {
    const text = textarea.value.trim();
    if (!text) { toast('Paste a problem first', 'err'); return; }
    importBtn.disabled = true;
    importBtn.textContent = 'Importing…';
    try {
      const r = await api('/api/paste', { text });
      if (!r.ok) throw new Error(r.error || 'import failed');
      toast('Imported', 'ok');
      location.hash = `#/solve/${encodeURIComponent(r.slug)}`;
    } catch (e) {
      toast(e.message, 'err');
      importBtn.disabled = false;
      importBtn.textContent = 'Import';
    }
  }

  const node = el('div', { class: 'import-card' },
    el('h3', {}, 'Add a problem'),
    textarea,
    el('div', { class: 'row-gap', style: 'margin-top:12px' }, importBtn),
    el('div', { class: 'hint' },
      'Faster: the LCForge browser extension puts a ⚡ on every leetcode.com problem page — one ',
      'click and the problem, its examples and its starter code land here.'));

  return { node, focus: () => textarea.focus() };
}
