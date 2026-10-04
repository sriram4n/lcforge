#!/usr/bin/env bash
# LCForge -- build the distributable jar.   Usage:  ./build.sh [--monaco DIR]
#
# Output: dist/lcforge.jar -- one file that runs anywhere a JDK 17+ does:   java -jar dist/lcforge.jar
#
# JDK tools only: javac and jar, nothing to install. The web interface and the two shared runtime
# sources travel inside the jar; your data never does.
#
#   --monaco DIR   bundle a monaco-editor `min/vs` folder so the editor works offline. The release
#                  workflow fetches it in CI; a local build normally leaves it out and uses the CDN.
set -euo pipefail
cd "$(dirname "$0")"

MONACO=""
while [ $# -gt 0 ]; do
  case "$1" in
    --monaco) MONACO="$2"; shift 2 ;;
    *) echo "unknown option: $1"; exit 2 ;;
  esac
done

command -v javac >/dev/null || { echo "javac not found -- LCForge needs a JDK (17 or newer)."; exit 1; }
command -v jar   >/dev/null || { echo "jar not found -- it ships with every JDK; is the JDK's bin/ on PATH?"; exit 1; }

STAGE=dist/stage
rm -rf "$STAGE" dist/lcforge.jar
mkdir -p "$STAGE"

echo "Compiling (for Java 17 and newer)..."
# --release 17, not just -source/-target: it also checks every API call against 17's library, so a
# method that only exists in a newer JDK fails here rather than on a user's machine.
find src -name '*.java' > dist/sources.txt
javac --release 17 -encoding UTF-8 -g -d "$STAGE" @dist/sources.txt
rm dist/sources.txt

echo "Packing the interface..."
cp -R web "$STAGE/web"
mkdir -p "$STAGE/runtime"
cp runtime/*.java "$STAGE/runtime/"
# The status window's icons (packaging/IconGen.java draws them).
mkdir -p "$STAGE/icons"
cp packaging/icons/icon-16.png packaging/icons/icon-32.png packaging/icons/icon-48.png packaging/icons/icon-256.png "$STAGE/icons/"

if [ -n "$MONACO" ]; then
  [ -f "$MONACO/loader.js" ] || { echo "--monaco must point at a monaco-editor min/vs folder (it has loader.js)"; exit 1; }
  mkdir -p "$STAGE/web/vendor/monaco"
  cp -R "$MONACO" "$STAGE/web/vendor/monaco/vs"
  # Monaco is MIT-licensed, so its notices travel with it. In the npm package they sit two levels
  # above min/vs.
  for f in LICENSE ThirdPartyNotices.txt; do
    if [ -f "$MONACO/../../$f" ]; then cp "$MONACO/../../$f" "$STAGE/web/vendor/monaco/"; fi
  done
  echo "  bundled Monaco from $MONACO"
fi

jar --create --file dist/lcforge.jar --main-class lcforge.App -C "$STAGE" .
rm -rf "$STAGE"

echo
echo "  dist/lcforge.jar  ($(du -h dist/lcforge.jar | cut -f1))"
echo "  run it with:  java -jar dist/lcforge.jar"
