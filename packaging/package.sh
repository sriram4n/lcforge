#!/usr/bin/env bash
# Package LCForge for the OS this runs on: trim a Java runtime, build the app around it, PROVE the
# app works, then wrap it as an installer and a portable archive.
#
#   bash packaging/package.sh JAR VERSION OUTDIR
#
# Needs a JDK 17+ (jlink, jpackage) and python3 for the smoke test; a .deb also needs fakeroot.
# The release workflow runs this once per platform. It is plain enough to run by hand, but the
# runtime alone is ~90 MB, so the workflow is the intended place. Leaves in OUTDIR:
#   Windows  LCForge-VERSION-windows-x64.msi    LCForge-VERSION-windows-x64-portable.zip
#   macOS    LCForge-VERSION-macos-arm64.dmg    (macos-x64 on an Intel Mac)
#   Linux    LCForge-VERSION-linux-x64.deb      LCForge-VERSION-linux-x64.tar.gz
set -euo pipefail

[ $# -eq 3 ] || { echo "usage: package.sh JAR VERSION OUTDIR"; exit 2; }
JAR=$1; VERSION=$2; OUT=$3
HERE="$(cd "$(dirname "$0")" && pwd)"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) OS=windows ;;
  Darwin) OS=macos ;;
  *) OS=linux ;;
esac
case "$(uname -m)" in
  arm64|aarch64) ARCH=arm64 ;;
  *) ARCH=x64 ;;
esac
PLATFORM=$OS-$ARCH

# Paths handed to Windows programs must be Windows paths.
native() { if [ $OS = windows ]; then cygpath -w "$1"; else echo "$1"; fi; }

# The JDK's tools: from JAVA_HOME when it is set (setup-java sets it), else from PATH.
tool() {
  if [ -n "${JAVA_HOME:-}" ] && { [ -x "$JAVA_HOME/bin/$1" ] || [ -x "$JAVA_HOME/bin/$1.exe" ]; }; then
    echo "$JAVA_HOME/bin/$1"
  else
    command -v "$1" || { echo "$1 not found: set JAVA_HOME to a JDK 17+" >&2; exit 1; }
  fi
}
JLINK=$(tool jlink)
JPACKAGE=$(tool jpackage)
# A Python 3 that actually runs. Not just the first name on PATH: on Windows, "python3" is often the
# Microsoft Store's placeholder, which exists, prints "Python was not found" and exits 49.
PY=""
for c in python3 python py; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c "import sys; sys.exit(sys.version_info < (3, 6))" >/dev/null 2>&1; then
    PY=$c; break
  fi
done
[ -n "$PY" ] || { echo "Python 3 not found (the smoke test needs it)" >&2; exit 1; }

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
WORK="$OUT/.work-$PLATFORM"
rm -rf "$WORK"
# The work folder holds a runtime and an app, ~180 MB together: gone on every exit, failure included.
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/input" "$WORK/pkg"
cp "$JAR" "$WORK/input/lcforge.jar"

# ---- 1. The runtime -----------------------------------------------------------------------------
# Only the modules LCForge uses. jdeps finds the first five. javac's implementation (jdk.compiler)
# and the debugger's agent (jdk.jdwp.agent) are loaded by name at run time, where no static
# analysis can see them. And no --strip-native-commands: the judge and the debugger start bin/java
# for every run, so it has to stay.
MODULES=java.base,java.compiler,java.desktop,jdk.httpserver,jdk.jdi,jdk.compiler,jdk.jdwp.agent
MAJOR=$("$JLINK" --version | cut -d. -f1)
if [ "$MAJOR" -ge 21 ]; then COMPRESS=zip-6; else COMPRESS=2; fi
echo "== jlink: Java $("$JLINK" --version), $MODULES"
# --generate-cds-archive: a class-data archive for the trimmed runtime, so the JVMs started for
# every Run and every trace come up faster. (Its warnings about java.util.logging are expected:
# the default class list names it, and this runtime leaves it out.)
"$JLINK" --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages \
  --compress "$COMPRESS" --generate-cds-archive --output "$(native "$WORK/runtime")" 2>&1 | grep -v "^\[.*cds\]" || true
[ -x "$WORK/runtime/bin/java" ] || [ -x "$WORK/runtime/bin/java.exe" ] || { echo "jlink failed: no bin/java" >&2; exit 1; }

# ---- 2. The app ---------------------------------------------------------------------------------
OWNER=$(echo "${GITHUB_REPOSITORY_OWNER:-lcforge}" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9-')
COMMON=(--name LCForge --app-version "$VERSION" --vendor LCForge
        --copyright "Copyright (c) $(date +%Y) the LCForge authors"
        --description "Run, judge and step through Java solutions to LeetCode-style problems, offline")
if [ -n "${GITHUB_REPOSITORY:-}" ]; then COMMON+=(--about-url "https://github.com/$GITHUB_REPOSITORY"); fi

case $OS in
  windows) ICON=icon.ico ;;
  macos)   ICON=icon.icns ;;
  linux)   ICON=icon.png ;;
esac
IMAGE=(--type app-image "${COMMON[@]}"
       --input "$(native "$WORK/input")" --main-jar lcforge.jar --main-class lcforge.App
       --runtime-image "$(native "$WORK/runtime")" --icon "$(native "$HERE/icons/$ICON")"
       --dest "$(native "$WORK/app")")
if [ $OS = macos ]; then
  IMAGE+=(--mac-package-identifier "io.github.$OWNER.lcforge" --mac-package-name LCForge
          --mac-app-category public.app-category.developer-tools)
fi
echo "== jpackage: app image"
"$JPACKAGE" "${IMAGE[@]}"

case $OS in
  windows) APP="$WORK/app/LCForge";     LAUNCHER="$APP/LCForge.exe" ;;
  macos)   APP="$WORK/app/LCForge.app"; LAUNCHER="$APP/Contents/MacOS/LCForge" ;;
  linux)   APP="$WORK/app/LCForge";     LAUNCHER="$APP/bin/LCForge" ;;
esac

# ---- 3. Prove it works --------------------------------------------------------------------------
# With the runtime it will ship with: compile, judge and step through a real solution. An installer
# whose Java cannot do that never gets built.
SMOKE=(--version "$VERSION")
if "$PY" -c "import sys, zipfile; sys.exit('web/vendor/monaco/vs/loader.js' not in zipfile.ZipFile(sys.argv[1]).namelist())" "$(native "$JAR")"; then
  SMOKE+=(--expect-bundled-editor)
fi
echo "== smoke test"
"$PY" "$(native "$HERE/smoke.py")" "${SMOKE[@]}" -- "$(native "$LAUNCHER")"

# ---- 4. The portable archive and the installer, from the app just tested --------------------------
NAME="LCForge-$VERSION-$PLATFORM"
echo "== jpackage: installer"
case $OS in
  windows)
    # The zip first: it needs nothing more, while the .msi needs the WiX Toolset (GitHub's Windows
    # runners have it; most PCs do not, and there this stops with jpackage's own message about it).
    (cd "$WORK/app" && "$PY" -m zipfile -c "$(native "$OUT/$NAME-portable.zip")" LCForge)
    # Per machine, into Program Files. Not --win-per-user-install: that installs into
    # %LOCALAPPDATA%\LCForge, which is where LCForge keeps your data -- the app and your work would
    # share a folder. The portable zip is the no-admin option.
    # The upgrade UUID never changes, so a new version's installer replaces the old one in place.
    "$JPACKAGE" --type msi "${COMMON[@]}" --app-image "$(native "$APP")" --dest "$(native "$WORK/pkg")" \
      --win-upgrade-uuid 32d11182-9a5a-438b-9342-0ec50e11d5ad \
      --win-menu --win-menu-group LCForge --win-shortcut --win-shortcut-prompt
    mv "$WORK"/pkg/*.msi "$OUT/$NAME.msi"
    ;;
  macos)
    "$JPACKAGE" --type dmg "${COMMON[@]}" --app-image "$APP" --dest "$WORK/pkg"
    mv "$WORK"/pkg/*.dmg "$OUT/$NAME.dmg"
    ;;
  linux)
    MAINTAINER="${GITHUB_REPOSITORY_OWNER:-lcforge}@users.noreply.github.com"
    "$JPACKAGE" --type deb "${COMMON[@]}" --app-image "$APP" --dest "$WORK/pkg" \
      --linux-package-name lcforge --linux-deb-maintainer "$MAINTAINER" \
      --linux-menu-group Development --linux-app-category devel --linux-shortcut
    mv "$WORK"/pkg/*.deb "$OUT/$NAME.deb"
    tar -C "$WORK/app" -czf "$OUT/$NAME.tar.gz" LCForge
    ;;
esac

echo "== done"
ls -l "$OUT"
