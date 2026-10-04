// The companion: the ember itself, sitting on the bottom edge of the code with nothing behind it.
//
// This used to be a status bar with a mascot glued to the left of it, which is a different thing
// entirely — a bar is furniture you stop seeing after a day, and it was charging the editor a strip
// of height for the privilege. Now the creature sits on the editor, in no box of its own, and every
// number about it is folded away until you go over and hover it. A figure you have to reach for is
// one you read because you were curious; a figure nailed to the frame is one you monitor while you
// are trying to think about a graph problem.

import { api, el } from './app.js';
import { makeEmber, moodLine } from './pet.js';
import { fuelShop } from './shop.js';

const watchers = new Set();

/** Tell every ember on the page that the numbers moved. */
export function emberChanged() {
  for (const fn of watchers) { try { fn(); } catch (e) { console.error(e); } }
}

/**
 * A perched ember for the corner of an editor.
 *
 * Mounts into any positioned element — it places itself against that element's bottom-right corner
 * and takes no space in the layout.
 */
export function emberPerch() {
  const ember = makeEmber({ size: 36 });
  const heatFill = el('i');
  const heatBar = el('div', { class: 'heat-bar' }, heatFill);
  const sparks = el('span', { class: 'sparks-count' },
    el('i', { class: 'bolt' }, '⚡'), el('b', {}, '0'));
  const mood = el('span', { class: 'mood' });

  // What it was on before the meal, so a big log can throw a bigger animation than a splinter.
  let heat = 0;

  const shop = fuelShop({
    onFed: (fuel, res) => {
      const now = Number(res?.pet?.heat);
      ember.react('feed', Number.isFinite(now) ? (now - heat) / 45 : 0.5);
      draw();
      emberChanged();
    },
    // The panel is hover-revealed, so an open menu has to hold it open: otherwise reading down the
    // fuel list past the bottom of the panel would shut the thing you are reading.
    onToggle: open => wrap.classList.toggle('menu-open', open),
  });

  const panel = el('div', { class: 'ember-panel' }, mood, heatBar, sparks, shop.node);

  const poke = el('button', {
    class: 'ember-poke', 'aria-expanded': 'false', 'aria-label': 'your ember',
  }, ember.node);

  const wrap = el('div', { class: 'ember-perch' }, panel, poke);

  // Hover is the discovery path; the click is the one that sticks, for touch and for anyone who
  // wants to read the fuel list without keeping a mouse perfectly still.
  poke.addEventListener('click', () => {
    const on = wrap.classList.toggle('pinned');
    poke.setAttribute('aria-expanded', String(on));
    if (!on) shop.close();
    // It reacts to the poke either way. Opening a panel is the useful half of the click; being
    // noticed is the half that makes it a creature rather than a button with a flame on it. Which
    // reaction you get is a weighted roll, so the tenth click is still worth making.
    ember.poke();
  });

  // Hovering the perch already opens the panel; this is the creature noticing you did it.
  poke.addEventListener('mouseenter', () => ember.greet());

  async function draw() {
    let data;
    try { data = await api('/api/pet', {}); } catch { return; }   // server down: leave it as it was
    const pet = data.pet || {};
    heat = Number(pet.heat) || 0;
    ember.set(pet);
    heatFill.style.width = `${Math.max(2, Math.round(heat))}%`;
    wrap.dataset.state = pet.state || 'cold';
    mood.textContent = moodLine(pet);
    sparks.querySelector('b').textContent = pet.sparks ?? 0;
    poke.title = `${pet.name || 'Ember'} · ${Math.round(heat)}% heat`;
  }

  /**
   * Accepted, and paid for the first time: the ember jumps, and the editor fills with sparks.
   *
   * The sparks are thrown from the perch rather than from inside the creature, because the creature
   * is itself spinning and hopping — sparks parented to it would spin with it, like glitter glued to
   * a ball. Up and to the left only: the perch sits in the bottom-right corner, so that is where the
   * code is, and a burst that half disappears off the edge of the window looks like a mistake.
   */
  function celebrate() {
    ember.celebrate();
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
    wrap.append(burst);
    setTimeout(() => burst.remove(), 1700);
  }

  watchers.add(draw);
  draw();

  return {
    node: wrap,
    react: (kind, strength) => ember.react(kind, strength),
    reward: n => ember.reward(n),
    celebrate,
    lookAt: f => ember.lookAt(f),
    refresh: draw,
    dispose: () => { watchers.delete(draw); shop.close(); },
  };
}
