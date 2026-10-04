// Problem statements: LeetCode's HTML, made safe to put on a page that can run code.
//
// This page is not an ordinary page. Anything that executes in it can call /api/run, and /api/run
// compiles and runs whatever Java it is given — so a script in a problem statement is not a
// cosmetic bug, it is code execution on your machine. Statements arrive from the extension (that is,
// from leetcode.com) and from paste, so this module treats every one of them as hostile.
//
// The previous version stripped `<script>` with a regular expression, and that has a hole the size
// of a door: `<iframe srcdoc="&lt;script&gt;...">`. The script is entity-encoded, so no pattern over
// the raw text sees it; the iframe decodes it at load and runs it with this page's own origin. No
// list of bad things closes that class of bug. An allowlist does: parse, then keep only what is
// known to be inert, and rebuild the few elements that need attributes at all.

/** LeetCode statements arrive as HTML; pasted ones are plain text. */
export function isHtml(s) {
  return /<\/?(p|div|ul|ol|li|pre|code|strong|em|b|i|br|sup|sub)\b/i.test(String(s || ''));
}

/**
 * Build the statement element. Plain text keeps its line breaks; HTML is sanitized into live DOM.
 *
 * The sanitized nodes are moved into the page rather than serialised back to a string and parsed
 * a second time. Re-parsing is where "mutation XSS" lives — markup that is harmless as written and
 * becomes something else when the browser re-reads its own serialisation — so it never happens.
 */
export function renderStatement(text, mk) {
  const raw = String(text || '');
  if (!isHtml(raw)) {
    return mk('div', { class: 'statement plain' }, raw);
  }
  const box = mk('div', { class: 'statement rich' });
  // DOMParser builds an inert document: no scripts run, no images load, no frames open. Nothing in
  // it can act until we decide to move it into the live page, and by then it has been cleaned.
  const doc = new DOMParser().parseFromString(raw, 'text/html');
  clean(doc.body);
  tidy(doc.body);
  box.append(...[...doc.body.childNodes]);
  return box;
}

// Removed with everything inside them — their content is code, styling or a way out of the page.
const DROP = new Set([
  'SCRIPT', 'STYLE', 'IFRAME', 'FRAME', 'FRAMESET', 'OBJECT', 'EMBED', 'APPLET', 'PORTAL',
  'FORM', 'INPUT', 'BUTTON', 'TEXTAREA', 'SELECT', 'OPTION', 'LINK', 'META', 'BASE', 'TITLE',
  'SVG', 'MATH', 'TEMPLATE', 'NOSCRIPT', 'VIDEO', 'AUDIO', 'SOURCE', 'TRACK', 'CANVAS', 'DIALOG',
]);

// Kept, with every attribute removed. The whole of what a problem statement legitimately uses.
const KEEP = new Set([
  'P', 'DIV', 'SPAN', 'BR', 'HR', 'UL', 'OL', 'LI', 'PRE', 'CODE', 'KBD', 'SAMP', 'VAR',
  'STRONG', 'B', 'EM', 'I', 'U', 'S', 'DEL', 'INS', 'MARK', 'SUP', 'SUB', 'SMALL', 'BIG',
  'BLOCKQUOTE', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'DL', 'DT', 'DD',
  'TABLE', 'CAPTION', 'THEAD', 'TBODY', 'TFOOT', 'TR', 'TD', 'TH',
  'A', 'IMG', 'FONT',
]);

// The one class LeetCode's markup carries that the stylesheet uses: "Example 1:" headings.
const KEEP_CLASS = new Set(['example']);

// Where LeetCode keeps the diagrams. This is the one place the app puts a remote URL into the page,
// so it gets an allowlist rather than a blocklist.
const IMAGE_HOSTS = /^https:\/\/(assets\.leetcode\.com|s3-lc-upload\.s3\.amazonaws\.com)\//i;

function clean(node) {
  for (const child of [...node.childNodes]) {
    if (child.nodeType === Node.COMMENT_NODE || child.nodeType === Node.PROCESSING_INSTRUCTION_NODE) {
      child.remove();
      continue;
    }
    if (child.nodeType !== Node.ELEMENT_NODE) continue;     // text is always safe as text

    const tag = child.tagName.toUpperCase();
    if (DROP.has(tag)) { child.remove(); continue; }
    if (!KEEP.has(tag)) {
      // Unknown but not dangerous-by-name: keep what it says, lose what it is.
      clean(child);
      child.replaceWith(...child.childNodes);
      continue;
    }

    if (tag === 'IMG') { child.replaceWith(safeImg(child)); continue; }

    const href = tag === 'A' ? child.getAttribute('href') : null;
    const span = (tag === 'TD' || tag === 'TH')
      ? [child.getAttribute('colspan'), child.getAttribute('rowspan')] : null;
    const cls = [...child.classList].filter(c => KEEP_CLASS.has(c));

    // Every attribute goes — on*, style, srcset, formaction, xlink:href, the lot — and the few
    // that are needed are put back from values checked above.
    for (const a of [...child.attributes]) child.removeAttribute(a.name);
    if (cls.length) child.className = cls.join(' ');
    if (span) {
      if (/^\d{1,2}$/.test(span[0] || '')) child.setAttribute('colspan', span[0]);
      if (/^\d{1,2}$/.test(span[1] || '')) child.setAttribute('rowspan', span[1]);
    }
    if (tag === 'A') {
      if (href && /^https?:\/\//i.test(href.trim())) {
        child.setAttribute('href', href.trim());
        child.setAttribute('target', '_blank');
        child.setAttribute('rel', 'noopener noreferrer');
      }
      // Anything else — javascript:, data:, a relative path into our own API — becomes plain text.
    }
    clean(child);
  }
}

/**
 * Rebuild an `<img>` from scratch, keeping only its source and its alt text.
 *
 * Rebuilding rather than filtering is the point: whatever else was on the tag — event handlers,
 * a `srcset` pointing somewhere else, LeetCode's inline `width: 613px` that would shove the problem
 * pane sideways — is gone because it was never copied across, not because a pattern caught it.
 *
 * The image itself is **hotlinked**, never downloaded. Nothing about a problem statement is written
 * to disk beyond the URL that was already stored at import.
 */
function safeImg(src) {
  const url = (src.getAttribute('src') || '').trim();
  if (!IMAGE_HOSTS.test(url)) return document.createTextNode('[image]');

  const img = document.createElement('img');
  img.src = url;
  // LeetCode ships most of these as alt="", which tells a screen reader the picture is decorative.
  // A grid diagram explaining the example is not decorative, so an empty alt becomes a real word.
  img.alt = (src.getAttribute('alt') || '').trim() || 'diagram from the problem statement';
  img.decoding = 'async';
  img.referrerPolicy = 'no-referrer';
  // No `loading="lazy"`: a lazy image parsed outside the document is never observed and never
  // loads. The diagrams fail in one way only — the network is gone — so say that, not nothing.
  img.addEventListener('error', () => {
    const note = document.createElement('span');
    note.className = 'img-missing';
    note.textContent = 'diagram needs the network';
    img.replaceWith(note);
  }, { once: true });
  return img;
}

/**
 * Undo LeetCode's spacing habits, which assume their stylesheet rather than ours.
 *
 *  - An empty paragraph holding a lone &nbsp; is their section spacer; here it is a third blank
 *    line between blocks that already have margins.
 *  - `<pre>` keeps whitespace, so the newline right after the opening tag is a blank first line in
 *    every example block, and the one before the close is a blank last line.
 */
function tidy(root) {
  for (const p of root.querySelectorAll('p')) {
    if (!p.querySelector('img') && !p.textContent.replace(/[\s ]/g, '')) p.remove();
  }
  for (const pre of root.querySelectorAll('pre')) {
    const first = pre.firstChild, last = pre.lastChild;
    if (first?.nodeType === Node.TEXT_NODE) first.textContent = first.textContent.replace(/^[ \t]*\n/, '');
    if (last?.nodeType === Node.TEXT_NODE) last.textContent = last.textContent.replace(/\n[ \t]*$/, '');
  }
}
