// The hearth: the ember at a size you can actually see it, on the library page.
//
// The strip in the editor is a status line you glance at. This is where you look *at* it — name it,
// see how old it is, and spend what you have earned on things that do nothing whatsoever.

import { api, el, toast } from './app.js';
import { makeEmber, moodLine } from './pet.js';
import { emberChanged } from './companion.js';
import { fuelShop } from './shop.js';

export async function hearthCard() {
  let data;
  try { data = await api('/api/pet', {}); } catch { return null; }

  const ember = makeEmber({ size: 96 });
  const title = el('b', {});
  const sub = el('span', { class: 'hearth-sub' });
  const bar = el('i');
  const meter = el('div', { class: 'hearth-meter' }, bar);
  const balance = el('span', { class: 'sparks-count' },
    el('i', { class: 'bolt' }, '⚡'), el('b', {}, '0'));

  const wares = el('div', { class: 'wares' });
  // One handler on the row rather than one per chip, for the same reason the palette picker has
  // one: moving from chip A to chip B fires A's leave *after* B's enter, and a per-chip restore
  // would take off the hat you just asked to see.
  wares.addEventListener('mouseleave', () => ember.preview(null));
  wares.addEventListener('focusout', e => {
    if (!wares.contains(e.relatedTarget)) ember.preview(null);
  });
  // The ember is 96px here, so the meal animation is the whole point of watching it happen.
  const shop = fuelShop({
    onFed: (fuel, res) => {
      const now = Number(res?.pet?.heat);
      const was = Number(data.pet?.heat) || 0;
      ember.react('feed', Number.isFinite(now) ? (now - was) / 45 : 0.5);
      refresh();
    },
  });
  // A button rather than a div: it is the biggest the ember ever gets, so it is the one you will
  // actually reach out and prod, and a thing you can click has to be reachable from the keyboard.
  const petBtn = el('button', { class: 'hearth-pet', 'aria-label': 'poke your ember' }, ember.node);
  petBtn.addEventListener('click', () => ember.poke());
  // Going near it is worth something too. `greet` picks from a much gentler pool than the poke
  // does and refuses to fire twice inside a second, because a hover also happens every time the
  // pointer crosses the creature on its way to the Feed button.
  petBtn.addEventListener('mouseenter', () => ember.greet());
  petBtn.addEventListener('focus', () => ember.greet());

  const card = el('div', { class: 'hearth' },
    petBtn,
    el('div', { class: 'hearth-body' },
      el('div', { class: 'hearth-top' }, title, balance, shop.node),
      sub, meter, wares));

  function paint() {
    const pet = data.pet || {};
    ember.set(pet);
    card.dataset.state = pet.state || 'cold';
    title.textContent = pet.name || 'Ember';
    bar.style.width = `${Math.max(2, Math.round(pet.heat || 0))}%`;

    const age = pet.ageDays || 0;
    // moodLine already says how it is doing; repeating the state word after it just says it twice.
    sub.textContent = `${moodLine(pet)} · ${Math.round(pet.heat || 0)}% heat · `
      + (age < 1 ? 'lit today' : `${age} day${age === 1 ? '' : 's'} old`)
      + (pet.freezes ? ` · ${pet.freezes} freeze${pet.freezes === 1 ? '' : 's'} held` : '');
    balance.querySelector('b').textContent = pet.sparks ?? 0;

    // ---- the wardrobe ----
    // The chips are rebuilt here, so any chip the pointer was over is gone and its mouseleave will
    // never arrive. Take the try-on off explicitly or a bought hat stays ghosted after you buy it.
    ember.preview(null);
    wares.replaceChildren();
    const owned = new Set(pet.owned || []);
    const worn = new Set(pet.equipped || []);

    for (const c of data.cosmetics || []) {
      const have = owned.has(c.id);
      const on = worn.has(c.id);
      const chip = el('button', {
        class: `ware ${have ? 'owned' : ''} ${on ? 'on' : ''}`,
        title: c.blurb,
        'aria-pressed': have ? String(on) : null,
      }, el('span', {}, c.name), have ? null : el('span', { class: 'ware-cost' }, `⚡${c.cost}`));

      chip.addEventListener('click', () => have ? toggle(c, !on) : buy(c));
      // Hovering puts it on the ember. An unworn hat is a name and a price until you can see it on
      // the thing wearing it, and nobody spends 200⚡ on a noun. Focus does the same, so tabbing
      // through the wardrobe shows you the same thing a mouse would.
      if (!on) {
        chip.addEventListener('mouseenter', () => ember.preview(c.id, !have));
        chip.addEventListener('focus',      () => ember.preview(c.id, !have));
      }
      wares.append(chip);
    }

    // The one purchase that does something. Kept out of the wardrobe because it is not worn, and
    // because mixing it in would make the hats look like they might have effects too.
    const freezes = pet.freezes || 0;
    const full = freezes >= (data.maxFreezes ?? 2);
    const freeze = el('button', { class: `ware freeze ${full ? 'maxed' : ''}`,
      title: 'Saves your streak on a day you miss. The only purchase that does anything.' },
      el('span', {}, full ? 'Streak freeze (full)' : 'Streak freeze'),
      full ? null : el('span', { class: 'ware-cost' }, `⚡${data.freezeCost ?? 60}`));
    if (!full) freeze.addEventListener('click', () => buy({ id: 'freeze', name: 'Streak freeze' }));
    wares.append(freeze);
  }

  async function buy(item) {
    const r = await api('/api/pet/buy', { item: item.id });
    if (!r.ok) { toast(r.error || 'Not enough Sparks', 'err'); return; }
    toast(`${item.name} — ${r.note || 'bought'}`, 'ok');
    await refresh();
  }

  async function toggle(item, on) {
    const r = await api('/api/pet/equip', { item: item.id, on });
    if (!r.ok) { toast(r.error || 'No', 'err'); return; }
    await refresh();
  }

  async function refresh() {
    data = await api('/api/pet', {});
    emberChanged();
    paint();
  }

  // Naming it is the thing that makes it yours rather than a widget, so it is one click away.
  title.title = 'Click to rename';
  title.addEventListener('click', async () => {
    const name = prompt('Name your ember', data.pet?.name || 'Ember');
    if (name === null) return;
    const r = await api('/api/pet/name', { name });
    if (!r.ok) { toast(r.error || 'It needs a name', 'err'); return; }
    await refresh();
  });

  paint();
  return card;
}
