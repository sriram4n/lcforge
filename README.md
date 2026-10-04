<p align="center"><img src="packaging/icons/icon-256.png" width="96" height="96" alt=""></p>

# LCForge

**Debug your LeetCode solutions, one line at a time.**

LCForge is a free desktop app for solving LeetCode problems in Java. Bring a problem over from
LeetCode with one click, write your solution in a real editor, **Run** one case or **Submit** them
all — and when something fails, **step through every line it executed** and watch your variables
change.

Everything runs on your own computer. No account, no cloud, no telemetry.

## Download

The newest version is on the [Releases page](../../releases/latest).

| Your computer | Take |
|---|---|
| Windows 10 / 11 | `LCForge-…-windows-x64.msi` — or the `-portable.zip` if you can't install software: unzip it, run `LCForge.exe` |
| Mac with Apple silicon (M1 and later) | `LCForge-…-macos-arm64.dmg` |
| Mac with an Intel chip | `LCForge-…-macos-x64.dmg` |
| Ubuntu 22.04+ / Debian 12+ | `LCForge-…-linux-x64.deb` — `sudo apt install ./LCForge-…-linux-x64.deb` |
| Other Linux | `LCForge-…-linux-x64.tar.gz` — unpack it, run `LCForge/bin/LCForge` |
| Anything with Java 17+ | `LCForge-….jar` — `java -jar LCForge-….jar` |

Java comes built into everything except the jar.

**The first launch asks for one extra click,** because the builds aren't code-signed. On Windows,
at *"Windows protected your PC"* choose **More info → Run anyway**. On macOS, open
**System Settings → Privacy & Security** and choose **Open Anyway** next to LCForge.

## Getting problems in

- **The Chrome extension** puts a ⚡ on every LeetCode problem page. Press it, and the problem — its
  statement, its examples and the exact method signature — opens in LCForge, ready to solve. To
  install it, download `LCForge-chrome-extension-….zip` from the release and unzip it, open
  `chrome://extensions`, switch on **Developer mode**, choose **Load unpacked** and pick the folder.
  (From a source checkout, pick the `extension/` folder itself.)
- **Or paste** a problem's text into the box on LCForge's home page.

## What's inside

- **Run and Submit, judged like LeetCode** — Accepted, Wrong Answer or Time Limit Exceeded, with
  every case's runtime side by side.
- **A real step-through debugger**, built on the JVM's own debugging interface: scrub a timeline of
  every step and watch arrays, maps, linked lists and trees change.
- **A proper editor** (Monaco, the one inside VS Code) with Java autocomplete that knows your types,
  a formatter, and panes you can resize.
- **An ember** that lives on your progress. Your first Accepted on a problem pays Sparks to keep it
  fed; it dims while you're away. It never dies.
- **Eighteen themes**, light and dark — or let it follow your system.

Press `?` inside the app for every shortcut.

## Your data

Everything LCForge saves lives in one folder on your computer. Help → About in the app shows where:

| | |
|---|---|
| Windows | `%LOCALAPPDATA%\LCForge` |
| macOS | `~/Library/Application Support/LCForge` |
| Linux | `~/.local/share/lcforge` |

Your solutions are plain `.java` files you can open in any editor. Uninstalling LCForge leaves this
folder alone; delete it to remove your work too.

## Run it from source

All it needs is a JDK, version 17 or newer. No Maven, no Gradle, no npm, no libraries.

```bash
./run.sh
```

On Windows, `.\run.ps1`. Either one compiles LCForge, starts it on <http://localhost:7070> and opens
your browser. Run this way, your data lives in the checkout itself (`data/` and `workspace/`, which
git ignores).

| | |
|---|---|
| `./verify.sh` / `.\verify.ps1` | the test suites: eight of them, 307 checks |
| `./build.sh` / `.\build.ps1` | `dist/lcforge.jar` — the whole app in one file |
| `bash site/build.sh` | the website, assembled into `_site/` |

How it all works, and why it works that way: [docs/DESIGN.md](docs/DESIGN.md).

## Releasing

1. Bump `VERSION` in `src/lcforge/Server.java` and add the version's section to
   [CHANGELOG.md](CHANGELOG.md).
2. Tag it and push the tag: `git tag v1.0.1` then `git push origin v1.0.1`.
3. The **Release** workflow tests everything on Windows, macOS and Linux, builds every installer,
   smoke-tests each one with the Java it carries, and opens a **draft** release with them attached.
   Check it over, then press **Publish**.

The website in `site/` deploys itself to GitHub Pages whenever it changes, and its download
buttons always point at the newest published release. (One-time setup: **Settings → Pages →
Source: GitHub Actions**.)

## Privacy

LCForge never uploads your code. The only things it fetches from the internet are problem diagrams,
straight from LeetCode's own servers, and — for the bare jar only — the code editor, from a CDN.
The extension sends the problem you're looking at to the LCForge app on your own computer, and
nowhere else. The full policy is [site/privacy.html](site/privacy.html), published with the website.

## License

[MIT](LICENSE) © 2026 N. Sriram

---

LCForge is an independent project. It is not affiliated with, endorsed by or connected to LeetCode.
LeetCode is a trademark of its owner.
