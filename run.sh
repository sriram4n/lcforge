#!/usr/bin/env bash
# LCForge — compile and start.  Usage: ./run.sh [--port 7070] [--clean]
set -euo pipefail
cd "$(dirname "$0")"

PORT=7070
CLEAN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --port)  PORT="$2"; shift 2 ;;
    --clean) CLEAN=1; shift ;;
    *) echo "unknown option: $1"; exit 1 ;;
  esac
done

if ! command -v javac >/dev/null 2>&1; then
  echo "javac not found on PATH. LCForge needs a JDK (17 or newer)." >&2
  exit 1
fi

[ "$CLEAN" = 1 ] && rm -rf build
mkdir -p build data workspace

# Rebuild only when something changed.
NEWEST=$(find src runtime -name '*.java' -newer build/.buildstamp 2>/dev/null | head -1 || true)
if [ ! -f build/.buildstamp ] || [ -n "$NEWEST" ]; then
  echo "Compiling LCForge..."
  javac -d build -g -encoding UTF-8 --add-modules jdk.jdi $(find src -name '*.java')
  date > build/.buildstamp
fi

# --console: this terminal is the window. Ctrl+C stops it.
exec java --add-modules jdk.jdi -cp build lcforge.App --console --root "$PWD" --port "$PORT"
