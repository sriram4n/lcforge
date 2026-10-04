// Java completion for the Monaco editor.
//
// Monaco ships a Java *tokenizer* and nothing else — colours, no knowledge. The knowledge lives on
// the server, which resolves the type in front of your cursor and reflects on it. This file is the
// thin part: ask, translate the answer into Monaco's shape, and add the things that need no type
// information at all (keywords, and snippets for the loops you write forty times a week).
//
// If the server can't answer, the keywords and snippets still work. Autocomplete that vanishes
// entirely on a hiccup is worse than autocomplete that gets quieter.

const KIND = m => ({
  method:   m.languages.CompletionItemKind.Method,
  field:    m.languages.CompletionItemKind.Field,
  variable: m.languages.CompletionItemKind.Variable,
  class:    m.languages.CompletionItemKind.Class,
  keyword:  m.languages.CompletionItemKind.Keyword,
  snippet:  m.languages.CompletionItemKind.Snippet,
});

const KEYWORDS = (
  'abstract assert boolean break byte case catch char class continue default do double else enum ' +
  'extends final finally float for if implements import instanceof int interface long new package ' +
  'private protected public return short static super switch this throw throws try void while ' +
  'true false null var record yield'
).split(' ');

// Live templates. The ones below the line are LeetCode-shaped: the four or five structures that
// make up most of a solution, spelled the way you would actually want them.
const SNIPPETS = [
  ['fori',  'for (int ${1:i} = 0; ${1:i} < ${2:n}; ${1:i}++) {\n\t$0\n}',          'counting loop'],
  ['forr',  'for (int ${1:i} = ${2:n} - 1; ${1:i} >= 0; ${1:i}--) {\n\t$0\n}',     'reverse loop'],
  ['fore',  'for (${1:int} ${2:x} : ${3:nums}) {\n\t$0\n}',                        'for-each'],
  ['whi',   'while (${1:cond}) {\n\t$0\n}',                                        'while loop'],
  ['ifel',  'if (${1:cond}) {\n\t$2\n} else {\n\t$0\n}',                           'if / else'],
  ['sout',  'System.out.println($0);',                                             'print'],
  ['soutv', 'System.out.println("${1:x} = " + ${1:x});',                           'print a variable'],
  ['tryc',  'try {\n\t$1\n} catch (${2:Exception} ${3:e}) {\n\t$0\n}',              'try / catch'],

  ['hm',  'Map<${1:Integer}, ${2:Integer}> ${3:map} = new HashMap<>();$0',          'HashMap',
          ['java.util.Map', 'java.util.HashMap']],
  ['hs',  'Set<${1:Integer}> ${2:seen} = new HashSet<>();$0',                       'HashSet',
          ['java.util.Set', 'java.util.HashSet']],
  ['al',  'List<${1:Integer}> ${2:out} = new ArrayList<>();$0',                     'ArrayList',
          ['java.util.List', 'java.util.ArrayList']],
  ['dq',  'Deque<${1:Integer}> ${2:dq} = new ArrayDeque<>();$0',                    'deque / stack / queue',
          ['java.util.Deque', 'java.util.ArrayDeque']],
  ['pq',  'PriorityQueue<${1:int[]}> ${2:pq} = new PriorityQueue<>((a, b) -> ${3:a[0] - b[0]});$0',
          'heap', ['java.util.PriorityQueue']],
  ['bs',  'int lo = 0, hi = ${1:nums.length} - 1;\nwhile (lo <= hi) {\n\tint mid = lo + (hi - lo) / 2;\n'
        + '\tif (${2:nums[mid] == target}) return mid;\n\tif (${3:nums[mid] < target}) lo = mid + 1;\n'
        + '\telse hi = mid - 1;\n}\nreturn $0;',                                    'binary search'],
  ['memo', 'int[] memo = new int[${1:n}];\nArrays.fill(memo, -1);$0',               'memo array',
           ['java.util.Arrays']],
  ['dfs', 'private ${1:int} dfs(${2:int i}) {\n\tif (${3:base}) return ${4:0};\n\t$0\n}',
          'recursive helper'],
];

let registered = false;

export function registerJavaCompletion(monaco) {
  if (registered) return;
  registered = true;

  const kinds = KIND(monaco);
  const snippetRule = monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet;

  monaco.languages.registerCompletionItemProvider('java', {
    triggerCharacters: ['.'],

    async provideCompletionItems(model, position, _context, token) {
      const word = model.getWordUntilPosition(position);
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber: position.lineNumber,
        startColumn: word.startColumn,
        endColumn: position.column,
      };

      let reply = null;
      try {
        reply = await ask(model.getValue(), model.getOffsetAt(position), token);
      } catch {
        // Server down or mid-restart: fall through to the offline set.
      }
      if (token.isCancellationRequested) return { suggestions: [] };

      // Inside a comment or a string literal there is nothing useful to say.
      if (reply && reply.kind === 'suppress') return { suggestions: [] };

      const out = (reply ? reply.items : []).map(it => toSuggestion(monaco, model, it, range, kinds, snippetRule));

      // After a dot, the member list is the whole answer — keywords there would be noise. Anywhere
      // else, add the things that need no type knowledge.
      const afterDot = reply && (reply.kind === 'members' || reply.kind === 'none');
      if (!afterDot) {
        for (const k of KEYWORDS) {
          out.push({ label: k, kind: kinds.keyword, insertText: k, range, sortText: '4' + k });
        }
        for (const [label, body, detail, imports] of SNIPPETS) {
          out.push({
            label,
            kind: kinds.snippet,
            detail,
            insertText: body,
            insertTextRules: snippetRule,
            range,
            sortText: '2' + label,
            additionalTextEdits: importEdits(monaco, model, imports || []),
          });
        }
      }

      return { suggestions: out, incomplete: !reply };
    },
  });
}

function toSuggestion(monaco, model, it, range, kinds, snippetRule) {
  const isSnippet = typeof it.insert === 'string' && it.insert.includes('${');
  return {
    label: it.label,
    kind: kinds[it.kind] || kinds.variable,
    detail: it.detail || '',
    documentation: it.doc || '',
    insertText: it.insert || it.label,
    insertTextRules: isSnippet ? snippetRule : undefined,
    range,
    sortText: it.sort || it.label,
    additionalTextEdits: it.importName ? importEdits(monaco, model, [it.importName]) : undefined,
  };
}

// ---------- imports ----------

/**
 * Adding the import along with the name.
 *
 * LeetCode's editor quietly imports java.util for you, so pasted code that compiles there fails
 * here the moment it mentions a Map. Accepting a suggestion is the natural moment to fix that, and
 * `additionalTextEdits` applies it in the same undo step — the import appears at the top without
 * the cursor ever leaving the line you are typing.
 */
function importEdits(monaco, model, names) {
  const edits = [];
  const text = model.getValue();
  const lines = model.getLinesContent();

  // Where a new import belongs: after the last one, else after the package line, else the top.
  let insertAfter = 0;
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i].trim();
    if (t.startsWith('import ')) insertAfter = i + 1;
    else if (t.startsWith('package ') && insertAfter === 0) insertAfter = i + 1;
    else if (t.startsWith('class ') || t.startsWith('public class ')) break;
  }
  const hadImports = lines.some(l => l.trim().startsWith('import '));

  for (const name of names) {
    const pkg = name.slice(0, name.lastIndexOf('.'));
    const already =
      new RegExp(`^\\s*import\\s+${escapeRe(name)}\\s*;`, 'm').test(text) ||
      new RegExp(`^\\s*import\\s+${escapeRe(pkg)}\\.\\*\\s*;`, 'm').test(text);
    if (already) continue;

    edits.push({
      range: new monaco.Range(insertAfter + 1, 1, insertAfter + 1, 1),
      text: `import ${name};\n` + (hadImports || edits.length ? '' : '\n'),
    });
  }
  return edits.length ? edits : undefined;
}

const escapeRe = s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

// ---------- the server ----------

let inFlight = null;

async function ask(code, offset, token) {
  if (inFlight) inFlight.abort();
  const ctl = new AbortController();
  inFlight = ctl;
  token.onCancellationRequested(() => ctl.abort());

  try {
    const res = await fetch('/api/complete', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code, offset }),
      signal: ctl.signal,
    });
    if (!res.ok) throw new Error(res.statusText);
    return await res.json();
  } finally {
    if (inFlight === ctl) inFlight = null;
  }
}
