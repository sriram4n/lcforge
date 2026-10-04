// Solve: statement, editor, testcases, results. The place you actually work.
//
// Laid out the way LeetCode lays it out, because that is the layout your hands already know: the
// problem on the left (with its history a tab away), the code on the right, and a console under
// the code with the cases on one tab and the verdict on the other.
//
// Two buttons, two jobs. **Run** tries the case you are looking at, fast, as often as you like, and
// can never pay or count as solving anything. **Submit** judges every case, and is the only way a
// problem gets solved — so the one moment the economy pays out is a moment you chose to make.

import { api, el, clear, toast, setCrumbs, setActions, loadMonaco, javaEditorOptions, session }
  from '../app.js';
import { colSplitter, rowSplitter } from '../splitter.js';
import { invalidate } from '../stats.js';
import { emberPerch, emberChanged } from '../companion.js';
import { attachFontZoom, zoomedFontSize } from '../editor-zoom.js';
import { renderStatement } from '../statement.js';
import { verdictCard } from '../verdict.js';

export async function renderSolve(app, slug) {
  const r = await api(`/api/problem?slug=${encodeURIComponent(slug)}`);
  if (!r.ok) throw new Error(r.error);
  const problem = r.problem;

  // Working copy of the testcases; saved back on edit.
  let cases = (problem.testcases || []).map(c => ({ ...c }));
  if (!cases.length) cases = [{ input: '', expected: '' }];

  // The one selection. Both console tabs draw from it, so the case you picked on Testcase is the
  // case you are shown on Result and the case Debug steps through — there is no second index to
  // drift out of step with the first.
  let active = 0;
  let results = null;     // the last /api/run reply, plus `caseIndex`: which case each result is
  let editor = null;
  let busyNow = false;

  setCrumbs(
    el('a', { href: '#/', style: 'color:inherit;text-decoration:none' }, 'Library'),
    problem.title || slug);
  setActions(problem.url
    ? el('a', { class: 'btn ghost', href: problem.url, target: '_blank', rel: 'noopener' }, 'On LeetCode ↗')
    : null);

  // ---------- the left pane: Description | Attempts ----------

  const hasStatement = !!(problem.statement || '').trim();
  const root = el('div', { class: 'solve' });

  const descPanel = el('div', { class: 'desc-panel' },
    statementHead(problem),
    hasStatement
      ? renderStatement(problem.statement, el)
      : el('div', { class: 'empty-note' },
          el('b', {}, 'No description'),
          el('span', {}, 'This problem was pasted without one. The cases and your attempts are all here; ' +
                         'the statement is on LeetCode if you need it.')));

  const attemptsPanel = el('div', { class: 'attempts-panel' });
  attemptsPanel.hidden = true;
  let attemptsStale = true;

  const leftTabs = tabBar([
    { id: 'desc', label: 'Description' },
    { id: 'attempts', label: 'Attempts' },
  ], showLeftTab);

  root.append(el('div', { class: 'pane' },
    el('div', { class: 'pane-head' }, leftTabs.node),
    el('div', { class: 'pane-body' }, descPanel, attemptsPanel)));
  root.append(colSplitter(root, { varName: '--statement-w', key: 'lcforge.split.statement' }));

  function showLeftTab(id) {
    leftTabs.select(id);
    descPanel.hidden = id !== 'desc';
    attemptsPanel.hidden = id !== 'attempts';
    if (id === 'attempts' && attemptsStale) drawAttempts();
  }

  // ---------- the right pane: editor over console ----------

  const editorWrap = el('div', { class: 'editor-wrap' });

  const modeSelect = el('select', {
    class: 'compact',
    title: 'How your output is compared with the expected answer',
    onChange: async e => {
      await api('/api/save', { slug, compareMode: e.target.value });
      toast('Comparison mode saved', 'ok');
    }
  },
    el('option', { value: 'exact' }, 'exact match'),
    el('option', { value: 'unordered' }, 'any order'),
    el('option', { value: 'float' }, 'float ±1e-5'));
  modeSelect.value = problem.compareMode || 'exact';

  const editorPane = el('div', { class: 'pane' },
    el('div', { class: 'pane-head' },
      el('h3', {}, 'Solution.java'),
      signature(problem) ? el('span', { class: 'signature', title: 'from LeetCode — the order your input lines map to' }, signature(problem)) : null,
      el('span', { class: 'spacer' }),
      el('span', { class: 'muted', style: 'font-size:12px' }, 'compare:'),
      modeSelect),
    editorWrap);

  // The console. `.tests` keeps its name and its identity because it is what the row splitter
  // resizes, and a saved height under the old key must keep working.
  const casePanel = el('div', { class: 'case-panel' });
  const resultPanel = el('div', { class: 'result-panel' });
  resultPanel.hidden = true;
  const consoleTabs = tabBar([
    { id: 'testcase', label: 'Testcase' },
    { id: 'result', label: 'Result' },
  ], showPanelTab);

  const debugBtn = el('button', { class: 'ghost', title: 'Step through the selected case, line by line', onClick: () => debugCase(active) }, '🐞 Debug');
  const runBtn = el('button', { title: 'Run the selected case (Ctrl+Enter)', onClick: () => execute('run') }, 'Run');
  const submitBtn = el('button', { class: 'primary', title: 'Judge every case (Ctrl+Shift+Enter)', onClick: () => execute('submit') }, 'Submit');
  const BUTTON_LABEL = new Map([[debugBtn, '🐞 Debug'], [runBtn, 'Run'], [submitBtn, 'Submit']]);

  const testsPanel = el('div', { class: 'tests' },
    el('div', { class: 'tests-head' }, consoleTabs.node),
    el('div', { class: 'tests-body' }, casePanel, resultPanel),
    el('div', { class: 'console-bar' },
      el('span', { class: 'console-hint' }, el('kbd', {}, 'Ctrl ⏎'), ' run · ', el('kbd', {}, 'Ctrl ⇧ ⏎'), ' submit'),
      el('span', { class: 'spacer' }),
      debugBtn, runBtn, submitBtn));

  // It perches inside the editor rather than under it: absolutely positioned, click-through, and
  // costing the code no height at all. Mounted after Monaco exists so it is last in the DOM and
  // sits above the editor's own layers without a z-index fight.
  const pet = emberPerch();

  // 190 rather than the splitter's default 110: head, case chips and the action bar all have to fit,
  // or a short console pushes Submit out of sight.
  editorPane.append(rowSplitter(editorPane, testsPanel, { key: 'lcforge.split.tests', minBottom: 190 }));
  editorPane.append(testsPanel);
  root.append(editorPane);

  app.append(root);
  if (!hasStatement) showLeftTab('attempts');

  // ---------- editor ----------

  const monaco = await loadMonaco();
  editor = monaco.editor.create(editorWrap,
    javaEditorOptions({ value: problem.code || '', fontSize: zoomedFontSize(13.5) }));
  editorWrap.append(pet.node);

  // Ctrl+wheel resizes the code and only the code. The perch is already appended, so the listener
  // goes on the wrapper both of them share.
  const disposeZoom = attachFontZoom(monaco, editor, editorWrap, 13.5);

  // Monaco matches modifiers exactly, so these two never trigger each other. The window listener
  // below is the one that has to tell them apart by hand.
  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => execute('run'));
  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.Enter, () => execute('submit'));
  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => { saveCode(); toast('Saved', 'ok'); });

  // Debounced autosave: the workspace file should always match what you see.
  let saveTimer = null;
  editor.onDidChangeModelContent(() => {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(saveCode, 900);
  });

  // It watches where you are working. Costs nothing, and is most of why it reads as a companion
  // rather than as a battery indicator with a face.
  editor.onDidChangeCursorPosition(e => {
    const width = editor.getLayoutInfo().contentWidth || 1;
    pet.lookAt(editor.getScrolledVisiblePosition(e.position)?.left / width ?? 0.5);
  });

  async function saveCode() {
    try { await api('/api/save', { slug, code: editor.getValue() }); }
    catch (e) { toast('Could not save: ' + e.message, 'err'); }
  }

  window.addEventListener('keydown', onKey);
  function onKey(e) {
    // Monaco has already handled it when the editor has focus; this is for everywhere else.
    if (e.defaultPrevented || !(e.ctrlKey || e.metaKey) || e.key !== 'Enter') return;
    e.preventDefault();
    // Shift is checked, not assumed. Ctrl+Shift+Enter also satisfies "Ctrl and Enter", and without
    // this a Submit would quietly fire a Run first.
    execute(e.shiftKey ? 'submit' : 'run');
  }

  // Views are torn down by clearing #app, so stop listening when this node goes away.
  new MutationObserver((_, obs) => {
    if (!document.contains(root)) {
      window.removeEventListener('keydown', onKey);
      clearTimeout(saveTimer);
      clearTimeout(caseTimer);
      disposeZoom();
      editor.dispose();   // otherwise every visit leaves a live editor and its model behind
      pet.dispose();
      obs.disconnect();
    }
  }).observe(document.getElementById('app'), { childList: true });

  // ---------- the console: tabs ----------

  function showPanelTab(id) {
    consoleTabs.select(id);
    casePanel.hidden = id !== 'testcase';
    resultPanel.hidden = id !== 'result';
  }

  /** Where in the last run this case's result sits, or -1 if it was not part of it. */
  const resultIndexOf = i => results?.caseIndex ? results.caseIndex.indexOf(i) : -1;
  const resultFor = i => results?.results?.[resultIndexOf(i)];

  function drawPanelTabs() {
    const res = results;
    let badge = null;
    if (res?.phase === 'compile') badge = el('span', { class: 'tab-badge fail' }, 'error');
    else if (res?.results) {
      const all = res.passed === res.total;
      badge = el('span', { class: `tab-badge ${all ? 'pass' : 'fail'}` }, `${res.passed}/${res.total}`);
    }
    consoleTabs.label('result', 'Result', badge);
  }

  // ---------- the console: Testcase ----------

  function addCase() {
    cases.push({ input: '', expected: '' });
    active = cases.length - 1;
    drawCaseChips(); drawCaseBody(); persistCases();
  }

  function removeCase(i) {
    if (cases.length <= 1) { toast('Keep at least one case', 'err'); return; }
    cases.splice(i, 1);
    if (active >= cases.length || active > i) active = Math.max(0, active - 1);
    // The results are indexed by case, and the cases just moved. A stale verdict on the wrong case
    // is worse than none, so they go, and the Result tab goes with them.
    results = null;
    drawPanelTabs();
    drawResult();
    showPanelTab('testcase');
    drawCaseChips(); drawCaseBody(); persistCases();
  }

  async function persistCases() {
    try { await api('/api/save', { slug, testcases: cases }); }
    catch (e) { toast('Could not save cases: ' + e.message, 'err'); }
  }

  const caseChips = el('div', { class: 'chips' });
  const caseBody = el('div', { class: 'case-body' });
  casePanel.append(caseChips, caseBody);

  function caseChip(i, { removable }) {
    const verdict = resultFor(i)?.verdict || '';
    const chip = el('button', {
      class: `chip ${i === active ? 'active' : ''}`,
      onClick: () => select(i),
    }, el('span', { class: `dot ${verdict}` }), `Case ${i + 1}`);
    if (removable && i === active && cases.length > 1) {
      chip.append(el('span', {
        class: 'chip-x', title: 'Remove this case', role: 'button', 'aria-label': `Remove case ${i + 1}`,
        onClick: e => { e.stopPropagation(); removeCase(i); },
      }, '×'));
    }
    return chip;
  }

  function drawCaseChips() {
    clear(caseChips);
    cases.forEach((_, i) => caseChips.append(caseChip(i, { removable: true })));
    caseChips.append(el('button', { class: 'chip add', title: 'Add a case', 'aria-label': 'Add a case', onClick: addCase }, '+'));
  }

  /** Select a case on either tab. Redraws only what depends on the selection. */
  function select(i) {
    if (i === active) return;
    active = i;
    drawCaseChips();
    drawCaseBody();
    drawResult();
  }

  // Rebuilt only when the selection moves or the list changes — never by a run. A run used to
  // rebuild these textareas, which threw away whatever you were in the middle of typing.
  function drawCaseBody() {
    clear(caseBody);
    const c = cases[active];

    const inputBox = el('textarea', {
      spellcheck: 'false',
      placeholder: 'One argument per line, exactly as LeetCode shows it:\n[2,7,11,15]\n9',
      onInput: e => { c.input = e.target.value; scheduleCaseSave(); },
      // Finish typing an input for a case that has no answer yet and we go and fetch one.
      onBlur: () => { if (c.input.trim() && !(c.expected || '').trim()) capture(c, true); },
    });
    inputBox.value = c.input || '';

    const expectedBox = el('textarea', {
      spellcheck: 'false',
      class: 'short',
      placeholder: 'run your solution to fill this in, or type it',
      onInput: e => {
        c.expected = e.target.value;
        c.captured = false;      // you typed it, so it is yours now — drop the badge
        scheduleCaseSave();
        drawCapturedBadge();
      }
    });
    expectedBox.value = c.expected || '';

    const captureBtn = el('button', {
      class: 'ghost capture-btn',
      title: 'Run your current solution on this input and use its answer',
      onClick: () => capture(c, false),
    }, '⤓ Capture');

    const badgeSlot = el('span');
    function drawCapturedBadge() {
      clear(badgeSlot);
      if (c.captured && (c.expected || '').trim()) {
        badgeSlot.append(el('span', {
          class: 'captured-badge',
          title: 'This came from running your current solution, not from LeetCode. It pins '
               + "today's behaviour as a regression test — check that it is actually right.",
        }, '⚠ captured — verify'));
      }
    }
    drawCapturedBadge();

    /** Run the current solution on this input and adopt whatever it produced. */
    async function capture(target, silent) {
      if (!target.input.trim()) return;
      captureBtn.disabled = true;
      captureBtn.textContent = 'Running…';
      try {
        const out = await api('/api/capture', { slug, code: editor.getValue(), input: target.input });
        if (!out.ok) {
          if (!silent) toast(out.error || 'could not capture', 'err');
          return;
        }
        target.expected = out.output;
        target.captured = true;
        expectedBox.value = out.output;
        drawCapturedBadge();
        persistCases();
        if (!silent) toast('Captured ' + out.output, 'ok');
      } catch (e) {
        if (!silent) toast(e.message, 'err');
      } finally {
        captureBtn.disabled = false;
        captureBtn.textContent = '⤓ Capture';
      }
    }

    caseBody.append(
      el('div', { class: 'field' },
        el('div', { class: 'field-head' }, el('label', {}, 'Input')),
        inputBox),
      el('div', { class: 'field' },
        el('div', { class: 'field-head' }, el('label', {}, 'Expected'), badgeSlot, captureBtn),
        expectedBox));
  }

  let caseTimer = null;
  function scheduleCaseSave() {
    clearTimeout(caseTimer);
    caseTimer = setTimeout(persistCases, 700);
  }

  // ---------- the console: Result ----------

  function drawResult() {
    clear(resultPanel);
    const res = results;

    if (!res) {
      resultPanel.append(el('div', { class: 'empty-note' },
        el('b', {}, 'Nothing run yet'),
        el('span', {}, 'Run tries the selected case. Submit judges all of them — and is the only thing that counts as solving it.')));
      return;
    }

    if (res.phase === 'compile') {
      resultPanel.append(
        el('div', { class: 'verdict-title fail' }, 'Compile Error'),
        compileErrors(res.diagnostics));
      return;
    }

    if (res.mode === 'submit') resultPanel.append(verdictCard(res, el));

    // The chips: every case that took part, coloured by how it went.
    const chips = el('div', { class: 'chips' });
    res.caseIndex.forEach(i => chips.append(caseChip(i, { removable: false })));
    resultPanel.append(chips);

    const k = resultIndexOf(active);
    if (k < 0) {
      resultPanel.append(el('div', { class: 'muted', style: 'padding:6px 2px' },
        `Case ${active + 1} was not part of the last ${res.mode === 'run' ? 'run' : 'submission'}.`));
      return;
    }
    resultPanel.append(resultDetail(res.results[k]));
  }

  function resultDetail(res) {
    const passed = res.verdict === 'PASS';
    const box = el('div', { class: `result-box ${passed ? 'pass' : 'fail'}` });

    box.append(el('div', { class: `verdict-line ${passed ? 'pass' : 'fail'}` },
      passed ? '✓ Correct' : '✗ ' + label(res.verdict),
      el('span', { class: 'ms' }, `${res.millis ?? 0} ms`)));

    const row = (k, v, cls = '') => el('div', { class: 'diff-row' },
      el('span', { class: 'k' }, k), el('span', { class: `v ${cls}` }, v));

    box.append(row('input', (res.input || '').trim() || '(empty)'));
    if (res.error) {
      box.append(row('error', res.error, 'bad'));
      if (res.stackTrace) box.append(el('div', { class: 'muted', style: 'margin-top:6px' }, res.stackTrace));
    } else {
      box.append(
        row('yours', res.output || '(nothing)', passed ? 'good' : 'bad'),
        row('expected', res.expected || '(not set)'));
    }
    if (res.stdout) box.append(row('stdout', res.stdout.trimEnd(), 'muted'));
    if (res.note) box.append(el('div', { class: 'note' }, res.note));

    if (!passed) {
      box.append(el('div', { style: 'margin-top:10px' },
        el('button', { onClick: () => debugCase(active) }, '🐞 Step through this case')));
    }
    return box;
  }

  // ---------- attempts ----------

  let attemptFilter = 'all';

  async function drawAttempts() {
    attemptsStale = false;
    clear(attemptsPanel);
    attemptsPanel.append(el('div', { class: 'muted', style: 'padding:16px 18px' }, 'Loading…'));
    let data;
    try { data = await api(`/api/attempts?slug=${encodeURIComponent(slug)}`); }
    catch (e) {
      clear(attemptsPanel);
      attemptsPanel.append(el('div', { class: 'empty-note' }, el('b', {}, 'Could not load attempts'), el('span', {}, e.message)));
      return;
    }
    paintAttempts(data);
  }

  function paintAttempts(data) {
    clear(attemptsPanel);
    const rows = data.attempts || [];
    const filters = el('div', { class: 'filter-chips' },
      ...[['all', 'All'], ['submit', 'Submissions'], ['run', 'Runs']].map(([id, text]) =>
        el('button', {
          class: `fchip ${attemptFilter === id ? 'on' : ''}`,
          onClick: () => { attemptFilter = id; paintAttempts(data); },
        }, text)));

    attemptsPanel.append(el('div', { class: 'attempts-head' },
      el('span', { class: 'muted' },
        `${data.submits || 0} submission${data.submits === 1 ? '' : 's'} · ${data.runs || 0} run${data.runs === 1 ? '' : 's'}`),
      el('span', { class: 'spacer' }),
      filters));

    const shown = rows.filter(a => attemptFilter === 'all' || a.mode === attemptFilter);
    if (!shown.length) {
      attemptsPanel.append(el('div', { class: 'empty-note' },
        el('b', {}, rows.length ? 'Nothing here with this filter' : 'No attempts yet'),
        el('span', {}, rows.length ? 'Try another filter.' : 'Every Run and Submit lands here, newest first.')));
      return;
    }

    const list = el('div', { class: 'attempts' });
    for (const a of shown) {
      const v = attemptVerdict(a);
      list.append(el('div', { class: 'attempt' },
        el('span', { class: `mode-badge ${a.mode}` }, a.mode === 'run' ? 'RUN' : 'SUBMIT'),
        el('span', { class: `attempt-verdict ${v.cls}` }, v.text),
        el('span', { class: 'attempt-cases' }, a.total ? `${a.passed}/${a.total}` : ''),
        el('span', { class: 'attempt-ms' }, a.total ? `${a.runMs} ms` : ''),
        el('span', { class: 'attempt-when', title: new Date(a.t).toLocaleString() }, ago(a.t))));
    }
    attemptsPanel.append(list);
    if (rows.length >= 60) attemptsPanel.append(el('div', { class: 'muted', style: 'padding:10px 18px;font-size:12px' }, 'Showing the 60 most recent.'));
  }

  // ---------- actions ----------

  /** Disable a button and say what it is doing; put its label back when done. */
  function busy(btn, text) {
    if (text) { btn.disabled = true; btn.textContent = text; }
    else { btn.disabled = false; btn.textContent = BUTTON_LABEL.get(btn); }
  }

  /**
   * Run or Submit.
   *
   * Which cases go is decided here, because only the page knows which one you are looking at. A
   * Submit leaves out cases with no input at all: "+ case" makes an empty one, and an empty case
   * would fail every submission forever — a permanent block on Accepted from a stray click.
   */
  async function execute(mode) {
    if (busyNow || !editor) return;
    const submit = mode === 'submit';

    const picked = submit
      ? cases.map((c, i) => ({ c, i })).filter(({ c }) => (c.input || '').trim())
      : [{ c: cases[active], i: active }];
    if (!picked.length || !(picked[0].c.input || '').trim()) {
      toast(submit ? 'Give at least one case an input first' : `Case ${active + 1} has no input yet`, 'err');
      showPanelTab('testcase');
      return;
    }

    busyNow = true;
    const btn = submit ? submitBtn : runBtn;
    busy(btn, submit ? 'Judging…' : 'Running…');
    (submit ? runBtn : submitBtn).disabled = true;
    try {
      await persistCases();
      const out = await api('/api/run', {
        slug, mode, code: editor.getValue(), testcases: picked.map(p => p.c),
      });
      out.mode = out.mode || mode;
      out.caseIndex = picked.map(p => p.i);
      results = out;

      if (out.phase === 'compile') {
        markErrors(out.diagnostics);
        pet.react('compile');
      } else {
        markErrors([]);
        const all = out.passed === out.total;
        if (submit) {
          // The payment is said where you are looking: the Result card says it in words, and the
          // ember says it with a number floating off its head.
          if (all && out.sparks) pet.celebrate?.();
          else pet.react(all ? 'pass' : 'fail');
          if (out.sparks) pet.reward(out.sparks);
          // Jump to the first failure — that is what you want to look at.
          const firstBad = out.results.findIndex(x => x.verdict !== 'PASS');
          if (firstBad >= 0) active = out.caseIndex[firstBad];
        } else {
          pet.react(all ? 'pass' : 'fail');
        }
      }
      drawPanelTabs();
      drawCaseChips();
      drawResult();
      showPanelTab('result');

      // The run is now in the log, so the streak chip, the heatmap, the balance and the Attempts
      // tab are all a moment out of date.
      attemptsStale = true;
      if (!attemptsPanel.hidden) drawAttempts();
      invalidate();
      emberChanged();
      pet.refresh();
    } catch (e) {
      toast(e.message, 'err');
    } finally {
      busy(btn);
      runBtn.disabled = submitBtn.disabled = false;
      busyNow = false;
    }
  }

  function markErrors(diagnostics) {
    const model = editor.getModel();
    monaco.editor.setModelMarkers(model, 'lcforge', (diagnostics || [])
      .filter(d => d.file === 'Solution.java' && d.line > 0)
      .map(d => ({
        severity: d.kind === 'error' ? monaco.MarkerSeverity.Error : monaco.MarkerSeverity.Warning,
        message: d.message,
        startLineNumber: d.line, endLineNumber: d.line,
        startColumn: Math.max(1, d.col), endColumn: Math.max(2, d.col + 1),
      })));
  }

  function compileErrors(diagnostics) {
    const box = el('div', { class: 'compile-errors' });
    const errs = (diagnostics || []).filter(d => d.kind === 'error');
    if (!errs.length) { box.append('Compilation failed, but javac reported no details.'); return box; }
    errs.forEach(d => {
      box.append(
        el('span', {
          class: 'loc',
          onClick: () => { editor.revealLineInCenter(d.line); editor.setPosition({ lineNumber: d.line, column: d.col || 1 }); editor.focus(); }
        }, `${d.file}:${d.line}`),
        '  ' + d.message + '\n\n');
    });
    return box;
  }

  async function debugCase(i) {
    const c = cases[i];
    if (!c || !c.input.trim()) { toast('This case has no input to run', 'err'); return; }
    busy(debugBtn, 'Tracing…');
    try {
      await saveCode();
      const t = await api('/api/trace', {
        slug, code: editor.getValue(), input: c.input, expected: c.expected
      });
      if (t.phase === 'compile') {
        results = { ...t, caseIndex: [] };
        markErrors(t.diagnostics);
        drawPanelTabs();
        drawResult();
        showPanelTab('result');
        toast('Fix the compile errors first', 'err');
        return;
      }
      if (!t.ok && !t.steps?.length) throw new Error(t.error || 'trace failed');

      session.trace = t;
      // input and expected travel along so the timeline can re-run the same case after you edit
      // the code there, without sending you back here to press Debug again.
      session.traceMeta = {
        slug, title: problem.title || slug, code: editor.getValue(), caseIndex: i,
        input: c.input, expected: c.expected,
      };
      location.hash = `#/trace/${encodeURIComponent(slug)}`;
    } catch (e) {
      toast(e.message, 'err');
    } finally {
      busy(debugBtn);
    }
  }

  drawCaseChips();
  drawCaseBody();
  drawResult();
  drawPanelTabs();
}

// ---------- pieces ----------

/**
 * A row of tabs. `onPick(id)` is called with the chosen tab; `select(id)` marks one without
 * calling it, and `label(id, text, badge)` rewrites one — the Result tab carries its score.
 */
function tabBar(tabs, onPick) {
  const node = el('div', { class: 'tabs', role: 'tablist' });
  const byId = new Map();
  for (const t of tabs) {
    const b = el('button', { class: 'tab', role: 'tab', onClick: () => onPick(t.id) }, t.label);
    byId.set(t.id, b);
    node.append(b);
  }
  const select = id => {
    for (const [k, b] of byId) {
      b.classList.toggle('active', k === id);
      b.setAttribute('aria-selected', String(k === id));
    }
  };
  select(tabs[0].id);
  return {
    node,
    select,
    label(id, text, badge) {
      const b = byId.get(id);
      clear(b);
      b.append(text);
      if (badge) b.append(badge);
    },
  };
}

/** Title, difficulty and topics: the furniture LeetCode puts above every statement. */
function statementHead(p) {
  const tags = Array.isArray(p.topicTags) ? p.topicTags : [];
  return el('div', { class: 'statement-head' },
    el('h2', {}, p.title || 'Untitled'),
    el('div', { class: 'statement-meta' },
      el('span', { class: `diff ${p.difficulty || 'none'}` }, p.difficulty || 'Unrated'),
      ...tags.slice(0, 8).map(t => el('span', { class: 'topic-tag' }, t))));
}

function attemptVerdict(a) {
  if (a.verdict === 'COMPILE') return { text: 'Compile Error', cls: 'fail' };
  if (a.mode === 'submit' && a.ok) return { text: 'Accepted', cls: 'pass' };
  if (a.verdict === 'TIMEOUT') return { text: 'Time Limit Exceeded', cls: 'fail' };
  if (a.verdict === 'ERROR') return { text: 'Runtime Error', cls: 'fail' };
  if (a.verdict === 'PASS') return { text: a.mode === 'run' ? 'Passed' : 'Accepted', cls: 'pass' };
  if (!a.total) return { text: 'No cases', cls: 'muted' };
  return { text: 'Wrong Answer', cls: 'fail' };
}

/** "4m ago", "3h ago", "yesterday", "Sep 14": precise enough to find the attempt, no more. */
function ago(t) {
  const s = Math.max(0, (Date.now() - t) / 1000);
  if (s < 45) return 'just now';
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  if (s < 86400) return `${Math.round(s / 3600)}h ago`;
  if (s < 172800) return 'yesterday';
  return new Date(t).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

function label(v) {
  return {
    FAIL: 'Wrong answer',
    NEAR_MISS: 'Wrong answer — right values, wrong order',
    ERROR: 'Runtime error',
    TIMEOUT: 'Time limit exceeded',
  }[v] || v;
}

/**
 * Render the method signature LeetCode reports in metaData, e.g. "twoSum(int[] nums, int target)".
 * Worth showing because it tells you what each line of the input box actually maps to -- the
 * single most common source of confusion when a testcase is pasted by hand.
 */
function signature(p) {
  if (!p.entry || !p.paramNames?.length) return '';
  const args = p.paramNames.map((n, i) => `${javaType(p.paramTypes?.[i])} ${n}`).join(', ');
  return `${javaType(p.returnType)} ${p.entry}(${args})`;
}

/** LeetCode's type vocabulary is its own; map it onto what you actually write in Java. */
function javaType(t) {
  if (!t) return '';
  const suffix = (t.match(/\[\]/g) || []).join('');
  const base = t.replace(/\[\]/g, '').trim();

  const list = base.match(/^list<(.+)>$/i);
  if (list) return `List<${boxed(javaType(list[1]))}>`;

  const simple = {
    integer: 'int', long: 'long', double: 'double', float: 'double',
    string: 'String', character: 'char', boolean: 'boolean', void: 'void',
  }[base.toLowerCase()];
  return (simple || base) + suffix;
}

/** Generics cannot hold primitives: List<int> is not a thing. */
function boxed(t) {
  return { int: 'Integer', long: 'Long', double: 'Double',
           char: 'Character', boolean: 'Boolean' }[t] || t;
}
