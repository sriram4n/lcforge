// Ctrl/Cmd + wheel over an editor resizes that editor, and nothing else on the page.
//
// What is stored is a STEP, not a size. The solve editor sits at 13.5 and the timeline at 13 — half
// a point apart on purpose — so saving an absolute size would flatten one into the other the first
// time you zoomed either. A shared offset keeps the gap, and keeps both editors agreeing about how
// big "big" is, which is what you actually mean when you zoom one of them.
//
// Monaco has a `mouseWheelZoom` option and it is the wrong tool here: it drives an internal
// EditorZoom singleton that is global to every editor on the page, has no public getter to persist,
// and cannot be clamped. Left off, so the two can never double-step.

const KEY = 'lcforge.zoom.step';
const MIN = -5, MAX = 12;     // roughly 8.5px .. 25.5px from the solve editor's 13.5 base
const NOTCH = 24;             // px of wheel per step: one mouse notch (100-120) is exactly one step

const clampStep = n => Math.max(MIN, Math.min(MAX, Math.round(n)));

// localStorage throws in some privacy modes, and a font size is not worth breaking the page over.
function readStep() {
  try {
    const n = Number(localStorage.getItem(KEY));
    return Number.isFinite(n) ? clampStep(n) : 0;
  } catch { return 0; }
}
function writeStep(n) { try { localStorage.setItem(KEY, String(n)); } catch {} }

/**
 * The size an editor should be *created* at.
 *
 * Passed into `monaco.editor.create` rather than applied afterwards, so a zoomed-in editor never
 * paints one frame at the default size and then jumps.
 */
export function zoomedFontSize(base) {
  return Math.max(8, base + readStep());
}

/**
 * Wire Ctrl/Cmd+wheel and Ctrl+0 to one editor's font size.
 *
 * `host` is the element the editor was mounted into — not `window`. Scoping it there is what makes
 * Ctrl+scroll over the statement or the console still zoom the browser the way it always has: that
 * text is not the editor's, and hijacking the whole page would be a worse bargain than the feature.
 *
 * Returns a dispose function; call it from the view's teardown.
 */
export function attachFontZoom(monaco, editor, host, base) {
  let step = readStep();
  let acc = 0;

  const apply = () => editor.updateOptions({ fontSize: Math.max(8, base + step) });

  function onWheel(e) {
    if (!(e.ctrlKey || e.metaKey)) return;    // a plain scroll still belongs to the code

    e.preventDefault();     // stops the BROWSER zooming the whole interface
    e.stopPropagation();    // and stops MONACO scrolling the code while you resize it

    // Held together those two say: with Ctrl down, this gesture is the zoom's and nobody else's.

    // Chrome reports pixels, Firefox reports lines, and a page-scroll wheel reports pages. Convert
    // to something comparable first or one notch means three different things in three browsers.
    const px = e.deltaMode === 1 ? e.deltaY * 16
             : e.deltaMode === 2 ? e.deltaY * 400
             : e.deltaY;

    acc += px;
    if (Math.abs(acc) < NOTCH) return;        // a trackpad pinch arrives as a shower of tiny deltas

    // **One step per notch, then reset.** Draining the accumulator a notch at a time instead would
    // turn one flick of a real mouse wheel — which reports 100 to 120 at once — into three or four
    // steps, and the whole range would be gone in three flicks.
    const next = clampStep(step + (acc > 0 ? -1 : 1));   // wheel down is smaller, as everywhere else
    acc = 0;
    if (next === step) return;                // already at the clamp

    step = next;
    apply();
    writeStep(step);
  }

  // Two non-negotiable options here, both learned the hard way:
  //
  // `capture: true` — Monaco's scrollable element calls stopPropagation() on every wheel event
  // inside the editor, so a bubbling listener on this wrapper is never reached at all. Capture runs
  // top-down, before the target's own handlers, which is the only place upstream of that.
  //
  // `passive: false` — Chrome makes wheel listeners on a scrollable ancestor passive by default,
  // and a passive preventDefault() is ignored: the editor would resize *and* the whole page would
  // zoom underneath it.
  host.addEventListener('wheel', onWheel, { passive: false, capture: true });

  // Bound to the editor rather than the window, so it only fires with the code focused and is
  // released when the editor is disposed.
  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Digit0, () => {
    step = 0;
    acc = 0;
    apply();
    writeStep(0);
  });

  // The capture flag is part of the listener's identity — remove it without and nothing is removed.
  return () => host.removeEventListener('wheel', onWheel, { capture: true });
}
