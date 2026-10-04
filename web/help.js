// The help sheet: every shortcut in one place, and what LCForge is and where it keeps your things.
//
// Opened with `?` from anywhere that is not a text field, or with the button in the top bar. One
// sheet rather than a shortcuts dialog and an about dialog, because the less chrome a tool has, the
// more of it is the tool.

import { api, el, toast } from './app.js';

const MAC = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
const MOD = MAC ? '⌘' : 'Ctrl';

const GROUPS = [
  ['Solving', [
    [[MOD, '⏎'], 'Run the selected case'],
    [[MOD, '⇧', '⏎'], 'Submit — judge every case'],
    [[MOD, 'S'], 'Save now (it autosaves anyway)'],
    [['⇧', 'Alt', 'F'], 'Format the file'],
    [[MOD, 'Space'], 'Suggestions'],
    [[MOD, 'scroll'], 'Resize the code, and only the code'],
    [[MOD, '0'], 'Reset the code size'],
  ]],
  ['Stepping through (Debug)', [
    [['→'], 'Next step'], [['←'], 'Previous step'],
    [['N'], 'Next step where something changed'], [['P'], 'Previous change'],
    [['Space'], 'Play / pause'], [['Home'], 'First step'], [['End'], 'Last step'],
  ]],
  ['Anywhere', [
    [['?'], 'This sheet'], [['Esc'], 'Close it'],
  ]],
];

let open = null;     // the sheet, while it is showing

/** The "?" in the top bar. Mount once; it also arms the `?` key for the whole app. */
export function helpButton() {
  document.addEventListener('keydown', e => {
    if (e.key !== '?' || e.ctrlKey || e.metaKey || e.altKey) return;
    // A question mark typed into a testcase, the search box or the editor is a question mark.
    const t = e.target;
    if (t.closest?.('input, textarea, select, [contenteditable=""], [contenteditable="true"], .monaco-editor')) return;
    e.preventDefault();
    open ? close() : show();
  });
  return el('button', {
    class: 'icon-btn', title: 'Shortcuts and about (?)', 'aria-label': 'Help — shortcuts and about',
    onClick: () => (open ? close() : show()),
  }, '?');
}

function show() {
  const about = el('div', { class: 'about-body muted' }, 'Loading…');
  const sheet = el('div', { class: 'sheet', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'sheet-title' },
    el('div', { class: 'sheet-head' },
      el('h2', { id: 'sheet-title' }, 'Shortcuts'),
      el('button', { class: 'icon-btn', 'aria-label': 'Close', onClick: close }, '×')),
    el('div', { class: 'sheet-grid' },
      ...GROUPS.map(([title, rows]) => el('section', {},
        el('h3', {}, title),
        el('dl', { class: 'keys' },
          ...rows.flatMap(([keys, what]) => [
            el('dt', {}, ...keys.flatMap((k, i) => [i ? el('span', { class: 'plus' }, '+') : null, el('kbd', {}, k)])),
            el('dd', {}, what),
          ]))))),
    el('div', { class: 'sheet-about' }, el('h2', {}, 'About'), about));

  const veil = el('div', { class: 'veil', onClick: e => { if (e.target === veil) close(); } }, sheet);
  document.body.append(veil);
  open = { veil, returnTo: document.activeElement };
  document.addEventListener('keydown', onEsc);
  sheet.querySelector('.icon-btn').focus();
  fillAbout(about);
}

function close() {
  if (!open) return;
  open.veil.remove();
  document.removeEventListener('keydown', onEsc);
  // Give focus back to wherever it was, so closing the sheet does not strand you at the top of
  // the page with the keyboard.
  open.returnTo?.focus?.();
  open = null;
}

function onEsc(e) { if (e.key === 'Escape') { e.preventDefault(); close(); } }

async function fillAbout(box) {
  let a;
  try { a = await api('/api/about'); } catch { a = null; }
  box.replaceChildren();
  box.classList.remove('muted');

  const path = a?.home || '(unavailable)';
  const copy = el('button', { class: 'ghost mini-btn', onClick: async () => {
    try { await navigator.clipboard.writeText(path); toast('Path copied', 'ok'); }
    catch { toast('Could not copy — select the path instead', 'err'); }
  } }, 'Copy');

  box.append(
    el('p', {}, el('b', {}, `LCForge ${a?.version || ''}`), ' — a local debugger for LeetCode-style problems in Java. ',
      `Running on Java ${a?.java || '?'}.`),
    el('p', {}, 'Your problems, solutions and history live in one folder on this computer:'),
    el('div', { class: 'path-row' }, el('code', { class: 'path' }, path), copy),
    el('p', {}, 'Your code never leaves this machine. The only things fetched from the internet are the code ',
      'editor itself (Monaco, from a CDN, unless your copy came with it built in) and problem diagrams, ',
      'straight from LeetCode’s own servers — nothing is sent anywhere in return.'),
    el('p', { class: 'fine' }, 'LCForge is an independent project and is not affiliated with, endorsed by or ',
      'connected to LeetCode. LeetCode is a trademark of its owner. The code editor is Monaco (MIT licence).'));
}
