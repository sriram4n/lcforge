// The fuel shop, in one place, used by both the strip in the editor and the hearth on the library.
//
// It borrows the palette picker's dropdown behaviour wholesale — click-away, Escape, and staying
// open while you browse. Those three are easy to write and easy to write *slightly* wrong, and a
// menu that shuts every time you buy something is the exact bug the palette picker already had once.

import { api, el, toast } from './app.js';

export function fuelShop({ onFed, onToggle, label = 'Feed' } = {}) {
  const menu = el('div', { class: 'shop-menu' });
  menu.hidden = true;

  const btn = el('button', { class: 'ghost feed-btn', 'aria-haspopup': 'menu' }, label);
  const node = el('div', { class: 'shop' }, btn, menu);

  btn.addEventListener('click', async e => {
    e.stopPropagation();
    if (!menu.hidden) return close();
    try { draw(await api('/api/pet', {})); } catch { return; }
    menu.hidden = false;
    document.addEventListener('click', onAway);
    document.addEventListener('keydown', onEsc);
    onToggle?.(true);
  });

  function close() {
    if (menu.hidden) return;
    menu.hidden = true;
    document.removeEventListener('click', onAway);
    document.removeEventListener('keydown', onEsc);
    onToggle?.(false);
  }
  const onAway = e => { if (!node.contains(e.target)) close(); };
  const onEsc = e => { if (e.key === 'Escape') close(); };

  function draw(data) {
    const pet = data.pet || {};
    menu.replaceChildren(el('div', { class: 'shop-head' },
      el('b', {}, 'Fuel'), el('span', {}, `⚡${pet.sparks ?? 0}`)));

    for (const f of data.fuel || []) {
      // Off the shelf is shown greyed rather than removed. Seeing that Garbage exists, and knowing
      // it only turns up when things are bad, is more use than a list that changes shape silently.
      const afford = (pet.sparks ?? 0) >= f.cost;
      const row = el('button', {
        class: `shop-row ${f.available ? '' : 'gone'} ${afford ? '' : 'poor'}`,
        'aria-label': `${f.name}, ${f.cost} sparks. ${f.blurb}`,
      },
        el('span', { class: 'shop-name' }, el('b', {}, f.name), el('span', {}, f.blurb)),
        el('span', { class: 'shop-cost' }, `⚡${f.cost}`));
      row.addEventListener('click', () => feed(f));
      menu.append(row);
    }
  }

  async function feed(f) {
    try {
      const r = await api('/api/pet/feed', { fuel: f.id });
      if (!r.ok) { toast(r.error || 'Not now', 'err'); return; }

      toast(r.note || `${f.name} — ${Math.round(r.pet.heat)}% heat`, 'ok');
      if (f.id === 'truffle') armTruffle();

      // Redrawn in place rather than closed: you have just spent Sparks and the balance, and what
      // you can now afford, both changed.
      try { draw(await api('/api/pet', {})); } catch { /* the balance line will catch up */ }
      // The response is handed on so the ember can size its own reaction to the size of the meal.
      onFed?.(f, r);
    } catch (e) {
      toast(e.message, 'err');
    }
  }

  /**
   * The truffle is only eaten while nobody is looking.
   *
   * Which is the joke, and also the only way a server could ever know: it waits for the page to
   * admit the tab went away.
   */
  function armTruffle() {
    const onHide = async () => {
      if (!document.hidden) return;
      document.removeEventListener('visibilitychange', onHide);
      window.removeEventListener('blur', onHide);
      try {
        const r = await api('/api/pet/feed', { fuel: 'truffle', unobserved: true });
        onFed?.({ id: 'truffle', name: 'Heisenbug Truffle' }, r);
      } catch { /* it will still be sitting there next time */ }
    };
    document.addEventListener('visibilitychange', onHide);
    window.addEventListener('blur', onHide);
  }

  return { node, close };
}
