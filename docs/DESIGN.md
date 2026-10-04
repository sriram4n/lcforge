# How LCForge works, and why

The README says how to install and use LCForge. This is the other half: what each part does, and
the reasoning behind the decisions that are not obvious from the code.

---

## Why this exists

LeetCode's own debugger is a Premium feature, and even that one only shows you values where you
stop. When your solution returns `[]` instead of `[1,2]`, you are left adding print statements.

LCForge records the whole run instead. For a two-sum with an off-by-one loop bound, the timeline
shows the loop going `i=0`, `i=1`, and then jumping straight to `return new int[]{}` — never
reaching `i=2`. The bug is visible in one glance rather than three print-and-rerun cycles.

## Getting a problem in

**The one-click way — the Chrome extension.** A small, faded **⚡** sits in the bottom-right corner
of every leetcode.com problem page. Click it and the problem opens in LCForge with its examples
already filled in.

The button is a bare glyph — no background, no border — sitting at low opacity so it stays out of
your way, and brightening on hover. It pulses while working, turns a green **✓** when the problem
lands, and a red **⚠** if something went wrong (hover it, or read the note it shows, for why).

The extension calls LeetCode's own GraphQL endpoint — the same request the page makes to render
itself — rather than scraping the DOM, so it survives their redesigns. It reads only what your
browser is already showing you; nothing Premium-gated is involved.

It pulls three things: the statement, `exampleTestcaseList` (LeetCode's own example **inputs**,
already one-argument-per-line), and `metaData` (the real method signature). Expected **outputs**
only exist in the statement prose, so those are parsed out of it and matched to the inputs **by
content, not by position** — when LeetCode lists an example the statement never spells out,
position-pairing would slide every later case onto someone else's answer. Anything left unmatched
arrives blank rather than confidently wrong.

**Who talks to LCForge.** The content script reads the problem, but it never contacts LCForge
itself: it hands the problem to the extension's background worker, which does. Since Chrome 142, a
request from a web page to the user's own machine needs the user to grant that site "Local Network
Access", and a content script's requests count as the page's. An extension worker holding host
permission for the address is outside that rule — so the button works without anyone having to let
leetcode.com reach into their computer, and LCForge's API gate does not let leetcode.com in at all.
The worker finds LCForge by asking ports 7070–7079 for `/api/health` at once (a closed port refuses
in a millisecond) and takes the lowest that answers as LCForge. Whatever comes back from those ports
is treated as untrusted — any program could be listening on one — so nothing from a reply is ever
rendered as HTML on LeetCode's page, and the tab it opens is built by the extension, not taken
from the reply.

The signature is shown above the editor, e.g. `int[] twoSum(int[] nums, int target)`, so you can
see what each line of the input box maps to.

**The paste way.** Copy the problem text (title, description, Example blocks) into the box on the
home page and press Import. The parser pulls out the title, difficulty, statement and every
`Input:`/`Output:` pair, turning `nums = [2,7,11,15], target = 9` into LeetCode's
one-argument-per-line format.

## Run and Submit

| | |
|---|---|
| `Ctrl`+`Enter` | **Run** — the case you are looking at |
| `Ctrl`+`Shift`+`Enter` | **Submit** — every case, judged |
| `Ctrl`+`S` | Save (it also autosaves as you type) |
| **🐞 Debug** | Trace the selected case and open the timeline |
| `?` | Every shortcut, and About |

The split is the whole economy. **Run can never pay**: it is the fast loop, and a payment for
pressing it would make holding Ctrl+Enter the most profitable thing in the app. **Submit** runs
every non-blank case and is the only thing that can come back *Accepted* — and the only thing that
can pay. That rule is said exactly once, in the record's `ok` flag
(`submit && passed == total && total > 0`), and everything downstream — the payment, the solved
count, the library's status dot, the acceptance rate — reads that one flag. A request with no
`mode` is a Submit, so old clients and old history keep their meaning.

A blank case you added and never filled in is left out of a Submit rather than judged: a case that
does not exist should not be able to block *Accepted* forever.

The **Attempts** tab beside the statement lists every Run and Submit for the problem, newest first,
with its verdict and runtime — so you can see which Submit was the one that was finally accepted.

## The timeline

| | |
|---|---|
| `j` / `k` or `←` `→` | Step one line |
| `space` | Play / pause |
| `n` / `p` | Jump to the next / previous variable change |
| `Home` / `End` | First / last step |

Variables that changed on the current line are highlighted. Lines marked down the gutter were
executed at least once during the run — lines left unmarked never ran, which is often the answer by
itself. Linked lists and binary trees are drawn as diagrams from the actual runtime values.

**The code pane in the timeline is a real editor.** Find the bug, fix it where you found it, press
`Ctrl`+`Enter` to re-trace the same case. No walking back to the other screen to change one
character.

The moment you type, the highlights are describing code that no longer exists — line 12 of a file
that has since gained three lines is not line 12 any more. So a bar appears saying the timeline is
showing the previous run, and the marks drain to grey until you re-trace. Edits save to the
workspace either way, so nothing is lost if you wander off.

## The editor

**Comparison modes** (top-right of the editor): `exact`, `any order` for problems where grouping
order does not matter, and `float ±1e-5`. If your answer has the right values in the wrong order,
exact mode says so explicitly instead of just failing — it is a wrong answer, but you are told why.

**Autocomplete.** Type a `.` and you get the real members of whatever is in front of it, with
generics resolved: after `Map<String, List<Integer>> groups`, `groups.get(k).` offers `List`'s
methods, and `.get(0).` after that offers `Integer`'s. It follows local variables, parameters,
fields, array indexing (`grid[0].` is still an array, so `.length`), chained calls, `var`, `this`,
`ListNode`/`TreeNode`, and helper classes you declared in the same file.

`Ctrl`+`Space` asks for it anywhere. With no dot in front, you get your locals **with their types**,
the members of the class you are in, the type names LCForge knows, and snippets — `fori`, `fore`,
`ifel`, `sout`, `tryc`, plus `hm` `hs` `al` `dq` `pq` `bs` `memo` `dfs` for the structures you write
constantly. Picking a name that needs an import adds the import line at the top in the same
keystroke, which matters here because LeetCode's editor imports `java.util` invisibly and LCForge
compiles what you actually wrote.

**Shift+Alt+F formats the file.** There is no Java formatter in the JDK and adding one would mean
adding a dependency, so this is a small one written here: it scans the source to find out which
characters are actually code — never the inside of a string or a comment — and then fixes
indentation, blank-line runs, spacing after commas and semicolons, braces, and the operators whose
meaning is unambiguous (`=`, `==`, `!=`, `<=`, `>=`, `&&`, `||`, `+=`, `->`). It is registered as a
Monaco formatting provider, so the right-click menu and the command palette reach it too, and the
whole file changes in one undo step.

It deliberately leaves `<`, `>`, `+`, `-`, `*` and `/` alone, because those need a grammar to read:
`<` is comparison *and* generics, `>>` closes `List<List<Integer>>` as often as it shifts, and `*`
ends `import java.util.*`. It also never moves code between lines. **Before returning, it compares
the tokens of its own output against the input and hands back your original untouched if they
differ by so much as a character** — whitespace is all it is allowed to change, so anything else is
a bug in the formatter, and a bug in the formatter must not become a bug in your solution.

**Long lines wrap** — in the editor, in the statement, and in the example blocks. Three panes share
the width here, so long lines are the norm rather than the exception, and a horizontal scrollbar
hides text you have no reason to suspect is missing. Wrapped code is indented to its own depth plus
one step, so a split line still reads as one statement.

**Resizable panes.** Every divider drags: statement vs editor, editor vs test panel, and code vs
inspector in the timeline. Sizes are remembered per divider. Double-click any divider to reset it.

**Captured expected values.** Add a case, type an input, click away — LCForge runs your current
solution on it and fills in the expected output. There is also a **⤓ Capture** button to re-take it.

This is a *regression baseline, not ground truth*: it records what your code does today so you
notice when that changes. If the solution is wrong for that input, it will happily capture the
wrong answer — which is why every captured value is badged **⚠ captured — verify** until you
either check it or type your own. Use it for edge cases you invent (empty array, single element,
duplicates, overflow) once the solution already passes the real examples, so a later refactor
cannot quietly break them.

**The diagrams show up.** A grid, a linked list or an overlap figure is often most of the problem,
so the statement renders LeetCode's images — loaded straight from their CDN and **never copied onto
your disk**. The statement goes through an allowlist sanitizer: it is parsed into a detached
document, only known-safe tags survive, every attribute is stripped, and each image is rebuilt from
scratch with only its `src` (and only from LeetCode's own upload hosts) and `alt`. Nodes are moved
rather than re-serialised, so there is no second parse for a mutation-XSS payload to hide in. They
are drawn on a white ground in every palette because that is the paper they were drawn against —
black line art on a near-black page is invisible ink. Offline, each one says so instead of leaving
a hole.

## The ember

There is a small fire perched on the bottom-right corner of the editor, in no box of its own. Solve
something and it is fed; ignore it for a week and you come back to a grey cinder sitting in its own
smoke. Hover it and a pill unfolds with its mood, its heat and what you can spend; click it to pin
that open. Feed it and it swells, settles, and throws a shower of hot chips — bigger for a bigger
meal, because the animation is the receipt.

**A problem pays once, ever.** 12⚡ for an Easy, 30 for a Medium, 70 for a Hard, half again as much
if your very first Submit on it was Accepted. A problem you have already been paid for pays nothing
however many times you submit it again, and neither does a Run, a compile failure, a partial pass,
or a submission with no testcases. The rule is deliberately blunt: a tool whose whole purpose is to
get you solving problems must never pay you for operating the tool, and any softer rule — a daily
re-solve rate, a small consolation for a near miss — makes holding a key down the most profitable
thing in the app. The first payment is a moment: the verdict card says *Accepted*, the ember jumps,
and the editor fills with sparks. Submitting it again says *already paid for this one*, so a
claimed problem never reads as the currency being broken.

The consequence is worth saying out loud: **the only income is a problem you have not solved yet.**
Work through everything you have imported and the ember will eventually go cold and stay cold. It
cannot die, so nothing is lost, and the way to relight it is the thing you came here to do.

The ember loses 20 heat a day and holds 100, so it runs five days from full and costs about 13⚡ a
day to keep lit. One Easy problem buys a day, one Hard buys five. Solve two or three new problems a
week and it stays bright with money left over — it is there to reward the habit, not to tax it.

**It cannot die.** Heat floors at zero and it looks terrible, but it is always one meal from fine.
A pet you can lose for good is a great motivator for three weeks and then, on your first bad week,
a reason never to open the app again.

**The fuel** runs from a 4⚡ Kindling Splinter to a 55⚡ Anthracite Block, priced so that saving up is
the rational move. The rest of the shelf is more interesting: **Midnight Oil** halves decay for a day,
**Memory Leak** is the best rate on the board if — and only if — you come back tomorrow, the
**Heisenbug Truffle** will not be touched while you are watching, and **Garbage** appears only once
things are already bad, because with no death there has to be a way back. The **Null Pellet** is free
and does nothing.

Spare Sparks buy hats, which do nothing and never will, and streak freezes, which do.
**Hovering a hat puts it on the ember** — dimmed if you do not own it yet — because an unworn hat is
a name and a price until you can see it on the thing that would wear it, and nobody spends 200 Sparks
on a noun.

**Poke it.** Clicking the ember — the small one on the editor or the big one on the library — gets
one of nine answers, rolled fresh each time and weighted so the good ones stay rare: it wobbles,
squishes, hops, giggles, shakes, sneezes a shower of chips, gasps with its mouth open, spins on the
spot, or — once in a while — lands a full backflip. It never rolls the same one twice running, which
is the small lie that makes a random thing feel random. Prod it five times in a row and it goes
dizzy, the one reaction you cannot roll and have to earn by being annoying. Nothing about the pet
changes; it just noticed you.

Going *near* it counts too. A hover gets a much smaller greeting — a blink, a perk, a sway — and
never two inside a second, because a pointer crossing the card on its way to the Feed button is not
a conversation. Left alone it blinks and shifts by itself every few seconds, on no schedule you
could predict.

The ember is drawn from the palette rather than from a picture: its whole mood is one `--heat`
variable mixing `--accent` toward `--fg-faint`, so it is amber on Forge, indigo on Midnight and a
sooty orange on Paper with no second drawing anywhere. Not one colour lives in the artwork — even
the gradients declare stops that the stylesheet paints, so switching palette repaints the creature
mid-animation. Light falls off rather than ending at an edge, the flame has a soft silhouette under
a crisp one, the tip goes ashen before the base does, and the eyes have a highlight, because a dot
with a highlight is looking at you and a dot without one is a hole. It flares when everything
passes, flinches at a compile error, and leans toward wherever your cursor is.

Every one of these is inside `prefers-reduced-motion: no-preference`. Ask your machine for stillness
and the creature is simply still.

The website's front page shows the same creature, running the same code: `web/pet.js` loads with
nothing but `dom.js` and `svg.js`, and its styles sit between `@ember` markers in `styles.css`,
which `site/build.sh` lifts out. Change the ember and the next deploy of the site shows the change.

**Your year** is on the library page — a square per day, plus a streak count in the corner that
counts *showing up*, not winning.

## Palettes

Eighteen of them, picked from the swatch at the top-right: thirteen dark (Forge, Midnight, Nord,
Gruvbox, GitHub Dark, OLED, Dracula, Tokyo Night, Catppuccin Mocha, One Dark, Monokai, Rosé Pine,
Solarized Dark) and five light (Paper, GitHub Light, Solarized Light, Catppuccin Latte, Rosé Pine
Dawn). **Match system** follows your OS between a dark favourite and a light one, and switches live
when the OS does.

Each palette declares about twenty colours in one CSS block and everything else is mixed from those
with `color-mix()` relative to `--fg` and `--bg`, so a light palette inverts on its own. The editor,
the structure diagrams and every badge and border follow along. Syntax colours are each scheme's
official ones; the colours that carry meaning — pass, fail, warning — were nudged along their own
hue until they reach at least 3:1 against their background, which is why a few differ by a shade
from the published scheme. Your choice is remembered and applied before the first paint
(`boot.js`), so it never flashes the default first.

**Hovering a card puts that palette on the whole screen**, and moving away puts back the one you
had; nothing is saved until you click. The miniature on each card is drawn from that palette's own
variables — the blocks are keyed on the `data-palette` attribute alone, so stamping it on any
element re-declares the palette inside it — rather than from a copy of its colours in JavaScript, so
it cannot drift from the real thing. The website's theme swatches use the same trick.

## Your files

Everything LCForge saves lives in one data folder: solutions as ordinary `.java` files in
`workspace/<problem-slug>/Solution.java`, problems, history and the ember as JSON under `data/`.
Open the solutions in an IDE if you like; LCForge reads and writes the same files.

| Running | Data folder |
|---|---|
| Installed, Windows | `%LOCALAPPDATA%\LCForge` |
| Installed, macOS | `~/Library/Application Support/LCForge` |
| Installed, Linux | `$XDG_DATA_HOME/lcforge`, else `~/.local/share/lcforge` |
| From a source checkout | the checkout itself |

`--home DIR`, `-Dlcforge.home` or `LCFORGE_HOME` choose another. Help → About shows the one in use.
One copy of LCForge owns a data folder at a time: a second launch on the same folder finds the
first (a lock on `home/.lock`, its port in `home/.port`) and just opens a browser tab on it.

`ListNode` and `TreeNode` are on the classpath already (in `runtime/`), exactly as LeetCode
defines them, so pasted solutions compile unmodified.

## How it works

**No generated harness.** Your `Solution.java` is compiled as-is and called by reflection, reading
the parameter types off the method itself. So `List<List<Integer>>` survives erasure, there is no
templated glue code to debug, and the code you step through in the timeline is exactly the code you
wrote — no wrapper frames.

**Autocomplete by reflection.** Monaco ships a Java tokenizer — colours, no knowledge — so the
knowledge comes from the server. It reads the receiver expression backwards out of the buffer
(backwards, because that is the direction where a `)` unambiguously has a matching `(` and half-
written code still parses), resolves it to a type, and reflects on that type for its members. The
JVM doing the reflecting is the same JDK your solution compiles against, so the member list is not
a hand-written table that drifts out of date: it is the truth, generics included. Type arguments
are substituted through method returns, which is what lets a chain keep its element type instead of
collapsing to `Object`.

**A persistent runner JVM.** Starting a JVM costs ~80ms. Fine once, fatal for a stress-testing
loop, so one child JVM stays alive and loads each recompile through a fresh classloader. Being a
separate process also means a runaway solution can simply be killed — there is no safe way to stop
a hung thread in-process.

**JDI for the trace.** The solution runs in a debuggee JVM under `com.sun.jdi`, single-stepping
with exclusion filters for the JDK and LCForge's own plumbing. (Class *inclusion* filters are ANDed
by JDI, so more than one matches nothing — exclusions are additive and are what actually works
here.) Values are read by walking object fields directly; calling `toString()` over JDI would
resume the debuggee and corrupt the step sequence.

Traces stop at 25,000 steps or 15 seconds and say so. For heavy DP over large inputs, trace a
smaller input.

## Who may call the API

LCForge compiles and runs whatever Java it is handed, so "it only listens on 127.0.0.1" is not a
security boundary on its own: every web page you open is also running on your machine, and any of
them can send requests to localhost. Four checks stand between a page and `/api/run`, each closing
a different door:

1. **Host** must be a loopback name with our port. A page that repoints its own hostname at
   127.0.0.1 (DNS rebinding) still sends its own name, so it is refused.
2. **Origin** must be LCForge's own page, or — for `/api/import` and `/api/health` only — the
   browser extension (`chrome-extension://` and a 32-letter id). Absent means not a browser at all,
   which is fine: anything that can run curl on this machine can already run Java on it.
3. **Method**: a plain GET reaches only endpoints that read. An image tag cannot delete anything.
4. **Content-Type** must be `application/json` on a POST, which no cross-site page can send
   without a CORS preflight that only the extension is ever granted.

Every response also carries `X-Frame-Options: DENY`, `frame-ancestors 'none'`, `nosniff` and
`no-referrer`, and the page carries a Content-Security-Policy with no inline script and
`connect-src 'self'` — the second wall, should anything ever get past the statement sanitizer.
`test/GateTest.java` replays the attacks with forged headers over raw sockets.

## Packaging

`build.sh` / `build.ps1` compile with `javac --release 17` and pack the interface and the two
runtime sources into one jar, `dist/lcforge.jar`; `--monaco DIR` bundles the code editor too, so it
works offline. Run from the jar, LCForge serves its interface from the classpath and unpacks
`ListNode.java`/`TreeNode.java` into the data folder.

The installers are built by `.github/workflows/release.yml` on a version tag, never on a laptop:

- **The editor** is fetched from npm and checked against its published sha512 before it is built
  into anything.
- **The runtime** is a jlink image of exactly the modules LCForge uses: the five jdeps finds, plus
  `jdk.compiler` (javac's implementation) and `jdk.jdwp.agent` (the debuggee's agent), which are
  loaded by name and invisible to static analysis. It keeps `bin/java` — jpackage's own jlink would
  strip it, and the judge and the debugger start it for every run — and carries a CDS archive so
  those JVMs start faster.
- **Every app is smoke-tested before it is packaged** (`packaging/smoke.py`): start it on a free
  port with an empty data folder, import a problem, Submit it, trace it, and check a cross-site
  request is refused. An installer whose bundled Java cannot compile, judge or debug never gets built.
- Windows gets an `.msi` and a portable `.zip`; macOS a `.dmg` each for Apple silicon and Intel;
  Linux a `.deb` (built inside Ubuntu 22.04, so its dependencies use package names that newer
  releases still answer to) and a `.tar.gz`. All of them land on a **draft** release, with
  checksums, for a human to publish.

The icons are drawn by `packaging/IconGen.java` from the same geometry as `web/icon.svg`.

## Tests

```bash
./verify.sh        # or .\verify.ps1 on Windows
```

Eight suites, 307 checks:

- **CodecTest** — every supported type round-tripped, including LeetCode's tree format where
  `null` does *not* reserve slots for absent children.
- **ImportHtmlTest** — the extension's import path: Example blocks mined out of statement HTML,
  inputs matched to outputs by content (including the misalignment case above), and signature
  parsing.
- **CompleteTest** — completion is mostly type resolution, so that is what it checks: generic
  chains, array indexing, static vs instance receivers, `var`, `this`, buffer-declared classes, and
  the cases where the right answer is *nothing* — an unknown receiver, or a cursor inside a comment
  or a string. Plus a junk-input pass: malformed source is the normal case, and it must never throw.
- **AttemptLogTest** — the append-only practice log: that ten thousand records survive a reload,
  that a line torn by a kill mid-append costs one attempt rather than the whole file, that two runs
  a millisecond either side of midnight land on different days, that a streak is not broken at 9am
  merely because you have not practised *yet* — and the Run/Submit split: a passing Run is never
  `ok`, a record with no `mode` is a Submit, and acceptance divides by submissions.
- **EmberTest** — the pet's arithmetic, which is the kind that fails silently. Decay is exactly
  20/day and idempotent per read (a pet that starves faster while you watch it is a bug and a small
  betrayal); Midnight Oil halves only its own window; and the fuel curve is asserted to reward
  saving up, so one Easy problem is always worth more than a day of decay. The award gates get
  their own block, because every one of them is a way to mint currency by pressing a button: a
  Run, a compile failure, a partial pass, a submission with **no testcases at all** (0 of 0 passed
  is vacuously "all of them"), a second payment for the same problem, and a ledger that forgets
  across a restart.
- **GoldenTest** — eight problems covering the risky type surface (`ListNode`, `TreeNode`, `void`
  in-place mutation, `List<List<String>>`, `double`, `int[][]`), each run with a correct solution
  *and* a realistically buggy one. Asserting the buggy version **fails** matters as much as the
  pass: it is what catches an over-lenient judge.
- **TracerTest** — a live JDI trace, asserting it contains only your frames and that locals and
  change-detection are populated.
- **GateTest** — the API gate, attacked the way a hostile page or a DNS-rebinding attack would:
  cross-site POSTs in every content type, forged Host headers, `Origin: null`, image-tag GETs, a
  look-alike extension id; and every legitimate path, so the gate is shown to open as well as shut.

## What is next

- **Stress testing** — write a slow-but-obviously-correct brute force; LCForge generates random
  inputs until the two disagree, then *shrinks* the counterexample to the smallest failing case and
  drops you into the timeline with it loaded.
- **Spaced repetition** (FSRS), a pattern-mastery map with drills, XP and streaks.
- **Hint ladder** — nudge → approach → pseudocode → code, via your own Claude / OpenAI / Gemini
  API key, with later rungs costing XP so it stays a last resort.
