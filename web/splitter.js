// Draggable pane dividers.
//
// Sizes persist per splitter in localStorage, because re-dragging the same divider every time you
// open a problem would be worse than not having one. Double-click any divider to reset it.

/**
 * A vertical divider inside a CSS grid container. Drives a custom property used as the grid's
 * first column width, so the grid keeps doing the layout and we only feed it a number.
 *
 * Insert the returned node into the grid between the two panes.
 */
export function colSplitter(container, { varName, key, minLeft = 220, minRight = 340 }) {
  const bar = document.createElement('div');
  bar.className = 'splitter col';
  bar.title = 'Drag to resize · double-click to reset';

  restore(container, varName, key);

  drag(bar, {
    onMove(ev) {
      const rect = container.getBoundingClientRect();
      const limit = Math.max(minLeft, rect.width - minRight);
      const w = clamp(ev.clientX - rect.left, minLeft, limit);
      container.style.setProperty(varName, w + 'px');
    },
    onEnd() { save(container, varName, key); },
    onReset() { reset(container, varName, key); },
  });

  return bar;
}

/**
 * A horizontal divider that resizes the element BELOW it (the test panel). Sets an explicit
 * height and drops the percentage cap the stylesheet applies by default.
 */
export function rowSplitter(container, target, { key, minTop = 160, minBottom = 110 }) {
  const bar = document.createElement('div');
  bar.className = 'splitter row';
  bar.title = 'Drag to resize · double-click to reset';

  const saved = read(key);
  if (saved) {
    // Clamped on the way in, not just while dragging. A height saved before the console grew its
    // action bar can be shorter than the console now needs, and restoring it verbatim would hide
    // Submit below the fold until you found the divider and dragged it.
    const px = parseFloat(saved);
    target.style.height = `${Number.isFinite(px) ? Math.max(minBottom, px) : minBottom}px`;
    target.style.maxHeight = 'none';
  }

  drag(bar, {
    onMove(ev) {
      const rect = container.getBoundingClientRect();
      const limit = Math.max(minBottom, rect.height - minTop);
      const h = clamp(rect.bottom - ev.clientY, minBottom, limit);
      target.style.height = h + 'px';
      target.style.maxHeight = 'none';
    },
    onEnd() { write(key, target.style.height); },
    onReset() {
      target.style.removeProperty('height');
      target.style.removeProperty('maxHeight');
      target.style.removeProperty('max-height');
      remove(key);
    },
  });

  return bar;
}

// ---------- shared drag handling ----------

function drag(bar, { onMove, onEnd, onReset }) {
  bar.addEventListener('pointerdown', e => {
    if (e.button !== 0) return;
    e.preventDefault();
    // Capture keeps events coming to the bar once the cursor leaves it. It can throw for a
    // pointer the browser no longer tracks, and a failed capture is not worth losing the drag.
    try { bar.setPointerCapture(e.pointerId); } catch {}
    bar.classList.add('dragging');
    // While dragging, the cursor must not flicker and text must not select as it sweeps
    // across the editor.
    document.body.classList.add(bar.classList.contains('col') ? 'dragging-col' : 'dragging-row');

    const move = ev => onMove(ev);
    const up = ev => {
      try { bar.releasePointerCapture(ev.pointerId); } catch {}
      bar.classList.remove('dragging');
      document.body.classList.remove('dragging-col', 'dragging-row');
      bar.removeEventListener('pointermove', move);
      bar.removeEventListener('pointerup', up);
      bar.removeEventListener('pointercancel', up);
      onEnd();
    };

    bar.addEventListener('pointermove', move);
    bar.addEventListener('pointerup', up);
    bar.addEventListener('pointercancel', up);
  });

  bar.addEventListener('dblclick', onReset);
}

// ---------- persistence ----------

const clamp = (v, lo, hi) => Math.max(lo, Math.min(v, hi));

function restore(container, varName, key) {
  const saved = read(key);
  if (saved) container.style.setProperty(varName, saved);
}
function save(container, varName, key) {
  write(key, container.style.getPropertyValue(varName));
}
function reset(container, varName, key) {
  container.style.removeProperty(varName);
  remove(key);
}

// localStorage throws in some privacy modes; a splitter is not worth breaking the page over.
function read(key)         { try { return localStorage.getItem(key); } catch { return null; } }
function write(key, value) { try { if (value) localStorage.setItem(key, value); } catch {} }
function remove(key)       { try { localStorage.removeItem(key); } catch {} }
