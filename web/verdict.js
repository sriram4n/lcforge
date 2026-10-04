// The verdict card: the top of the Result tab after a Submit.
//
// This is the moment the whole app is built around — you finished a problem — and it used to be a
// toast that vanished in 2.4 seconds with the payment as a five-character suffix. Now it is the
// biggest thing in the console, and it says three things in the order you want to know them: did
// it pass, how fast, and what it paid.

/**
 * Build the card for one /api/run reply in submit mode.
 *
 * `mk` is the page's element helper, passed in so this module stays free of app.js and can be
 * imported anywhere without dragging the router along.
 */
export function verdictCard(out, mk) {
  const results = out.results || [];
  const accepted = results.length > 0 && out.passed === out.total;
  const firstBad = results.find(r => r.verdict !== 'PASS');
  const totalMs = results.reduce((s, r) => s + (Number(r.millis) || 0), 0);

  const title = accepted ? 'Accepted' : VERDICT[firstBad?.verdict] || 'Wrong Answer';

  const card = mk('div', { class: `verdict-card ${accepted ? 'pass' : 'fail'}` },
    mk('div', { class: 'verdict-top' },
      mk('div', { class: 'verdict-title' }, title),
      paid(out, accepted, mk)),
    mk('div', { class: 'verdict-sub' },
      mk('span', {}, mk('b', {}, `${out.passed} / ${out.total}`), ' testcases passed'),
      mk('span', { class: 'dotsep' }, '·'),
      mk('span', {}, 'runtime ', mk('b', {}, `${totalMs} ms`))));

  // One bar per case, as tall as it was slow. When one case takes 40x the others, that is the case
  // your complexity is wrong on — and this is the only place the per-case times are shown together.
  if (results.length > 1) {
    const max = Math.max(1, ...results.map(r => Number(r.millis) || 0));
    const bars = mk('div', { class: 'ms-bars', 'aria-hidden': 'true' });
    results.forEach((r, k) => {
      const ms = Number(r.millis) || 0;
      const bar = mk('i', {
        class: r.verdict === 'PASS' ? 'ok' : 'bad',
        title: `Case ${(out.caseIndex?.[k] ?? k) + 1} · ${ms} ms`,
      });
      bar.style.setProperty('--h', `${Math.max(8, Math.round((ms / max) * 100))}%`);
      bars.append(bar);
    });
    card.append(bars);
  }
  return card;
}

const VERDICT = {
  FAIL: 'Wrong Answer',
  NEAR_MISS: 'Wrong Answer',
  ERROR: 'Runtime Error',
  TIMEOUT: 'Time Limit Exceeded',
};

/**
 * What it paid — or, as plainly, that it did not pay and why.
 *
 * A pass that pays nothing looks exactly like a pass that pays, and the only difference is a
 * ledger the page cannot see. Unsaid, the second solve of a problem reads as the currency being
 * broken rather than as the rule (one payment per problem, ever) working.
 */
function paid(out, accepted, mk) {
  if (out.sparks) return mk('span', { class: 'verdict-pay' }, `+⚡${out.sparks}`);
  if (accepted && out.claimed) return mk('span', { class: 'verdict-pay muted' }, 'already paid for this one');
  return null;
}
