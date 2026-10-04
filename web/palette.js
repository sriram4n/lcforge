// Interface colour schemes.
//
// The colours themselves live in styles.css, one block per palette. This file only decides which
// block is active, and then reads the resulting values back out to build a matching Monaco theme —
// so the editor and the chrome around it can never drift apart, and adding a palette stays a
// CSS-only job plus one line in the list below.
//
// What you choose is either one palette, or "match system": then the OS decides between two
// favourites, one dark and one light, and LCForge follows it live as it switches at sunset.

const KEY = 'lcforge.palette';                     // a palette id, or 'system'
const FAV = { dark: 'lcforge.palette.dark', light: 'lcforge.palette.light' };
const DEFAULT = { dark: 'forge', light: 'paper' };

// The boot script in index.html repeats a few lines of this resolution, because it has to run
// before the first paint and this module loads too late for that. Keep the two in step.

export const PALETTES = [
  { id: 'forge',            tone: 'dark',  name: 'Forge',            hint: 'amber on near-black — the original' },
  { id: 'midnight',         tone: 'dark',  name: 'Midnight',         hint: 'cool indigo, for late nights' },
  { id: 'oled',             tone: 'dark',  name: 'OLED',             hint: 'true black, zero backlight' },
  { id: 'github',           tone: 'dark',  name: 'GitHub Dark',      hint: 'the dark you already read code in' },
  { id: 'dracula',          tone: 'dark',  name: 'Dracula',          hint: 'the purple one' },
  { id: 'tokyo-night',      tone: 'dark',  name: 'Tokyo Night',      hint: 'neon city after the rain' },
  { id: 'catppuccin-mocha', tone: 'dark',  name: 'Catppuccin Mocha', hint: 'soft pastels on a warm dark' },
  { id: 'one-dark',         tone: 'dark',  name: 'One Dark',         hint: 'Atom’s, and half of VS Code’s' },
  { id: 'monokai',          tone: 'dark',  name: 'Monokai',          hint: 'the one Sublime made famous' },
  { id: 'nord',             tone: 'dark',  name: 'Nord',             hint: 'muted arctic, low contrast' },
  { id: 'gruvbox',          tone: 'dark',  name: 'Gruvbox',          hint: 'warm retro' },
  { id: 'rose-pine',        tone: 'dark',  name: 'Rosé Pine',        hint: 'muted, dusky, quiet' },
  { id: 'solarized-dark',   tone: 'dark',  name: 'Solarized Dark',   hint: 'sixteen colours, chosen by maths' },

  { id: 'paper',            tone: 'light', name: 'Paper',            hint: 'warm daylight' },
  { id: 'github-light',     tone: 'light', name: 'GitHub Light',     hint: 'the daytime half of the GitHub pair' },
  { id: 'catppuccin-latte', tone: 'light', name: 'Catppuccin Latte', hint: 'the pastels, with the lights on' },
  { id: 'solarized-light',  tone: 'light', name: 'Solarized Light',  hint: 'the same sixteen, by day' },
  { id: 'rose-pine-dawn',   tone: 'light', name: 'Rosé Pine Dawn',   hint: 'the quiet one, at sunrise' },
];

const byId = id => PALETTES.find(p => p.id === id);

// localStorage throws in some privacy modes, and a colour choice is not worth breaking the page over.
function stored(key) { try { return localStorage.getItem(key); } catch { return null; } }
function store(key, value) { try { localStorage.setItem(key, value); } catch { /* won't stick */ } }

const media = typeof matchMedia === 'function' ? matchMedia('(prefers-color-scheme: light)') : null;
const systemTone = () => (media?.matches ? 'light' : 'dark');

/** What you chose: a palette id, or 'system'. Nothing chosen yet means follow the OS. */
export function choice() {
  const c = stored(KEY);
  return c === 'system' || byId(c) ? c : 'system';
}

/** Your favourite of one tone: what "match system" shows when the OS is in that mode. */
export function favourite(tone) {
  const id = stored(FAV[tone]);
  return byId(id)?.tone === tone ? id : DEFAULT[tone];
}

/** The palette that should be on screen right now, given the choice and the OS. */
export function resolved() {
  const c = choice();
  return c === 'system' ? favourite(systemTone()) : c;
}

export function currentPalette() {
  return document.documentElement.dataset.palette || resolved();
}

/** Paint a palette without remembering it — what hovering an option does. */
export function showPalette(id) {
  const p = byId(id) || byId('forge');
  const root = document.documentElement;
  root.dataset.palette = p.id;
  // Native scrollbars, form controls and the autofill tint follow this, so a light palette does
  // not come with dark scrollbars bolted to its edges.
  root.style.colorScheme = p.tone;
  syncEditorTheme();
  return p.id;
}

/**
 * Choose a palette.
 *
 * It always becomes the favourite for its own tone. Outside "match system" it is also the answer
 * outright; inside it, it is simply what you will get the next time the OS is in that mode — so
 * picking a light palette at midnight changes the daytime slot and leaves your screen alone.
 */
export function applyPalette(id) {
  const p = byId(id);
  if (!p) return resolved();
  store(FAV[p.tone], p.id);
  if (choice() !== 'system') store(KEY, p.id);
  return showPalette(resolved());
}

/**
 * Turn "match system" on or off.
 *
 * On: what you were using becomes the favourite of its own tone, and then the OS decides — so the
 * screen changes only if the OS disagrees (Paper at midnight goes dark). Off: whatever is showing
 * becomes your plain choice, so turning it off never changes the screen at all.
 */
export function setSystem(on) {
  const now = byId(resolved());
  if (on) {
    if (now) store(FAV[now.tone], now.id);
    store(KEY, 'system');
  } else {
    store(KEY, now ? now.id : 'forge');
  }
  return showPalette(resolved());
}

const listeners = new Set();   // open pickers, re-marked when the OS flips under them

// Live: the OS flipping at sunset repaints the app, editor included, without a reload.
function follow() {
  if (choice() === 'system' && document.documentElement.dataset.palette !== resolved()) {
    showPalette(resolved());
  }
  for (const fn of listeners) fn();
}
if (media?.addEventListener) media.addEventListener('change', follow);
else media?.addListener?.(follow);          // Safari before 14 only knows the old name
// And again whenever you come back to the tab. The change event is the fast path, but not every
// environment delivers it (embedded browsers and some Linux desktops update the media query without
// firing it), and a tab left in the background over sunset should not come back in the wrong one.
document.addEventListener('visibilitychange', () => { if (!document.hidden) follow(); });

// The boot script set the palette before first paint; this settles what it could not (the
// colour-scheme hint for scrollbars and form controls) as soon as the module loads.
showPalette(resolved());

/** Read the palette back out of the cascade. */
function v(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

/**
 * Rebuild the Monaco theme from the active palette.
 *
 * Monaco cannot read CSS variables — a theme is a flat object of literal colours — so it has to be
 * regenerated on every switch. Defining over the same name updates it in place.
 */
export function syncEditorTheme() {
  const monaco = window.monaco;
  if (!monaco) return;   // called before the editor loaded; loadMonaco calls us again after

  monaco.editor.defineTheme('lcforge', {
    base: v('--monaco-base') === 'vs' ? 'vs' : 'vs-dark',
    inherit: true,
    rules: [
      { token: 'keyword',    foreground: hex(v('--syn-key')) },
      { token: 'string',     foreground: hex(v('--syn-str')) },
      { token: 'number',     foreground: hex(v('--syn-num')) },
      { token: 'comment',    foreground: hex(v('--syn-com')) },
      { token: 'type',       foreground: hex(v('--syn-type')) },
      { token: 'identifier', foreground: hex(v('--fg')) },
      { token: 'delimiter',  foreground: hex(v('--fg-dim')) },
      { token: 'annotation', foreground: hex(v('--syn-fn')) },
    ],
    colors: {
      'editor.background':                 v('--bg'),
      'editor.foreground':                 v('--fg'),
      'editorGutter.background':           v('--bg'),
      'editorLineNumber.foreground':       v('--fg-faint'),
      'editorLineNumber.activeForeground': v('--accent'),
      'editor.lineHighlightBackground':    v('--bg-2'),
      'editorIndentGuide.background1':     v('--line-soft'),
      'editorCursor.foreground':           v('--accent'),
      'editorWidget.background':           v('--bg-2'),
      'editorWidget.border':               v('--line'),
      'editorSuggestWidget.background':    v('--bg-2'),
      'editorSuggestWidget.border':        v('--line'),
      'editorSuggestWidget.selectedBackground': v('--bg-3'),
      'editorSuggestWidget.highlightForeground': v('--accent'),
      'editor.selectionBackground':        v('--accent-dim'),
      'scrollbarSlider.background':        v('--line'),
    },
  });
  monaco.editor.setTheme('lcforge');
}

/** Monaco wants token colours as bare RRGGBB, with no leading `#`. */
function hex(value) {
  return value.replace('#', '').slice(0, 6);
}

// ---------- the picker ----------

/**
 * The topbar control. Returns a node to mount once; it lives outside `#topbar-actions`, which each
 * view clears on navigation.
 *
 * Every option carries a miniature drawn by the stylesheet in that palette's own colours, straight
 * from the cascade. Hovering an option paints the whole interface in it and moving away puts back
 * what you had — a thumbnail can only ever hint at a palette; the question you are actually asking
 * is "what does my screen look like in this", so answer that one.
 */
export function palettePicker() {
  const btn = el('button', {
    class: 'palette-btn',
    title: 'Colour palette',
    'aria-label': 'Colour palette',
    'aria-haspopup': 'menu',
  }, mini());

  const sysSwitch = el('button', { class: 'switch', role: 'switch', 'aria-checked': 'false' },
    el('i', { class: 'knob' }));
  const sysNote = el('span', { class: 'sys-note' });
  const sysRow = el('div', { class: 'palette-system' },
    el('label', { class: 'sys-label' }, sysSwitch,
      el('span', {}, el('b', {}, 'Match system'), sysNote)));

  const menu = el('div', { class: 'palette-menu', role: 'menu' }, sysRow);
  menu.hidden = true;

  const wrap = el('div', { class: 'palette' }, btn, menu);

  btn.addEventListener('click', e => {
    e.stopPropagation();
    menu.hidden ? open() : close();
  });

  function open() {
    mark();
    menu.hidden = false;
    btn.setAttribute('aria-expanded', 'true');
    document.addEventListener('click', onAway);
    document.addEventListener('keydown', onEsc);
  }
  function close() {
    showPalette(resolved());   // an unchosen hover must not survive the menu closing
    menu.hidden = true;
    btn.setAttribute('aria-expanded', 'false');
    document.removeEventListener('click', onAway);
    document.removeEventListener('keydown', onEsc);
  }
  const onAway = e => { if (!wrap.contains(e.target)) close(); };
  const onEsc = e => { if (e.key === 'Escape') close(); };

  sysSwitch.addEventListener('click', e => {
    e.preventDefault();             // it sits in a <label>; one toggle per click, not two
    setSystem(choice() !== 'system');
    mark();
  });

  // One handler on the menu rather than eighteen on the options: leaving option A for option B
  // fires A's leave after B's enter, and a per-option restore would undo the preview you just
  // asked for.
  menu.addEventListener('mouseleave', () => { if (!menu.hidden) showPalette(resolved()); });

  // The options are built once and only re-marked afterwards. Rebuilding them on every choice
  // looked equivalent but was not: replacing the children detaches the button you just clicked, so
  // by the time that click reaches the document the `wrap.contains(target)` test says it happened
  // outside the picker and the menu shuts on you mid-browse.
  const options = [];
  for (const tone of ['dark', 'light']) {
    menu.append(el('h4', { class: 'palette-group' }, tone === 'dark' ? 'Dark' : 'Light'));
    const grid = el('div', { class: 'palette-grid' });
    for (const p of PALETTES.filter(q => q.tone === tone)) {
      const opt = el('button', {
        class: 'palette-opt',
        role: 'menuitemradio',
        title: p.hint,
        'aria-label': `${p.name} — ${p.hint}`,
      },
        mini(p.id),
        el('span', { class: 'who' }, el('b', {}, p.name), el('span', { class: 'mark' })));

      // Keyboard users get the same preview: focus is their hover.
      opt.addEventListener('mouseenter', () => showPalette(p.id));
      opt.addEventListener('focus',      () => showPalette(p.id));
      opt.addEventListener('click', () => { applyPalette(p.id); mark(); });
      grid.append(opt);
      options.push({ p, node: opt });
    }
    menu.append(grid);
  }

  /**
   * Re-mark every option. Outside system mode there is one tick. Inside it there are two marks, a
   * moon and a sun, because there are two answers — and the note under the switch names both, so
   * a click on the half that is not showing still visibly does something.
   */
  function mark() {
    const sys = choice() === 'system';
    const on = resolved();
    const fav = { dark: favourite('dark'), light: favourite('light') };
    sysSwitch.setAttribute('aria-checked', String(sys));
    sysRow.classList.toggle('on', sys);
    sysNote.textContent = sys
      ? `☾ ${byId(fav.dark).name} · ☀ ${byId(fav.light).name}`
      : 'follow your OS between a dark and a light favourite';

    for (const { p, node } of options) {
      const chosen = sys ? fav[p.tone] === p.id : p.id === on;
      node.classList.toggle('on', p.id === on);
      node.classList.toggle('fav', chosen);
      node.setAttribute('aria-checked', String(chosen));
      node.querySelector('.mark').textContent = !chosen ? '' : sys ? (p.tone === 'dark' ? '☾' : '☀') : '✓';
    }
  }
  mark();
  listeners.add(() => { if (!menu.hidden) mark(); });

  return wrap;
}

/**
 * A miniature of the interface: title bar, accent, three lines of code.
 *
 * It holds no colours of its own — `data-palette` re-declares the palette inside it and the
 * stylesheet does the rest, so the thumbnail is the palette rather than an impression of it.
 *
 * The attribute goes here and not on the surrounding option: the whole point is that it rewrites
 * every colour variable underneath it, and an option carrying it would print its own label in the
 * advertised palette's foreground — Paper's near-black text on the menu's dark background, which is
 * to say invisibly. Given no id it inherits, which is what the topbar button wants: whatever is on.
 */
function mini(id) {
  return el('span', { class: 'mini', 'data-palette': id || null, 'aria-hidden': 'true' },
    el('i', { class: 'bar' }),
    el('i', { class: 'l1' }),
    el('i', { class: 'l2' }),
    el('i', { class: 'l3' }));
}

// A local `el`, so this module can be imported by app.js without importing back from it.
function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, val] of Object.entries(attrs)) {
    if (val === null || val === undefined || val === false) continue;
    if (k === 'class') node.className = val;
    else node.setAttribute(k, val === true ? '' : val);
  }
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue;
    node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return node;
}
