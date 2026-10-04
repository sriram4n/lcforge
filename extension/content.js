// LCForge content script.
//
// Adds one button to a LeetCode problem page that ships the problem to the LCForge app running on
// this computer.
//
// It reads the problem through LeetCode's OWN GraphQL endpoint -- the same request the page makes
// to render itself -- rather than scraping the DOM, because the DOM changes with every redesign
// and the API does not. Nothing Premium-gated is touched: this returns exactly what your browser
// is already showing you.
//
// It never talks to LCForge itself. That is background.js's job: a request from here would count as
// leetcode.com's own, and Chrome makes a web page ask before it may reach your machine.

(() => {
  const QUERY = `
    query questionData($titleSlug: String!) {
      question(titleSlug: $titleSlug) {
        questionFrontendId
        title
        titleSlug
        difficulty
        content
        exampleTestcaseList
        metaData
        topicTags { name }
        codeSnippets { langSlug code }
      }
    }`;

  let button, note, currentSlug = null;

  function slugFromUrl() {
    const m = location.pathname.match(/\/problems\/([^/]+)/);
    return m ? m[1] : null;
  }

  // Text only. Never innerHTML: an error shown here may have come from a local port, and anything
  // could be listening on one of those.
  function showNote(text, ms = 6000) {
    note?.remove();
    note = document.createElement('div');
    note.id = 'lcforge-note';
    note.textContent = text;
    document.body.appendChild(note);
    if (ms) setTimeout(() => note?.remove(), ms);
  }

  // The button is a single glyph, so state is carried by which glyph and what colour it is --
  // never by growing a background. Titles do the explaining on hover.
  const STATES = {
    idle:    { icon: '⚡', cls: '',     title: 'Send this problem to LCForge' },
    reading: { icon: '⚡', cls: 'busy', title: 'Reading the problem…' },
    sending: { icon: '⚡', cls: 'busy', title: 'Sending to LCForge…' },
    sent:    { icon: '✓', cls: 'ok',   title: 'Sent to LCForge' },
    failed:  { icon: '⚠', cls: 'err',  title: 'Could not send — see the note' },
  };

  function setState(name) {
    const s = STATES[name];
    button.textContent = s.icon;
    button.className = s.cls;
    button.title = s.title;
    button.disabled = (s.cls === 'busy');
  }

  async function fetchProblem(slug) {
    const res = await fetch('/graphql', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({
        operationName: 'questionData',
        variables: { titleSlug: slug },
        query: QUERY,
      }),
    });
    if (!res.ok) throw new Error(`LeetCode returned ${res.status}`);
    const json = await res.json();
    const q = json?.data?.question;
    if (!q) throw new Error('LeetCode did not return this problem');
    return q;
  }

  async function send() {
    const slug = slugFromUrl();
    if (!slug) return;

    setState('reading');
    try {
      const q = await fetchProblem(slug);
      const java = (q.codeSnippets || []).find(s => s.langSlug === 'java');

      setState('sending');
      const result = await chrome.runtime.sendMessage({ type: 'import', payload: {
        slug: q.titleSlug,
        title: `${q.questionFrontendId}. ${q.title}`,
        difficulty: q.difficulty || '',
        statement: q.content || '',
        snippet: java ? java.code : '',
        // Authoritative example inputs, already one-argument-per-line. Parsing them out of the
        // statement HTML works but breaks on unusual formatting; this is what LeetCode's own
        // judge uses.
        exampleInputs: q.exampleTestcaseList || [],
        // The real signature: method name, parameter names and types. Removes any guesswork
        // about which method to call.
        meta: q.metaData || '',
        topicTags: (q.topicTags || []).map(t => t.name),
        url: `https://leetcode.com/problems/${q.titleSlug}/`,
      } });
      if (!result?.ok) {
        throw Object.assign(new Error(result?.error || 'LCForge did not answer'), { offline: !!result?.offline });
      }

      // The worker opens the tab.
      setState('sent');
      setTimeout(() => setState('idle'), 1800);

    } catch (e) {
      setState('failed');
      showNote(e.offline
        ? 'Could not reach LCForge. Open the LCForge app, then try again.'
        // The extension was updated or reloaded under this tab, which cuts this script off from it.
        : /context invalidated/i.test(String(e.message))
          ? 'LCForge was just updated. Reload this page, then try again.'
          : `LCForge: ${e.message}`);
      setTimeout(() => setState('idle'), 3000);
    }
  }

  function mount() {
    if (document.getElementById('lcforge-btn')) return;
    button = document.createElement('button');
    button.id = 'lcforge-btn';
    button.setAttribute('aria-label', 'Send this problem to LCForge');
    button.addEventListener('click', send);
    document.body.appendChild(button);
    setState('idle');
  }

  // LeetCode is a single-page app: navigating between problems never reloads the document,
  // so watch for URL changes instead of relying on load events.
  function watch() {
    const slug = slugFromUrl();
    if (slug && slug !== currentSlug) {
      currentSlug = slug;
      mount();
      if (button) setState('idle');
    }
  }

  mount();
  currentSlug = slugFromUrl();
  setInterval(watch, 800);
})();
