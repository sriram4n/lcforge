// SVG element helpers, shared by the structure diagrams in the timeline, the activity heatmap and
// the ember.
//
// Colours are never passed in here. Everything these build is styled by CSS class instead, so a
// drawing follows the active palette -- including when you switch palette while it is on screen.

const SVG = 'http://www.w3.org/2000/svg';

export function svgEl(tag, attrs = {}, ...children) {
  const n = document.createElementNS(SVG, tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === null || v === undefined || v === false) continue;
    n.setAttribute(k, v);
  }
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue;
    n.append(c);
  }
  return n;
}

export function svgText(x, y, text, cls = '', size = 11) {
  const t = svgEl('text', { x, y, class: cls, 'font-size': size, 'font-family': 'monospace',
                            'text-anchor': 'middle', 'dominant-baseline': 'middle' });
  t.textContent = text;
  return t;
}

/** A <title> child is the browser's own tooltip — no hover machinery, and it reads to screen readers. */
export function svgTitle(text) {
  const t = svgEl('title');
  t.textContent = text;
  return t;
}
