// LCForge — router, shared helpers, Monaco bootstrap.

import { renderLibrary } from './views/library.js';
import { renderSolve }   from './views/solve.js';
import { renderTimeline } from './views/timeline.js';
import { registerJavaCompletion } from './java-complete.js';
import { formatJava } from './format.js';
import { palettePicker, syncEditorTheme } from './palette.js';
import { streakChip } from './streak.js';
import { helpButton } from './help.js';
import { el, clear } from './dom.js';

export { el, clear };

// ---------- api ----------

export async function api(path, body) {
  const opts = body === undefined
    ? { method: 'GET' }
    : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
  let res;
  try {
    res = await fetch(path, opts);
  } catch {
    // Not an HTTP error — no answer at all. The server has gone (window closed, laptop slept, it
    // crashed), and every toast from here on would be a different symptom of that one fact.
    serverGone();
    throw new Error('LCForge is not running');
  }
  if (!res.ok) {
    let detail = res.statusText;
    try { detail = (await res.json()).error || detail; } catch { /* keep statusText */ }
    throw new Error(detail);
  }
  return res.json();
}

// ---------- dom helpers ----------

// ---------- when the server goes away ----------
//
// One bar that says what happened and what to do, instead of a toast per failed request. The page
// keeps everything it was holding — the code is in the editor, the cases are in memory — so the
// promise in the message is true: start LCForge again and carry on. It checks every few seconds
// and takes itself down the moment the server answers.

let gone = null;

function serverGone() {
  if (gone) return;
  gone = el('div', { class: 'offline-bar', role: 'alert' },
    el('b', {}, 'LCForge has stopped.'),
    el('span', {}, ' Start it again and this page carries on where it was — nothing you typed is lost.'));
  document.body.append(gone);
  const ping = async () => {
    try {
      const r = await fetch('/api/health', { cache: 'no-store' });
      if (r.ok) {
        gone.remove();
        gone = null;
        toast('Reconnected', 'ok');
        return;
      }
    } catch { /* still down */ }
    setTimeout(ping, 2500);
  };
  setTimeout(ping, 2500);
}


export function toast(message, kind = '') {
  const host = document.getElementById('toast-host');
  const t = el('div', { class: `toast ${kind}` }, message);
  host.append(t);
  setTimeout(() => {
    t.style.transition = 'opacity .2s';
    t.style.opacity = '0';
    setTimeout(() => t.remove(), 220);
  }, kind === 'err' ? 5200 : 2400);
}

/** Escape text destined for innerHTML. Problem statements arrive as HTML from LeetCode; anything
 *  we build ourselves goes through here. */
export function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

export function setCrumbs(...parts) {
  const bar = document.getElementById('crumbs');
  clear(bar);
  parts.forEach((p, i) => {
    if (i > 0) bar.append(el('span', { class: 'sep' }, '/'));
    bar.append(typeof p === 'string' ? el('span', { class: 'title' }, p) : p);
  });
}

export function setActions(...nodes) {
  const bar = document.getElementById('topbar-actions');
  clear(bar);
  nodes.filter(Boolean).forEach(n => bar.append(n));
}

// ---------- monaco ----------

let monacoReady = null;

// Where the editor comes from. An installer carries its own copy, so the editor works on a plane;
// a source checkout or the bare jar uses the CDN, which is what keeps them small. The server says
// which this is, rather than the page guessing with a request that 404s -- a console with an error
// in it on every load is a console you learn to stop reading.
const MONACO = {
  bundled: { base: '/vendor/monaco/vs', loader: '/vendor/monaco/vs/loader.js' },
  cdn: { base: 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.52.2/min/vs',
         loader: 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.52.2/min/vs/loader.min.js' },
};

function loadScript(src) {
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = src;
    s.onload = resolve;
    s.onerror = () => { s.remove(); reject(new Error(src)); };
    document.head.append(s);
  });
}

export function loadMonaco() {
  if (monacoReady) return monacoReady;
  monacoReady = (async () => {
    let which = 'cdn';
    try { which = (await api('/api/health')).editor === 'bundled' ? 'bundled' : 'cdn'; } catch { /* cdn */ }
    // The bundled copy first when there is one; the CDN as the fallback either way.
    let base = null;
    for (const m of which === 'bundled' ? [MONACO.bundled, MONACO.cdn] : [MONACO.cdn]) {
      try { await loadScript(m.loader); base = m.base; break; } catch { /* try the next */ }
    }
    if (!base || !window.require) {
      throw new Error('The code editor could not be loaded. This copy of LCForge fetches it from the ' +
                      'internet — check your connection, then reload.');
    }
    window.require.config({ paths: { vs: base } });
    return new Promise((resolve, reject) => {
      window.require(['vs/editor/editor.main'], () => {
        // Built from the active palette's CSS variables rather than written out here, so the editor
        // always matches the chrome around it.
        syncEditorTheme();
        // Registered here rather than per-editor: completion providers are global to the
        // language, and this promise is cached, so this runs exactly once.
        registerJavaCompletion(window.monaco);
        registerJavaFormatter(window.monaco);
        resolve(window.monaco);
      }, reject);
    });
  })();
  // A failure is not cached: back online, the next page you open tries again instead of
  // remembering forever that it once could not.
  monacoReady.catch(() => { monacoReady = null; });
  return monacoReady;
}

/**
 * Teach Monaco how to tidy Java.
 *
 * Registered as a formatting *provider* rather than bolted on as a command, which is the difference
 * between adding one shortcut and turning on a feature: Shift+Alt+F is already Monaco's binding for
 * Format Document, and the right-click menu entry, the command palette and any future format-on-
 * save all go through the same provider. One registration, every entry point.
 *
 * The whole document is replaced in a single edit so the change is one undo step — Ctrl+Z puts back
 * exactly what you had, which matters for a command you will sometimes fire by accident.
 */
function registerJavaFormatter(monaco) {
  monaco.languages.registerDocumentFormattingEditProvider('java', {
    provideDocumentFormattingEdits(model) {
      const text = model.getValue();
      const formatted = formatJava(text);
      if (formatted === text) return [];       // nothing to do; do not dirty the buffer
      return [{ range: model.getFullModelRange(), text: formatted }];
    },
  });
}

/**
 * The editor options both views share.
 *
 * `cursorSmoothCaretAnimation` and `smoothScrolling` are VS Code's: the caret glides between
 * positions instead of teleporting, and the viewport eases rather than jumping. It sounds
 * cosmetic, and it is — but it is also the thing that lets your eye keep hold of where the cursor
 * went, which matters most while stepping a trace.
 */
export function javaEditorOptions(extra = {}) {
  return {
    language: 'java',
    theme: 'lcforge',
    automaticLayout: true,
    fontSize: 13.5,
    fontFamily: 'JetBrains Mono, Cascadia Code, Consolas, monospace',
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    tabSize: 4,
    renderLineHighlight: 'line',
    padding: { top: 12 },

    // Wrap rather than scroll sideways. The editor shares its width with the problem statement and
    // the test panel, so a long line is normal here, and a horizontal scrollbar hides code you have
    // no reason to expect is missing. Continuation lines are indented to the depth of the line they
    // belong to, plus one step, so a wrapped line still reads as one statement.
    wordWrap: 'on',
    wrappingIndent: 'indent',

    cursorSmoothCaretAnimation: 'on',
    cursorBlinking: 'smooth',
    smoothScrolling: true,

    // Our completion provider already offers every declared name with its type, so Monaco's
    // word-based guesses would only duplicate them — and it suggests words out of comments and
    // strings too.
    wordBasedSuggestions: 'off',
    quickSuggestions: { other: true, comments: false, strings: false },
    suggestOnTriggerCharacters: true,
    tabCompletion: 'on',
    suggest: { showWords: false, localityBonus: true },

    ...extra,
  };
}

// ---------- session state ----------

/** Carries a trace from the solve view to the timeline view without re-running it. */
export const session = { trace: null, traceMeta: null };

// ---------- router ----------

const routes = [
  { re: /^\/?$/,                 view: renderLibrary },
  { re: /^\/solve\/([^/]+)$/,    view: renderSolve },
  { re: /^\/trace\/([^/]+)$/,    view: renderTimeline },
];

// Renders are serialized. Each view clears #app and then awaits (a fetch, Monaco loading) before
// appending, so two overlapping routes would both clear an empty container and then both append --
// rendering the page twice. Chaining guarantees one finishes before the next clears.
let pending = Promise.resolve();

function route() {
  pending = pending.then(doRoute).catch(e => console.error('route failed', e));
}

async function doRoute() {
  const hash = location.hash.replace(/^#/, '') || '/';
  const app = document.getElementById('app');

  for (const r of routes) {
    const m = hash.match(r.re);
    if (!m) continue;
    clear(app);
    setActions();
    try {
      await r.view(app, ...m.slice(1).map(decodeURIComponent));
    } catch (e) {
      console.error(e);
      clear(app);
      app.append(el('div', { class: 'page narrow' },
        el('h1', {}, 'Something broke'),
        el('p', { class: 'sub' }, String(e.message || e)),
        el('a', { class: 'btn', href: '#/' }, 'Back to library')));
    }
    return;
  }

  location.hash = '#/';
}

document.getElementById('streak-slot').append(streakChip());
document.getElementById('palette-slot').append(palettePicker());
document.getElementById('help-slot').append(helpButton());

window.addEventListener('hashchange', route);
// A module script is deferred, so it always runs after the document is parsed. One call, here --
// adding a DOMContentLoaded listener as well would route twice on load.
route();
