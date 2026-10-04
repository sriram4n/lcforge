// Timeline: scrub through every line the solution executed and watch the variables move.

import { api, el, clear, toast, setCrumbs, setActions, loadMonaco, javaEditorOptions, session }
  from '../app.js';
import { colSplitter } from '../splitter.js';
import { svgEl, svgText } from '../svg.js';
import { attachFontZoom, zoomedFontSize } from '../editor-zoom.js';

export async function renderTimeline(app, slug) {
  let trace = session.trace;
  let meta = session.traceMeta;

  // Arriving here directly (refresh, bookmark) means there is nothing to show.
  if (!trace || meta?.slug !== slug) {
    const r = await api(`/api/problem?slug=${encodeURIComponent(slug)}`);
    app.append(el('div', { class: 'page narrow' },
      el('h1', {}, 'No trace loaded'),
      el('p', { class: 'sub' },
        'Traces are not stored between visits. Open the problem, pick a case and press Debug.'),
      el('a', { class: 'btn', href: `#/solve/${encodeURIComponent(slug)}` },
        `Back to ${r.ok ? (r.problem.title || slug) : slug}`)));
    return;
  }

  const steps = trace.steps || [];
  const codeLines = (meta.code || '').split('\n');
  let cursor = 0;
  let playing = false;
  let timer = null;

  setCrumbs(
    el('a', { href: '#/', style: 'color:inherit;text-decoration:none' }, 'Library'),
    el('a', { href: `#/solve/${encodeURIComponent(slug)}`, style: 'color:inherit;text-decoration:none' },
      meta.title),
    'Timeline');

  setActions(
    el('a', { class: 'btn', href: `#/solve/${encodeURIComponent(slug)}` }, '← Back to editor'));

  const root = el('div', { class: 'timeline' });

  // ---------- banners ----------

  const result = trace.result || {};
  if (trace.error) {
    root.append(el('div', { class: 'banner error' }, trace.error));
  }
  if (result.error) {
    root.append(el('div', { class: 'banner error' },
      `The solution threw: ${result.error} — the timeline below stops where it happened.`));
  } else if (result.output !== undefined && result.output !== null) {
    const expected = (trace.expected || '').trim();
    const got = String(result.output).trim();
    const match = expected && got === expected;
    root.append(el('div', { class: `banner ${expected && !match ? 'error' : ''}` },
      expected
        ? `Returned ${got} · expected ${expected}${match ? ' ✓' : ' — scrub back to find where it diverged'}`
        : `Returned ${got}`));
  }
  if (trace.truncated) {
    root.append(el('div', { class: 'banner' },
      `Trace ${trace.truncateReason}. Try a smaller input to see the whole run.`));
  }

  if (!steps.length) {
    root.append(el('div', { class: 'empty' },
      'No steps were recorded. If the solution returned immediately there was nothing to trace.'));
    app.append(root);
    return;
  }

  // ---------- scrubber ----------

  const slider = el('input', {
    type: 'range', min: '0', max: String(steps.length - 1), value: '0',
    onInput: e => { setCursor(Number(e.target.value)); stop(); }
  });

  const stepLabel = el('span', { class: 'step-label' });
  const playBtn = el('button', { onClick: toggle, title: 'Play / pause' }, '▶');

  root.append(el('div', { class: 'scrubber' },
    el('div', { class: 'scrub-controls' },
      playBtn,
      el('button', { onClick: () => { stop(); setCursor(cursor - 1); }, title: 'Previous line (k or ←)' }, '◀'),
      el('button', { onClick: () => { stop(); setCursor(cursor + 1); }, title: 'Next line (j or →)' }, '▶|'),
      stepLabel,
      el('span', { style: 'flex:1' }),
      el('button', { class: 'ghost', onClick: () => { stop(); jumpChange(-1); } }, '↤ prev change'),
      el('button', { class: 'ghost', onClick: () => { stop(); jumpChange(1); } }, 'next change ↦'),
      el('span', { class: 'muted', style: 'font-size:12px' },
        el('kbd', {}, 'j'), '/', el('kbd', {}, 'k'), ' step · ', el('kbd', {}, 'space'), ' play')),
    slider));

  // ---------- body ----------

  const codeView = el('div', { class: 'code-view' });

  const retraceBtn = el('button', { class: 'primary', onClick: retrace },
    'Re-trace', el('kbd', {}, 'Ctrl ⏎'));
  const staleBar = el('div', { class: 'stale-bar' },
    el('b', {}, 'Code edited'),
    el('span', {}, '— the timeline below still shows the previous run.'),
    el('span', { class: 'grow' }),
    retraceBtn);
  staleBar.hidden = true;

  const codePane = el('div', { class: 'code-pane' }, staleBar, codeView);

  const varsSection = el('section');
  const stackSection = el('section');
  const vizSection = el('section');

  const inspector = el('div', { class: 'inspector' }, varsSection, stackSection, vizSection);
  const body = el('div', { class: 'timeline-body' });
  body.append(codePane,
              colSplitter(body, { varName: '--code-w', key: 'lcforge.split.timeline',
                                  minLeft: 280, minRight: 240 }),
              inspector);
  root.append(body);
  app.append(root);

  // Which lines were ever executed — a cheap coverage map down the gutter.
  const visited = new Set(steps.map(s => s.line));

  // ---------- the code pane is a real editor ----------
  //
  // Finding the bug and fixing it used to be two screens. Now the trace is something you read
  // beside the code rather than instead of it: edit here, re-trace here.

  const monaco = await loadMonaco();
  const codeEditor = monaco.editor.create(codeView, javaEditorOptions({
    value: meta.code || '',
    fontSize: zoomedFontSize(13),
    padding: { top: 10 },
  }));

  // Same Ctrl+wheel zoom as the solve editor, from this one's own slightly smaller base.
  const disposeZoom = attachFontZoom(monaco, codeEditor, codeView, 13);

  const visitedMarks = codeEditor.createDecorationsCollection(
    [...visited].filter(n => n >= 1 && n <= codeLines.length).map(n => ({
      range: new monaco.Range(n, 1, n, 1),
      options: { isWholeLine: true, linesDecorationsClassName: 'trace-visited-gutter' },
    })));

  const currentMark = codeEditor.createDecorationsCollection([]);

  // Once the buffer diverges from what was traced, the line numbers in the trace no longer point
  // at the lines you are looking at. Saying so is the only honest option — silently highlighting
  // line 12 of code that has since gained three lines is worse than no highlight at all.
  let stale = false;
  let saveTimer = null;

  codeEditor.onDidChangeModelContent(() => {
    if (!stale) {
      stale = true;
      staleBar.hidden = false;
      codePane.classList.add('stale');
    }
    clearTimeout(saveTimer);
    saveTimer = setTimeout(saveCode, 900);
  });

  codeEditor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, retrace);
  codeEditor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
    saveCode();
    toast('Saved', 'ok');
  });

  async function saveCode() {
    try { await api('/api/save', { slug, code: codeEditor.getValue() }); }
    catch (e) { toast('Could not save: ' + e.message, 'err'); }
  }

  async function retrace() {
    // A trace carried over from an older session may not know which case produced it.
    if (meta.input === undefined || meta.input === null) {
      toast('This trace did not record its input — press Debug again from the editor', 'err');
      return;
    }
    retraceBtn.disabled = true;
    retraceBtn.textContent = 'Tracing…';
    try {
      await saveCode();
      const code = codeEditor.getValue();
      const t = await api('/api/trace', { slug, code, input: meta.input, expected: meta.expected });

      if (t.phase === 'compile') {
        monaco.editor.setModelMarkers(codeEditor.getModel(), 'lcforge',
          (t.diagnostics || []).filter(d => d.file === 'Solution.java').map(d => ({
            startLineNumber: d.line, endLineNumber: d.line,
            startColumn: d.col, endColumn: d.col + 1,
            severity: d.kind === 'error' ? monaco.MarkerSeverity.Error : monaco.MarkerSeverity.Warning,
            message: d.message,
          })));
        toast('Fix the compile errors first', 'err');
        return;
      }
      if (!t.ok && !t.steps?.length) throw new Error(t.error || 'trace failed');

      monaco.editor.setModelMarkers(codeEditor.getModel(), 'lcforge', []);
      session.trace = t;
      session.traceMeta = { ...meta, code };
      stop();
      // Rebuild from the top: new steps, a new coverage map, the cursor back at the start.
      clear(app);
      await renderTimeline(app, slug);
    } catch (e) {
      toast(e.message, 'err');
    } finally {
      retraceBtn.disabled = false;
      retraceBtn.textContent = 'Re-trace';
      retraceBtn.append(el('kbd', {}, 'Ctrl ⏎'));
    }
  }

  // ---------- rendering ----------

  function setCursor(i) {
    cursor = Math.max(0, Math.min(steps.length - 1, i));
    slider.value = String(cursor);
    draw();
  }

  function draw() {
    const s = steps[cursor];

    stepLabel.innerHTML = '';
    stepLabel.append(
      el('b', {}, `${cursor + 1}`), ` / ${steps.length}`,
      el('span', { class: 'muted' }, `  line ${s.line} · ${s.method}()`));

    const last = codeEditor.getModel().getLineCount();
    const line = Math.min(Math.max(1, s.line), last);
    currentMark.set([{
      range: new monaco.Range(line, 1, line, 1),
      options: {
        isWholeLine: true,
        className: 'trace-current-line',
        linesDecorationsClassName: 'trace-current-gutter',
      },
    }]);
    // Eased rather than jumped, so your eye can follow the line rather than having to hunt for it
    // again after every step.
    codeEditor.revealLineInCenterIfOutsideViewport(line, monaco.editor.ScrollType.Smooth);

    drawVars(s);
    drawStack();
    drawViz(s);
  }

  function drawVars(s) {
    clear(varsSection);
    varsSection.append(el('h3', {}, 'Variables'));
    const names = Object.keys(s.locals || {});
    if (!names.length) {
      varsSection.append(el('div', { class: 'muted', style: 'font-size:12.5px' }, 'nothing in scope yet'));
      return;
    }
    const changed = new Set(s.changed || []);
    for (const name of names) {
      varsSection.append(el('div', { class: `var-row ${changed.has(name) ? 'changed' : ''}` },
        el('span', { class: 'n' }, name),
        el('span', { class: 'v' }, s.locals[name])));
    }
  }

  /** Rebuild the call stack by walking backwards for the most recent shallower frame. */
  function drawStack() {
    clear(stackSection);
    stackSection.append(el('h3', {}, 'Call stack'));

    const frames = [];
    const cur = steps[cursor];
    frames.push(cur);
    let want = cur.depth - 1;
    for (let i = cursor - 1; i >= 0 && want > 0 && frames.length < 24; i--) {
      if (steps[i].depth === want) { frames.push(steps[i]); want--; }
    }
    frames.forEach((f, i) => {
      stackSection.append(el('div', { class: `stack-row ${i === 0 ? 'top' : ''}` },
        `${f.method}()`, el('span', { class: 'ln' }, `  line ${f.line}`)));
    });
  }

  function drawViz(s) {
    clear(vizSection);
    const structs = s.structs || {};
    const names = Object.keys(structs);
    if (!names.length) { vizSection.style.display = 'none'; return; }
    vizSection.style.display = '';
    vizSection.append(el('h3', {}, 'Structure'));
    for (const name of names) {
      vizSection.append(el('div', { class: 'viz-label' }, name));
      const box = el('div', { class: 'viz' });
      const st = structs[name];
      if (st.kind === 'list') box.append(drawList(st));
      else if (st.kind === 'tree') box.append(drawTree(st));
      else if (st.kind === 'matrix') box.append(drawMatrix(st));
      vizSection.append(box);
    }
  }

  // ---------- svg drawing ----------
  // svgEl / svgText live in ../svg.js; the heatmap and the ember draw with the same two.

  function drawList(st) {
    const vals = st.values || [];
    const w = 46, h = 26, gap = 22;
    const svg = svgEl('svg', { width: Math.max(1, vals.length * (w + gap)), height: 44 });
    vals.forEach((v, i) => {
      const x = i * (w + gap);
      svg.append(svgEl('rect', { x, y: 8, width: w, height: h, rx: 5, class: 'node' }));
      svg.append(svgText(x + w / 2, 8 + h / 2, String(v)));
      if (i < vals.length - 1 || st.cycle) {
        svg.append(svgEl('line', { x1: x + w, y1: 8 + h / 2, x2: x + w + gap - 4, y2: 8 + h / 2,
                                   class: 'link' }));
      }
    });
    if (st.cycle) {
      const t = svgText(vals.length * (w + gap) - gap / 2 + 10, 8 + h / 2, '↺', 'cycle', 14);
      svg.append(t);
    }
    return svg;
  }

  function drawTree(st) {
    const level = st.level || [];
    if (!level.length) return el('div', { class: 'muted' }, 'empty');

    // Rebuild parent/child links from LeetCode's level-order encoding, where nulls do not
    // reserve slots for their absent children.
    const nodes = [];
    const queue = [];
    let root = null;
    for (let i = 0; i < level.length; i++) {
      const v = level[i];
      if (i === 0) {
        if (v === null) break;
        root = { v, children: [], depth: 0 };
        nodes.push(root);
        queue.push(root);
        continue;
      }
      const parent = queue[0];
      if (!parent) break;
      const node = v === null ? null : { v, children: [], depth: parent.depth + 1 };
      parent.children.push(node);
      if (node) { nodes.push(node); queue.push(node); }
      if (parent.children.length === 2) queue.shift();
    }
    if (!root) return el('div', { class: 'muted' }, 'empty');

    // In-order x placement keeps siblings from overlapping.
    let nextX = 0;
    (function assign(n) {
      if (!n) return;
      if (n.children[0]) assign(n.children[0]);
      n.x = nextX++;
      if (n.children[1]) assign(n.children[1]);
    })(root);

    const colW = 40, rowH = 46, pad = 20, r = 13;
    const maxDepth = Math.max(...nodes.map(n => n.depth));
    const width = nextX * colW + pad * 2;
    const height = (maxDepth + 1) * rowH + pad * 2;
    const svg = svgEl('svg', { width, height });
    const px = n => pad + n.x * colW + colW / 2;
    const py = n => pad + n.depth * rowH + r;

    for (const n of nodes) {
      for (const c of n.children) {
        if (!c) continue;
        svg.append(svgEl('line', { x1: px(n), y1: py(n), x2: px(c), y2: py(c), class: 'edge' }));
      }
    }
    for (const n of nodes) {
      svg.append(svgEl('circle', { cx: px(n), cy: py(n), r, class: 'node ring' }));
      svg.append(svgText(px(n), py(n), String(n.v)));
    }
    return svg;
  }

  function drawMatrix(st) {
    const table = el('table', { class: 'matrix' });
    for (const row of st.rows || []) {
      const tr = el('tr');
      for (const cell of row) tr.append(el('td', {}, String(cell)));
      table.append(tr);
    }
    return table;
  }

  // ---------- playback ----------

  function toggle() { playing ? stop() : play(); }

  function play() {
    if (cursor >= steps.length - 1) cursor = 0;
    playing = true;
    playBtn.textContent = '❚❚';
    timer = setInterval(() => {
      if (cursor >= steps.length - 1) { stop(); return; }
      setCursor(cursor + 1);
    }, 120);
  }

  function stop() {
    playing = false;
    playBtn.textContent = '▶';
    clearInterval(timer);
  }

  function jumpChange(dir) {
    for (let i = cursor + dir; i >= 0 && i < steps.length; i += dir) {
      if ((steps[i].changed || []).length) { setCursor(i); return; }
    }
    toast(dir > 0 ? 'No later change' : 'No earlier change');
  }

  // ---------- keys ----------

  function onKey(e) {
    // The code pane is a live editor now, so j/k/space belong to it whenever it has focus.
    if (e.target.matches('input, textarea') || e.target.closest('.monaco-editor')) return;
    const k = e.key;
    if (k === 'j' || k === 'ArrowRight') { e.preventDefault(); stop(); setCursor(cursor + 1); }
    else if (k === 'k' || k === 'ArrowLeft') { e.preventDefault(); stop(); setCursor(cursor - 1); }
    else if (k === ' ') { e.preventDefault(); toggle(); }
    else if (k === 'Home') { stop(); setCursor(0); }
    else if (k === 'End') { stop(); setCursor(steps.length - 1); }
    else if (k === 'n') { stop(); jumpChange(1); }
    else if (k === 'p') { stop(); jumpChange(-1); }
  }
  window.addEventListener('keydown', onKey);
  new MutationObserver((_, obs) => {
    if (!document.contains(root)) {
      window.removeEventListener('keydown', onKey);
      clearInterval(timer);
      clearTimeout(saveTimer);
      disposeZoom();
      codeEditor.dispose();     // Monaco holds on to models and listeners otherwise
      obs.disconnect();
    }
  }).observe(document.getElementById('app'), { childList: true });

  setCursor(0);
}
