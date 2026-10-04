// The ember.
//
// A flame on a coal, drawn once and then driven entirely by two CSS custom properties: --heat (0..1)
// and --mood. Brightness is the whole emotional range, which is the reason a fire was the right
// creature to pick — a sad face has to be *drawn* sad, and drawn sad five different ways, whereas a
// fire that is doing badly is just a fire with less light in it. That also means it needs no
// per-palette artwork: every colour here is mixed from --accent and --fg-faint, so it is amber on
// Forge, blue-white on Midnight and a proper sooty orange on Paper without a second thought.
//
// Nothing below names a colour. The gradients declare *stops* and the stylesheet paints them, so a
// palette switch still repaints the creature mid-animation, which is the whole reason the artwork
// was never allowed to hold a hex code in the first place.

import { el } from './dom.js';
import { svgEl } from './svg.js';

const FLAME = 'M 12 2 C 15.5 7 19 9.2 19 13.2 A 7 7 0 0 1 5 13.2 C 5 9.6 7.5 8.4 9 5.6 ' +
              'C 9.8 8 10.6 8.8 11.4 9.4 C 11.9 7.6 11.6 4.8 12 2 Z';
const INNER = 'M 12 9 C 13.6 11.4 15 12.6 15 14.6 A 3 3 0 0 1 9 14.6 C 9 12.8 10.8 11.8 12 9 Z';

// Every gradient and filter in an SVG is addressed by id, and there are two or three embers on a
// page at once — the hearth's, the perch's, and whatever a view mounts. Sharing ids happens to look
// right until the *first* ember is unmounted, at which point every reference in the others points at
// nothing and the flame paints black. One counter, one suffix per creature, no shared defs.
let seq = 0;

/**
 * Build an ember.
 *
 * `size` is the square it draws into. Returns a node plus the handful of things the rest of the app
 * wants to do to it: set its state, and poke it when something happens.
 */
export function makeEmber({ size = 28, cosmetics = true } = {}) {
  const uid = `emb${++seq}`;

  const svg = svgEl('svg', {
    class: 'ember', width: size, height: size, viewBox: '0 0 24 26',
    role: 'img', 'aria-label': 'your ember',
  });

  // ---- paint servers ----
  //
  // The flat fills this replaced were the whole of what made it read as a clip-art flame: a disc of
  // 16%-opacity amber has a hard edge, and light does not have edges. A radial falloff for the
  // light, a vertical ramp down the body so the tip is cooler than the base, and a hot core that
  // actually looks like the hottest part of a fire rather than a lighter-coloured sticker on it.
  svg.append(svgEl('defs', {},
    svgEl('radialGradient', { id: `halo-${uid}`, cx: '.5', cy: '.52', r: '.5' },
      svgEl('stop', { class: 'g-halo-0', offset: '0' }),
      svgEl('stop', { class: 'g-halo-1', offset: '.45' }),
      svgEl('stop', { class: 'g-halo-2', offset: '1' })),

    svgEl('linearGradient', { id: `body-${uid}`, x1: '0', y1: '0', x2: '.18', y2: '1' },
      svgEl('stop', { class: 'g-body-0', offset: '0' }),
      svgEl('stop', { class: 'g-body-1', offset: '.4' }),
      svgEl('stop', { class: 'g-body-2', offset: '1' })),

    svgEl('radialGradient', { id: `core-${uid}`, cx: '.5', cy: '.74', r: '.68' },
      svgEl('stop', { class: 'g-core-0', offset: '0' }),
      svgEl('stop', { class: 'g-core-1', offset: '.6' }),
      svgEl('stop', { class: 'g-core-2', offset: '1' })),

    svgEl('linearGradient', { id: `coal-${uid}`, x1: '0', y1: '0', x2: '0', y2: '1' },
      svgEl('stop', { class: 'g-coal-0', offset: '0' }),
      svgEl('stop', { class: 'g-coal-1', offset: '1' })),

    // The soft edge. A flame has no outline, and the single cheapest thing that sells "this is
    // burning" rather than "this is a shape" is a blurred copy of the silhouette underneath the
    // crisp one. The filter region has to be widened by hand or the blur is clipped at the bbox and
    // you get a soft flame in a hard rectangle.
    svgEl('filter', { id: `soft-${uid}`, x: '-45%', y: '-45%', width: '190%', height: '190%' },
      svgEl('feGaussianBlur', { stdDeviation: '.85' }))));

  // The coal it sits on. Always there, so a dead-cold ember is still visibly *something*.
  svg.append(svgEl('ellipse', { class: 'coal', cx: 12, cy: 22.5, rx: 7, ry: 2.4 }));
  // Where the fire actually meets its fuel. Without it the flame looks like it is hovering.
  svg.append(svgEl('ellipse', { class: 'coal-hot', cx: 12, cy: 21.3, rx: 4.6, ry: 1.5 }));
  svg.append(svgEl('ellipse', { class: 'glow', cx: 12, cy: 14, rx: 10, ry: 11 }));

  const body = svgEl('g', { class: 'flame-group' });
  body.append(svgEl('path', { class: 'flame-halo', d: FLAME }));
  body.append(svgEl('path', { class: 'flame', d: FLAME }));
  body.append(svgEl('path', { class: 'flame-inner', d: INNER }));

  // A mouth, kept at zero opacity and only opened by the reactions that need one. It is one element
  // and it is invisible at rest, so the "don't draw five faces" rule survives intact — this is not a
  // second expression to keep in sync, it is a prop that comes out for a gasp and goes away again.
  body.append(svgEl('ellipse', { class: 'mouth', cx: 12, cy: 17.3, rx: 1.05, ry: 1.25 }));

  // Eyes. Two dots is enough — anything more and you have to animate it well. The glint is the
  // exception worth making: a dot with a highlight is looking at you, a dot without one is a hole.
  const eyes = svgEl('g', { class: 'eyes' });
  for (const x of [10, 14]) {
    eyes.append(svgEl('circle', { class: 'eye', cx: x, cy: 14.5, r: 1.15 }));
    eyes.append(svgEl('circle', { class: 'glint', cx: x - 0.36, cy: 14.1, r: 0.36 }));
  }
  body.append(eyes);
  svg.append(body);

  // Smoke, for when it has gone out. Hidden by CSS at any other state — and borrowed by the sneeze.
  const smoke = svgEl('g', { class: 'smoke' });
  for (let i = 0; i < 3; i++) {
    smoke.append(svgEl('circle', { class: `puff p${i}`, cx: 12, cy: 12, r: 1.4 - i * 0.3 }));
  }
  svg.append(smoke);

  // Sparks thrown on a flare. Parked off-screen until something good happens.
  const sparks = svgEl('g', { class: 'sparks' });
  for (let i = 0; i < 6; i++) sparks.append(svgEl('circle', { class: `spark s${i}`, cx: 12, cy: 12, r: 0.9 }));
  svg.append(sparks);

  // Crackles: the shower of hot chips a fire throws when something lands on it. Squares rather than
  // circles, and thrown sideways rather than straight up, so a feed reads differently at a glance
  // from the vertical plume of a passing test.
  const crackles = svgEl('g', { class: 'crackles' });
  for (let i = 0; i < 7; i++) {
    crackles.append(svgEl('rect', { class: `crack c${i}`, x: 11.3, y: 13, width: 1.4, height: 1.4, rx: 0.35 }));
  }
  svg.append(crackles);

  const hats = cosmetics ? svgEl('g', { class: 'hats' }) : null;
  if (hats) svg.append(hats);

  const node = el('span', { class: 'ember-wrap' }, svg);

  // The gradients are handed to the stylesheet as custom properties rather than written onto the
  // elements as `fill="url(...)"`. A presentation attribute loses to *any* stylesheet rule, so a
  // fill attribute here would be quietly overridden by `.ember .flame { fill: ... }` and the whole
  // gradient would never paint. As properties they also stay overridable — the dependent ember in
  // the `child` cosmetic gets the flat fire colour by one ordinary CSS rule.
  for (const [prop, id] of [['body', 'body'], ['core', 'core'], ['halo', 'halo'],
                            ['coal', 'coal'], ['soft', 'soft']]) {
    node.style.setProperty(`--paint-${prop}`, `url(#${id}-${uid})`);
  }

  let actTimer = null;
  let idleTimer = null;
  let acting = false;
  let lastAct = '';
  let lastGreet = 0;
  let mashes = 0;         // consecutive quick pokes — mash it and it gets dizzy
  let lastPoke = 0;
  let worn = [];          // what the server says it is wearing
  let tryOn = null;       // { id, ghost } — something being held up against it, not yet owned or worn

  /** Paint a pet record. */
  function set(pet) {
    if (!pet) return;
    const heat = Math.max(0, Math.min(100, Number(pet.heat) || 0));
    const state = pet.state || 'cold';

    node.dataset.state = state;
    // The single number everything downstream is mixed from. Squashed a little at the bottom so a
    // guttering ember still reads as alight rather than as a rendering failure.
    node.style.setProperty('--heat', (0.12 + 0.88 * (heat / 100)).toFixed(3));

    const fx = new Set((pet.effects || []).map(e => e.id));
    node.classList.toggle('fx-green', fx.has('green'));
    node.classList.toggle('fx-hyper', fx.has('hyper'));
    node.classList.toggle('fx-oil', fx.has('oil'));
    node.classList.toggle('fx-leak', fx.has('leak'));

    worn = pet.equipped || [];
    paintHats();

    svg.setAttribute('aria-label',
      `${pet.name || 'your ember'} — ${STATE_WORDS[state] || state}, ${Math.round(heat)}% heat`);
    node.title = `${pet.name || 'Ember'} · ${STATE_WORDS[state] || state}`;
  }

  /** Whatever it owns and is wearing, plus whatever is currently being held up against it. */
  function paintHats() {
    if (!hats) return;
    hats.replaceChildren();
    for (const id of worn) {
      if (id === tryOn?.id) continue;            // already on; the preview would draw it twice
      const drawn = COSMETIC_ART[id];
      if (drawn) hats.append(drawn());
    }
    const fitting = tryOn && COSMETIC_ART[tryOn.id];
    if (fitting) {
      const g = fitting();
      g.setAttribute('class', `${g.getAttribute('class')} try${tryOn.ghost ? ' ghost' : ''}`);
      hats.append(g);
    }
  }

  /**
   * Try something on without buying it.
   *
   * A hat you have not bought is a hat you cannot picture, and a wardrobe of names is a wardrobe you
   * never spend anything in. `ghost` dims it, so a thing you do not own still reads as a proposal
   * rather than as something you already have. Pass null to take it off again.
   */
  function preview(id, ghost = false) {
    const next = id ? { id, ghost } : null;
    if (next?.id === tryOn?.id && next?.ghost === tryOn?.ghost) return;
    tryOn = next;
    paintHats();
  }

  // ---------- the animation driver ----------

  /**
   * Run one animation, whatever else was running.
   *
   * The duration is pushed into CSS as `--act-dur` rather than written down in both places. A
   * keyframe list and a `setTimeout` that disagree is the classic way an animation ends up frozen on
   * its last frame, and there is no way to notice it except by staring at the thing.
   */
  function play(cls, ms) {
    node.classList.remove(...ALL_CLASSES);
    void node.offsetWidth;                // restart the animation rather than ignore a repeat
    node.style.setProperty('--act-dur', `${ms}ms`);
    node.classList.add(cls);
    acting = true;
    clearTimeout(actTimer);
    actTimer = setTimeout(() => {
      node.classList.remove(cls);
      acting = false;
    }, ms + 80);
  }

  /**
   * Weighted pick that will not hand back what it handed back last time.
   *
   * Real randomness repeats, and a repeat is exactly the case where a random reaction stops reading
   * as random — the second identical wobble in a row looks like the first one was the only one it
   * has. Excluding the previous pick is a small lie that makes it feel more random, not less.
   */
  function pick(pool) {
    const opts = pool.length > 1 ? pool.filter(p => p.id !== lastAct) : pool;
    let r = Math.random() * opts.reduce((s, p) => s + p.w, 0);
    const hit = opts.find(p => (r -= p.w) <= 0) || opts[opts.length - 1];
    lastAct = hit.id;
    return hit;
  }

  /**
   * You prodded it.
   *
   * Nine different answers, weighted so the big ones stay rare: a wobble is what usually happens, a
   * backflip is what happens sometimes, and the whole reason to poke it a second time is that you do
   * not know which you are going to get. Keep prodding and it gets dizzy, which is the only reaction
   * you cannot roll — you have to earn it by being annoying.
   */
  function poke() {
    const now = Date.now();
    mashes = now - lastPoke < 1400 ? mashes + 1 : 0;
    lastPoke = now;

    if (mashes >= 5) {
      mashes = 0;
      lastAct = 'dizzy';
      play('act-dizzy', 1200);
      return;
    }
    const act = pick(POKES);
    play(`act-${act.id}`, act.ms);
  }

  /**
   * You went near it.
   *
   * Much smaller than a poke and rate-limited hard, because a hover fires whenever the pointer
   * crosses the creature on its way somewhere else. A greeting on every crossing is a twitch.
   */
  function greet() {
    const now = Date.now();
    if (acting || now - lastGreet < 1100) return;
    lastGreet = now;
    const act = pick(GREETS);
    play(`act-${act.id}`, act.ms);
  }

  /**
   * It blinks and shifts on its own, on no schedule you could predict.
   *
   * This is the difference between a creature and an icon, and it costs one timer. The loop stops
   * itself the moment the node leaves the page — there is no dispose() on an ember and a forever
   * timer holding a detached DOM node is a leak that would outlive every route change.
   */
  function scheduleIdle() {
    idleTimer = setTimeout(() => {
      if (!node.isConnected) return;               // unmounted: stop for good, release the node
      if (!document.hidden && !acting && !node.matches(':hover')) {
        const act = pick(IDLES);
        play(`act-${act.id}`, act.ms);
      }
      scheduleIdle();
    }, 3800 + Math.random() * 6500);
  }
  scheduleIdle();

  /**
   * One-shot animations for things that just happened.
   *
   * This is most of what makes it feel like a companion rather than a gauge: the ember is watching
   * you work, and reacts before you have finished reading the toast.
   */
  function react(kind, strength = 0.5) {
    if (kind === 'poke') { poke(); return; }       // kept so older callers still land somewhere
    const cls = { pass: 'r-flare', compile: 'r-flinch', fail: 'r-dip',
                  hunt: 'r-crackle', feed: 'r-feed' }[kind];
    if (!cls) return;
    // How much it swells when it eats. A Kindling Splinter should not look like an Anthracite
    // Block — the animation is the receipt, so it has to be proportional to what you spent.
    node.style.setProperty('--gorge', Math.max(0.25, Math.min(1, strength)).toFixed(2));
    play(cls, 1300);
  }

  /**
   * A payment, floating up off the ember.
   *
   * Its own element rather than a class on the sprite, so two rewards in quick succession stack
   * instead of cancelling each other, and so a long number cannot resize the creature.
   */
  function reward(n) {
    const pop = el('span', { class: 'spark-pop' }, `+⚡${n}`);
    node.append(pop);
    setTimeout(() => pop.remove(), 1800);
  }

  /**
   * You solved something it had never seen you solve.
   *
   * Not in any pool, so it cannot be rolled by poking — the only way to see it is to earn it, which
   * is the whole reason it is worth seeing. The perch throws sparks across the editor around it.
   */
  function celebrate() {
    lastAct = 'cheer';
    play('act-cheer', 1150);
  }

  /** Lean toward the side of the editor your cursor is on. Subtle, and only while typing. */
  function lookAt(fraction) {
    node.style.setProperty('--lean', (Math.max(0, Math.min(1, fraction)) - 0.5).toFixed(2));
  }

  return { node, set, react, reward, lookAt, preview, poke, greet, celebrate };
}

// ---------- the reaction pools ----------
//
// `w` is a weight, not a probability: the pool normalises itself, so adding a tenth reaction does
// not require rebalancing the other nine. `ms` is the truth about how long the animation runs — the
// stylesheet reads it back out of --act-dur, so these numbers are the only place it is written down.

const POKES = [
  { id: 'wobble', ms: 560, w: 14 },   // the old faithful
  { id: 'squish', ms: 480, w: 12 },
  { id: 'hop',    ms: 620, w: 12 },
  { id: 'giggle', ms: 700, w: 10 },
  { id: 'shake',  ms: 440, w: 8 },
  { id: 'sneeze', ms: 780, w: 7 },
  { id: 'gasp',   ms: 720, w: 6 },
  { id: 'spin',   ms: 820, w: 4 },    // uncommon
  { id: 'flip',   ms: 960, w: 2 },    // rare enough to be worth waiting for
];

const GREETS = [
  { id: 'perk',  ms: 460, w: 4 },
  { id: 'blink', ms: 400, w: 4 },
  { id: 'sway',  ms: 560, w: 3 },
  { id: 'peek',  ms: 440, w: 2 },
];

const IDLES = [
  { id: 'blink', ms: 400, w: 9 },
  { id: 'sway',  ms: 560, w: 3 },
  { id: 'peek',  ms: 440, w: 2 },
];

/** Every class `play` might have left behind, so a new animation always starts from nothing. */
const ALL_CLASSES = [
  'r-flare', 'r-flinch', 'r-dip', 'r-crackle', 'r-feed',
  'act-dizzy', 'act-cheer',
  ...new Set([...POKES, ...GREETS, ...IDLES].map(a => `act-${a.id}`)),
];

export const STATE_WORDS = {
  blazing: 'blazing',
  bright: 'burning well',
  steady: 'steady',
  guttering: 'guttering',
  cold: 'cold, and not happy about it',
};

/** What the ember says about itself, given how it is doing. */
export function moodLine(pet) {
  const n = pet?.name || 'It';
  switch (pet?.state) {
    case 'blazing': return `${n} is roaring.`;
    case 'bright': return `${n} is burning well.`;
    case 'steady': return `${n} is fine, and would not say no.`;
    case 'guttering': return `${n} is getting low.`;
    default: return `${n} has gone out. One meal fixes this.`;
  }
}

// ---------- cosmetics ----------
//
// Each is a scrap of SVG positioned over the flame. They use palette tokens via CSS classes rather
// than literal colours, same as everything else, so a hat is still legible on Paper.

const COSMETIC_ART = {
  partyhat: () => svgEl('g', { class: 'cos partyhat' },
    svgEl('path', { class: 'cos-fill', d: 'M 12 -1.5 L 15.5 5 L 8.5 5 Z' }),
    svgEl('circle', { class: 'cos-dot', cx: 12, cy: -1.8, r: 1 })),

  hardhat: () => svgEl('g', { class: 'cos hardhat' },
    svgEl('path', { class: 'cos-fill', d: 'M 6.5 5 A 5.5 5.5 0 0 1 17.5 5 Z' }),
    svgEl('rect', { class: 'cos-fill', x: 5, y: 4.4, width: 14, height: 1.5, rx: 0.7 })),

  wirerims: () => svgEl('g', { class: 'cos wirerims' },
    svgEl('circle', { class: 'cos-line', cx: 10, cy: 14.5, r: 2.1 }),
    svgEl('circle', { class: 'cos-line', cx: 14, cy: 14.5, r: 2.1 }),
    svgEl('line', { class: 'cos-line', x1: 12.1, y1: 14.5, x2: 11.9, y2: 14.5 })),

  shades: () => svgEl('g', { class: 'cos shades' },
    svgEl('rect', { class: 'cos-dark', x: 7.4, y: 12.9, width: 3.6, height: 2.6, rx: 0.8 }),
    svgEl('rect', { class: 'cos-dark', x: 13, y: 12.9, width: 3.6, height: 2.6, rx: 0.8 }),
    svgEl('line', { class: 'cos-line', x1: 11, y1: 13.6, x2: 13, y2: 13.6 })),

  scarf: () => svgEl('g', { class: 'cos scarf' },
    svgEl('rect', { class: 'cos-fill', x: 7, y: 17.4, width: 10, height: 2.1, rx: 1 }),
    svgEl('path', { class: 'cos-fill', d: 'M 15.4 19 L 18 21.6 L 16.2 22.2 L 14.4 19.6 Z' })),

  keyboard: () => svgEl('g', { class: 'cos keyboard' },
    svgEl('rect', { class: 'cos-dark', x: 4, y: 20.2, width: 9, height: 3.2, rx: 0.8 }),
    svgEl('line', { class: 'cos-line', x1: 5.2, y1: 21.3, x2: 11.8, y2: 21.3 }),
    svgEl('line', { class: 'cos-line', x1: 5.2, y1: 22.4, x2: 11.8, y2: 22.4 })),

  labcoat: () => svgEl('g', { class: 'cos labcoat' },
    svgEl('path', { class: 'cos-fill', d: 'M 6.5 17 L 12 19 L 17.5 17 L 18.5 23 L 5.5 23 Z' })),

  laurel: () => svgEl('g', { class: 'cos laurel' },
    svgEl('path', { class: 'cos-line', d: 'M 6 9 C 4.5 5.5 6 2.5 8.5 1.5' }),
    svgEl('path', { class: 'cos-line', d: 'M 18 9 C 19.5 5.5 18 2.5 15.5 1.5' })),

  // A second, smaller ember. It is not a hat, it is a dependent, and it copies the parent's heat
  // because it is drawn inside the same element.
  child: () => svgEl('g', { class: 'cos child' },
    svgEl('path', { class: 'flame', d: FLAME, transform: 'translate(17.5 14) scale(0.42)' })),
};
