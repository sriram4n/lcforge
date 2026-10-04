## Install

| Your computer | Download | |
|---|---|---|
| **Windows 10 / 11** | `LCForge-{version}-windows-x64.msi` | Can't install software on this PC? Take `LCForge-{version}-windows-x64-portable.zip`, unzip it anywhere, run `LCForge.exe`. |
| **Mac, Apple silicon** (M1 and later) | `LCForge-{version}-macos-arm64.dmg` | Open it and drag LCForge into Applications. |
| **Mac, Intel** | `LCForge-{version}-macos-x64.dmg` | |
| **Ubuntu 22.04+, Debian 12+** | `LCForge-{version}-linux-x64.deb` | `sudo apt install ./LCForge-{version}-linux-x64.deb` |
| **Other Linux** | `LCForge-{version}-linux-x64.tar.gz` | Unpack it, run `LCForge/bin/LCForge`. |
| **Anything with Java 17+** | `LCForge-{version}.jar` | `java -jar LCForge-{version}.jar` |

Everything except the jar carries its own Java, so there is nothing else to install.

**The first launch asks for a click.** These builds are not code-signed (that costs a yearly fee), so:

- **Windows** may say *"Windows protected your PC"*. Choose **More info → Run anyway**.
- **macOS** may say the app *"cannot be opened"* or *"could not be verified"*. Open **System Settings → Privacy & Security**, scroll down, and choose **Open Anyway** next to LCForge. You only do this once.

**The Chrome extension** (optional: it adds a button to LeetCode problem pages that sends the problem to LCForge): unzip `LCForge-chrome-extension-*.zip`, open `chrome://extensions`, turn on **Developer mode**, choose **Load unpacked** and pick the unzipped folder.

`SHA256SUMS.txt` lists every file's checksum. LCForge is an independent project, not affiliated with LeetCode.
