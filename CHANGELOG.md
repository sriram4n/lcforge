# Changelog

Every version of LCForge, newest first. Each version's section here is the text of its GitHub
release — the release workflow copies it from this file — so write it for the people installing it.

## [1.0.0] — 2026-10-03

The first public release: LCForge as an app you install, rather than a project you run.

### New

- **Installers** for Windows (`.msi`, plus a portable `.zip`), macOS (Apple silicon and Intel) and
  Linux (`.deb` and `.tar.gz`), each carrying its own trimmed Java — nothing else to install. The
  app opens your browser by itself, finds a free port if 7070 is taken, keeps one copy running per
  data folder, and shows a small window you quit it from.
- **Run and Submit.** Run tries the case you're looking at; Submit judges them all. Only an
  Accepted Submit can pay Sparks, so pressing Run never costs you a problem's one payment.
- **The Accepted moment.** A verdict card with every case's runtime — and the first time a problem
  is solved, the ember jumps and the editor fills with sparks.
- **A LeetCode-shaped solve page:** Description and Attempts tabs beside the code; Testcase and
  Result tabs beneath it; Run, Submit and Debug in the console bar.
- **The library as a problem table** — search it, filter it by status and difficulty, sort it by
  title, difficulty, acceptance or best trace.
- **Eighteen themes**, thirteen dark and five light, with a picker that previews each one on hover,
  and *Match system* to follow your computer's dark mode.
- **A livelier ember:** smoother rendering, nine different reactions to a poke (and a dizzy spell if
  you overdo it), greetings, and blinks of its own when left alone.
- A welcome for your first run, a `?` sheet with every shortcut, an About panel, and a banner that
  says so — and reconnects by itself — if LCForge stops.
- **The website,** with downloads, a working miniature of the app and the privacy policy.

### Security

- The local server accepts requests only from LCForge's own page and its browser extension. Other
  websites — DNS-rebinding attacks included — are refused, so no page you visit can ask LCForge to
  compile or run anything.
- Problem statements pass through an allowlist sanitizer before they are shown.
- The interface runs under a strict Content-Security-Policy, with no inline script.

### Changed

- **The Chrome extension (1.1.0)** now reaches LCForge from its own background worker. It finds the
  app on any port from 7070 to 7079, and Chrome's new "Local Network Access" prompt never has to
  ask whether leetcode.com may reach your computer. Install it fresh from this release.
- An installed LCForge keeps your work in your system's app-data folder; Help → About shows where.
