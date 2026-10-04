// A Java formatter, in about two hundred lines.
//
// Not a parser. It scans the source once to find out which characters are *code* — everything
// inside a string, a char literal or a comment is set aside and never touched — and then makes
// whitespace decisions from the brace structure of what is left. That is enough to fix the thing
// that actually goes wrong with pasted LeetCode solutions, which is indentation, without needing a
// grammar.
//
// What it deliberately does NOT do: space out operators. `Map<String,Integer>` and `a<b`, `i--` and
// `x - -y`, `->` and `-` are the same characters in different roles, and telling them apart is a
// parser's job. A formatter that turns `Map<String,Integer>` into `Map < String, Integer >` is
// worse than no formatter, so this one leaves operators exactly where it found them.

const INDENT = '    ';                 // four spaces, matching the editor's tabSize
const MAX_CONT = 1;                    // how far a wrapped line may be pushed past its statement

/**
 * Reformat Java source. Returns the input unchanged if it cannot do so safely.
 *
 * The safety net is the last thing this function does and the reason it can be trusted: it compares
 * the *tokens* of the input and the output, and hands back the original if they differ by so much
 * as a character. Whitespace is all this is allowed to change, so anything else is a bug in here —
 * and a bug in here must not become a bug in your solution.
 */
export function formatJava(src) {
  const text = String(src ?? '');
  if (!text.trim()) return text;

  // Text blocks change what a quote means, and getting that subtly wrong would mangle the contents
  // of a string rather than its layout. Refuse the whole file instead of guessing.
  if (text.includes('"""')) return text;

  let out;
  try { out = layout(text); } catch { return text; }

  return signature(out) === signature(text) ? out : text;
}

// ---------- the scanner ----------
//
// One pass, line by line, splitting each into alternating runs of code and not-code. Everything
// downstream works on the code runs only, which is what makes "don't touch the inside of a string"
// true by construction rather than by a pattern that has to remember every case.

function scan(text) {
  const lines = text.split('\n');
  const out = [];
  let inBlock = false;

  for (const line of lines) {
    const parts = [];
    const openedInBlock = inBlock;
    let buf = '';
    let i = 0;

    const flush = () => { if (buf) { parts.push({ code: true, text: buf }); buf = ''; } };

    while (i < line.length) {
      if (inBlock) {
        const end = line.indexOf('*/', i);
        if (end === -1) { parts.push({ code: false, text: line.slice(i) }); i = line.length; }
        else { parts.push({ code: false, text: line.slice(i, end + 2) }); i = end + 2; inBlock = false; }
        continue;
      }

      const c = line[i], d = line[i + 1];

      if (c === '/' && d === '*') {
        flush();
        parts.push({ code: false, text: '/*' });
        i += 2;
        inBlock = true;
        continue;
      }
      if (c === '/' && d === '/') {
        flush();
        parts.push({ code: false, text: line.slice(i) });
        i = line.length;
        continue;
      }
      if (c === '"' || c === "'") {
        flush();
        let j = i + 1;
        while (j < line.length) {
          if (line[j] === '\\') { j += 2; continue; }   // an escaped quote does not end anything
          if (line[j] === c) { j++; break; }
          j++;
        }
        parts.push({ code: false, text: line.slice(i, j) });
        i = j;
        continue;
      }

      buf += c;
      i++;
    }
    flush();
    out.push({ parts, openedInBlock });
  }
  return out;
}

const codeOf = line => line.parts.filter(p => p.code).map(p => p.text).join('');

// ---------- spacing, inside code runs only ----------

// The operators that are safe to space out, and the reason the list stops where it does.
//
// Every one of these is a multi-character sequence that can mean only one thing. The ones left out
// are the ones that cannot be read without a grammar: `<` and `>` are comparison *and* generics,
// `>>` closes `List<List<Integer>>` as often as it shifts, `-` is subtraction and negation and half
// of `--`, and `*` ends `import java.util.*`. Spacing those needs a parser; guessing at them would
// corrupt more code than it tidied.
// The lookarounds are not decoration. `>>=` contains `>=`, and spacing *that* would produce
// `> >=` — two tokens where there was one, a real change to the code that the signature check at
// the bottom of this file cannot see, because stripping whitespace puts it back together again.
// Splitting a token by inserting whitespace is the one way a whitespace-only edit can break Java,
// so every rule here has to refuse to fire in the middle of a longer operator.
const OPERATORS = /\s*(?<![<>=!+\-*/%&|^])(==|!=|<=|>=|&&|\|\||\+=|-=|\*=|\/=|%=|->)(?![=])\s*/g;

// A lone `=` is assignment and nothing else — the lookarounds are what keep it from biting a chunk
// out of `==`, `<=`, `+=` and the rest.
const ASSIGN = /\s*(?<![=!<>+\-*/%&|^])=(?!=)\s*/g;

function respace(t) {
  return t
    .replace(/\s+/g, ' ')                 // runs of whitespace never carry meaning in Java code
    .replace(OPERATORS, ' $1 ')
    .replace(ASSIGN, ' = ')
    .replace(/\s+([,;)\]])/g, '$1')       // no space before a separator or a closer
    .replace(/([(\[])\s+/g, '$1')         // nor just inside an opener
    .replace(/,(?=\S)/g, ', ')            // exactly one after a comma — safe inside generics too
    .replace(/;(?=[^\s);])/g, '; ')       // and after the semicolons of a for-header, but not `;;`
    .replace(/\b(if|for|while|switch|catch|synchronized)\(/g, '$1 (')
    .replace(/\)\{/g, ') {')
    // `)` against a name is always two tokens, never one, so a space here cannot change meaning.
    // It fixes `if (x)return y;` and, for free, every cast: `(int)x` becomes `(int) x`.
    .replace(/\)(?=[A-Za-z0-9_$])/g, ') ')
    .replace(/:\{/g, ': {')
    .replace(/\b(else|try|do|finally)\{/g, '$1 {')
    .replace(/\}(else|catch|finally)\b/g, '} $1');
}

// ---------- layout ----------

function layout(text) {
  const lines = scan(text);
  const out = [];

  let depth = 0;      // brace nesting
  let cont = 0;       // how far the current wrapped statement has been pushed
  const stack = [];   // one entry per open brace: { isSwitch, inCase }
  let blanks = 0;

  for (const line of lines) {
    const code = codeOf(line);

    // A trailing comment keeps exactly one space in front of it. Without this, collapsing the run
    // of spaces before `// note` and then trimming the line would weld it onto the semicolon.
    let rebuilt = '';
    for (const p of line.parts) {
      const t = p.code ? respace(p.text) : p.text;
      if (!p.code && /^\/[/*]/.test(t) && rebuilt.trim() && !/\s$/.test(rebuilt)) rebuilt += ' ';
      rebuilt += t;
    }
    rebuilt = rebuilt.trim();

    if (!rebuilt) {                       // blank, or whitespace only
      blanks++;
      continue;
    }
    // A run of empty lines is a paragraph break however long it is; more than one is just scroll.
    if (blanks && out.length) out.push('');
    blanks = 0;

    const bare = code.trim();
    const top = stack[stack.length - 1];

    // A continuation line inside a block comment keeps its leading star aligned under the opener.
    if (line.openedInBlock) {
      out.push(INDENT.repeat(Math.max(0, depth)) + (rebuilt.startsWith('*') ? ' ' : '') + rebuilt);
      continue;
    }

    // Leading closers belong one level out — they close the block whose body they follow.
    let lead = 0;
    for (const ch of bare) {
      if (ch === '}') lead++;
      else if (ch === ')' || ch === ']' || ch === ';' || ch === ' ') continue;
      else break;
    }

    // `case` and `default` sit at the switch's body level; what follows them sits one deeper,
    // which no amount of brace counting will tell you, because a case label opens nothing.
    let extra = 0;
    if (top?.isSwitch) {
      if (/^(case\b|default\b)/.test(bare)) top.inCase = true;
      else if (top.inCase && lead === 0) extra = 1;
    }

    const level = Math.max(0, depth - lead) + extra + cont;
    out.push(INDENT.repeat(level) + rebuilt);

    // ---- carry the structure forward ----

    // Walk this line's braces in order, so `} else {` both pops and pushes, and so a switch is
    // recognised from the text to the left of the brace that opens it.
    for (let i = 0; i < code.length; i++) {
      if (code[i] === '{') {
        stack.push({ isSwitch: /\bswitch\s*\([^)]*\)\s*$/.test(code.slice(0, i).trim()), inCase: false });
        depth++;
      } else if (code[i] === '}') {
        stack.pop();
        depth = Math.max(0, depth - 1);
      }
    }

    // A statement that has not ended yet pushes its next line in one step — which is what makes a
    // braceless `if (x)` / `return y;` pair and a wrapped condition both come out right.
    //
    // A line with no code on it at all is a comment, and a comment is not an unfinished statement.
    // Left to the rule below, the closing line of a block comment would look exactly like one and
    // shunt everything after it a level to the right. Leave the count alone instead — a comment
    // sitting in the middle of a wrapped expression should not end the wrap either.
    if (bare) {
      const ended = /[;{}:]$/.test(bare) || bare.startsWith('@');
      cont = ended ? 0 : Math.min(cont + 1, MAX_CONT);
    }
  }

  return out.join('\n') + '\n';
}

// ---------- the safety net ----------

/**
 * Everything about the source except its whitespace.
 *
 * Two sources with the same signature differ only in layout, which is the entire permitted scope of
 * this file. Comparing before and after is what lets `formatJava` promise it cannot damage code:
 * if a rule above ever eats a token, the comparison fails and the original is returned untouched.
 */
function signature(text) {
  return scan(text)
    .map(l => l.parts.map(p => p.text.replace(/\s+/g, '')).join(''))
    .join('');
}
