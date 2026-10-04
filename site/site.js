// The website's one script: the live ember, the working miniature, the theme swatches and the
// download buttons. The ember is the app's own code (ember/pet.js, copied in by build.sh), so it
// pokes, greets, idles and celebrates exactly as it does in LCForge.

import { makeEmber } from './ember/pet.js';
import { el } from './ember/dom.js';

// Filled in by the deploy (build.sh, from GITHUB_REPOSITORY). Left as is, the page is a local
// preview: the buttons stay put and say why.
const REPO = '__GITHUB_REPOSITORY__';
const PUBLISHED = !REPO.startsWith('__');

// ---------- the embers ----------

const hero = makeEmber({ size: 54, cosmetics: true });
hero.set({ heat: 84, state: 'bright', name: 'Ember' });
const perch = document.getElementById('demo-perch');
perch.append(hero.node);
hero.node.addEventListener('click', () => hero.poke());
hero.node.addEventListener('mouseenter', () => hero.greet());

const mini = makeEmber({ size: 40, cosmetics: true });
mini.set({ heat: 96, state: 'blazing', name: 'Ember' });
document.getElementById('mini-perch').append(mini.node);
mini.node.addEventListener('click', () => mini.poke());
mini.node.addEventListener('mouseenter', () => mini.greet());

// The fuel shop's wardrobe, worn on approval: preview() is the app's own try-on.
const WARDROBE = ['partyhat', 'shades', 'scarf', 'wirerims', 'hardhat', 'laurel', 'child', null];
let worn = -1;
document.getElementById('try-hat').addEventListener('click', e => {
  worn = (worn + 1) % WARDROBE.length;
  const id = WARDROBE[worn];
  mini.preview(id);
  mini.greet();
  e.currentTarget.textContent = id ? 'Try something else' : 'Try something on';
});

// ---------- the miniature ----------
//
// A real trace of twoSum([2,7,11,15], 9), one entry per line the debugger stops on. `line` is the
// line about to run; `vars` is what the variables hold at that moment.

const TRACE = [
  { line: 3, vars: { nums: '[2,7,11,15]', target: '9' } },
  { line: 4, vars: { seen: '{}' } },
  { line: 5, vars: { i: '0', seen: '{}' } },
  { line: 6, vars: { i: '0', j: 'null' } },
  { line: 7, vars: { i: '0', j: 'null' } },
  { line: 4, vars: { i: '0', seen: '{2=0}' } },
  { line: 5, vars: { i: '1', seen: '{2=0}' } },
  { line: 6, vars: { i: '1', j: '0' } },
];

const lines = [...document.querySelectorAll('#demo-code .ln')];
const out = document.getElementById('demo-out');
let step = -1;
let prev = {};
let paid = false;

function show(...nodes) { out.replaceChildren(...nodes); }

function highlight(line) {
  lines.forEach((l, i) => l.classList.toggle('cur', i + 1 === line));
}

function stepOnce() {
  step++;
  if (step >= TRACE.length) {
    highlight(0);
    show(el('span', { class: 'verdict ok' }, 'returned [0,1]'), el('span', {}, '— matches the expected output.'));
    hero.react('pass');
    step = -1;
    prev = {};
    return;
  }
  const s = TRACE[step];
  highlight(s.line);
  show(...Object.entries(s.vars).map(([k, v]) =>
    el('span', { class: `v${prev[k] !== undefined && prev[k] !== v ? ' changed' : ''}` }, el('i', {}, k), v)));
  prev = { ...prev, ...s.vars };
}

function resetStepping() { step = -1; prev = {}; highlight(0); }

document.getElementById('demo-step').addEventListener('click', stepOnce);

document.getElementById('demo-run').addEventListener('click', () => {
  resetStepping();
  show(el('span', { class: 'verdict ok' }, 'Case 1 passed'), el('span', {}, 'output [0,1] · 1 ms'));
  hero.react('pass');
});

document.getElementById('demo-submit').addEventListener('click', () => {
  resetStepping();
  const head = el('span', { class: 'verdict ok' }, 'Accepted');
  if (!paid) {
    // One payment per problem, ever -- the app's rule. The first Submit pays (Easy is 12, ×1.5 for
    // getting it right first time); every Submit after that is just a pass.
    paid = true;
    show(head, el('span', {}, '3 / 3 testcases · 2 ms'), el('span', { class: 'paid' }, '+⚡18'));
    celebrate();
  } else {
    show(head, el('span', {}, '3 / 3 testcases · 2 ms'), el('span', { class: 'muted' }, 'already paid for this one'));
    hero.react('pass');
  }
});

/** The app's Accepted burst: the ember jumps and throws sparks up and across the code. */
function celebrate() {
  hero.celebrate();
  hero.reward(18);
  const burst = el('div', { class: 'burst', 'aria-hidden': 'true' });
  for (let i = 0; i < 22; i++) {
    const angle = (-178 + Math.random() * 118) * Math.PI / 180;   // left, through up, to up-right
    const dist = 60 + Math.random() * 170;
    const bit = el('i', { class: `bit b${i % 4}` });
    bit.style.setProperty('--dx', `${(Math.cos(angle) * dist).toFixed(1)}px`);
    bit.style.setProperty('--dy', `${(Math.sin(angle) * dist).toFixed(1)}px`);
    bit.style.setProperty('--spin', `${Math.round(Math.random() * 720 - 360)}deg`);
    bit.style.setProperty('--wait', `${Math.round(Math.random() * 140)}ms`);
    burst.append(bit);
  }
  perch.append(burst);
  setTimeout(() => burst.remove(), 1700);
}

// ---------- themes ----------

const THEMES = [
  ['forge', 'Forge'], ['midnight', 'Midnight'], ['dracula', 'Dracula'], ['tokyo-night', 'Tokyo Night'],
  ['catppuccin-mocha', 'Catppuccin Mocha'], ['gruvbox', 'Gruvbox'], ['nord', 'Nord'],
  ['paper', 'Paper'], ['solarized-light', 'Solarized Light'], ['github-light', 'GitHub Light'],
];
const LIGHT = ['paper', 'github-light', 'solarized-light', 'catppuccin-latte', 'rose-pine-dawn'];
const KEY = 'lcforge.site.palette';
const systemLight = matchMedia('(prefers-color-scheme: light)');

function apply(id) {
  document.documentElement.dataset.palette = id;
  document.documentElement.style.colorScheme = LIGHT.includes(id) ? 'light' : 'dark';
  for (const b of swatches.querySelectorAll('.sw[data-id]')) b.setAttribute('aria-pressed', String(b.dataset.id === id));
}

function chosen() {
  try { return localStorage.getItem(KEY); } catch { return null; }
}

const swatches = document.getElementById('swatches');
for (const [id, name] of THEMES) {
  swatches.append(el('button', {
    type: 'button', class: 'sw', 'data-id': id, title: name, 'aria-label': name, 'aria-pressed': 'false',
    onClick: () => { try { localStorage.setItem(KEY, id); } catch { /* this visit only, then */ } apply(id); },
  }, el('i', { 'data-palette': id })));
}
swatches.append(el('button', {
  type: 'button', class: 'sw sw-system', title: 'Light or dark, as your system is set',
  onClick: () => { try { localStorage.removeItem(KEY); } catch { /* nothing stored */ } apply(systemLight.matches ? 'paper' : 'forge'); },
}, 'Match system'));
apply(document.documentElement.dataset.palette || 'forge');
systemLight.addEventListener('change', () => { if (!chosen()) apply(systemLight.matches ? 'paper' : 'forge'); });

// ---------- downloads ----------

const MATCH = {
  'windows-x64.msi':          n => n.endsWith('-windows-x64.msi'),
  'windows-x64-portable.zip': n => n.endsWith('-windows-x64-portable.zip'),
  'macos-arm64.dmg':          n => n.endsWith('-macos-arm64.dmg'),
  'macos-x64.dmg':            n => n.endsWith('-macos-x64.dmg'),
  'linux-x64.deb':            n => n.endsWith('-linux-x64.deb'),
  'linux-x64.tar.gz':         n => n.endsWith('-linux-x64.tar.gz'),
  'chrome-extension':         n => /^LCForge-chrome-extension-.+\.zip$/.test(n),
  'jar':                      n => /^LCForge-[\d.]+\.jar$/.test(n),
};
const OS_NAME = { windows: 'Windows', mac: 'macOS', linux: 'Linux' };

/** Best guess at the visitor's computer. Phones and tablets get no guess: there is nothing to install on them. */
function detectOS() {
  const ua = navigator.userAgent.toLowerCase();
  const p = (navigator.userAgentData?.platform || navigator.platform || '').toLowerCase();
  if (/android|iphone|ipad|ipod/.test(ua)) return null;
  if (p.includes('win') || ua.includes('windows')) return 'windows';
  if (p.includes('mac') || ua.includes('mac os')) return navigator.maxTouchPoints > 1 ? null : 'mac';  // an iPad says "Mac"
  if (p.includes('linux') || ua.includes('linux')) return 'linux';
  return null;
}

/** Apple silicon or Intel. Only Chromium will say; anywhere else, assume the Macs sold since 2020. */
async function macIsIntel() {
  try {
    const v = await navigator.userAgentData?.getHighEntropyValues(['architecture']);
    return v?.architecture === 'x86';
  } catch { return false; }
}

async function latestRelease() {
  const key = 'lcforge.site.release';
  try {
    const cached = JSON.parse(sessionStorage.getItem(key) || 'null');
    if (cached && Date.now() - cached.at < 10 * 60 * 1000) return cached.release;
  } catch { /* no cache */ }
  const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, {
    headers: { Accept: 'application/vnd.github+json' },
  });
  if (res.status === 404) return null;                 // nothing published yet
  if (!res.ok) throw new Error(`GitHub said ${res.status}`);
  const r = await res.json();
  const release = {
    tag: r.tag_name, url: r.html_url, date: r.published_at,
    assets: (r.assets || []).map(a => ({ name: a.name, url: a.browser_download_url, size: a.size })),
  };
  try { sessionStorage.setItem(key, JSON.stringify({ at: Date.now(), release })); } catch { /* fine */ }
  return release;
}

const mb = bytes => `${Math.max(1, Math.round(bytes / 1048576))} MB`;

async function downloads() {
  const os = detectOS();
  if (os) document.querySelector(`.dl[data-os="${os}"]`)?.classList.add('mine');
  const status = document.getElementById('dl-status');

  for (const a of document.querySelectorAll('[data-repo-link]')) {
    if (PUBLISHED) a.href = `https://github.com/${REPO}`;
    else a.removeAttribute('href');
  }
  if (!PUBLISHED) {
    status.textContent = 'Downloads appear here once LCForge is published on GitHub.';
    return;
  }

  const releases = `https://github.com/${REPO}/releases`;
  let release = null;
  try {
    release = await latestRelease();
  } catch {
    status.textContent = 'Could not ask GitHub for the newest version just now — every build is on the releases page.';
  }
  if (!release) {
    for (const a of document.querySelectorAll('[data-asset]')) a.href = releases;
    if (!status.textContent.startsWith('Could not')) status.textContent = 'The first release is on its way — the releases page will have it.';
    return;
  }

  const version = release.tag.replace(/^v/, '');
  const when = new Date(release.date).toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' });
  status.textContent = `Version ${version}, released ${when}. Free, for Windows, macOS and Linux.`;

  const found = {};
  for (const a of document.querySelectorAll('[data-asset]')) {
    const asset = release.assets.find(x => MATCH[a.dataset.asset]?.(x.name));
    found[a.dataset.asset] = asset;
    a.href = asset ? asset.url : release.url;
    if (asset && a.classList.contains('btn')) a.append(' ', el('span', { class: 'size' }, `· ${mb(asset.size)}`));
  }

  // The big button at the top goes straight to the file for this computer.
  if (!os) return;
  const pick = os === 'windows' ? 'windows-x64.msi'
             : os === 'linux' ? 'linux-x64.deb'
             : (await macIsIntel()) ? 'macos-x64.dmg' : 'macos-arm64.dmg';
  const asset = found[pick];
  if (!asset) return;
  const cta = document.getElementById('cta-download');
  cta.href = asset.url;
  cta.textContent = `Download for ${OS_NAME[os]}`;
  document.getElementById('cta-note').textContent =
    `Version ${version} · ${mb(asset.size)} · Java included · other systems below`;
}

downloads();
