// LCForge's background worker: the one part of the extension that talks to the LCForge app.
//
// Why here and not in the content script: since Chrome 142, a request from a web page to this
// computer needs the user to have granted that site "Local Network Access", and a content script's
// requests count as the page's own. An extension's worker holding host permission for the address
// is outside that rule. So the button works without anyone letting leetcode.com reach into their
// machine -- and LCForge's gate does not have to let leetcode.com in at all.
//
// Everything that comes back from these ports is treated as untrusted: any program on this machine
// could be listening on one of them. Only a slug is taken from a reply, and only into a URL built here.

// LCForge listens on 7070, or on the next free port up to 7079. Keep in step with host_permissions.
const PORTS = Array.from({ length: 10 }, (_, i) => 7070 + i);

let forgePort = null;   // remembered while this worker lives; looked for again after

/**
 * Which port LCForge is on. Every candidate is asked at once -- a closed port refuses in a
 * millisecond -- and the lowest one that answers as LCForge wins, so with two copies running (two
 * data folders) the button always reaches the same one.
 */
async function findForge() {
  const answers = await Promise.allSettled(PORTS.map(async port => {
    const res = await fetch(`http://127.0.0.1:${port}/api/health`, { signal: AbortSignal.timeout(1500) });
    const json = await res.json();
    if (json?.app !== 'lcforge') throw new Error('not LCForge');
    return port;
  }));
  const found = answers.filter(a => a.status === 'fulfilled').map(a => a.value).sort((a, b) => a - b);
  if (!found.length) throw new Error('LCForge is not reachable');
  return found[0];
}

async function importProblem(payload) {
  // The remembered port first, unless it has stopped answering (LCForge restarted on another
  // port): then look again, once.
  for (let attempt = 0; attempt < 2; attempt++) {
    const fresh = forgePort === null;
    if (fresh) forgePort = await findForge();
    try {
      const res = await fetch(`http://127.0.0.1:${forgePort}/api/import`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      if (!res.ok) throw new Error(`LCForge returned ${res.status}`);
      const json = await res.json();
      if (!json?.ok) throw new Error(String(json?.error || 'import rejected'));
      return { slug: String(json.slug || payload.slug), port: forgePort };
    } catch (e) {
      const unreachable = e instanceof TypeError;   // fetch's way of saying "no one is there"
      if (!unreachable || fresh) throw e;
      forgePort = null;
    }
  }
  throw new Error('LCForge is not reachable');
}

chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  if (msg?.type !== 'import' || !sender.tab) return false;
  importProblem(msg.payload).then(({ slug, port }) => {
    // Opened from here, not by the page: a tab an extension opens is never caught by a pop-up
    // blocker, however long reading the problem took. localhost, as the app opens it, because your
    // theme and layout are saved per address.
    chrome.tabs.create({
      url: `http://localhost:${port}/#/solve/${encodeURIComponent(slug)}`,
      index: sender.tab.index + 1,
    });
    reply({ ok: true });
  }, e => {
    const offline = e instanceof TypeError || /reachable/i.test(String(e?.message));
    reply({ ok: false, offline, error: String(e?.message || e) });
  });
  return true;   // the reply comes later
});
