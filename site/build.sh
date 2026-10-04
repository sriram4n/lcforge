#!/usr/bin/env bash
# Assemble the website into OUTDIR (default: _site), for GitHub Pages or a local look.
#
#   bash site/build.sh [OUTDIR]
#
# The page's ember is the app's own. web/pet.js and the two helpers it needs are copied in as they
# are, and the palettes and the creature's styles are lifted out of web/styles.css between their
# @markers -- so the site cannot drift from the app. Change the ember, and the next deploy shows it.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=${1:-_site}

rm -rf "$OUT"
mkdir -p "$OUT/ember"
cp site/*.html site/*.css site/*.js "$OUT/"
cp web/icon.svg "$OUT/favicon.svg"
cp web/pet.js web/svg.js web/dom.js "$OUT/ember/"

# One marked span of the app's stylesheet: from the line that starts "/* @NAME:begin" to the line
# that starts "/* @NAME:end", inclusive. Matched at the start of a line only -- the begin comment
# itself mentions the end marker, and a looser match stops on the very line it started on.
span() {
  awk -v b="/* @$1:begin" -v e="/* @$1:end" '
    index($0, b) == 1 { on = 1 }
    on { print; n++ }
    on && index($0, e) == 1 { done = 1; exit }
    END { if (!done || n < 50) { print "web/styles.css: the @" name " span is missing or truncated" > "/dev/stderr"; exit 1 } }
  ' name="$1" web/styles.css
}
{ span palettes; echo; span ember; } > "$OUT/ember/ember.css"

# Which repository the download buttons ask GitHub about. The workflow sets it; a local build has
# none, and the page says so instead of linking nowhere.
if [ -n "${GITHUB_REPOSITORY:-}" ]; then
  for f in "$OUT"/*.html "$OUT"/*.js; do
    sed "s|__GITHUB_REPOSITORY__|$GITHUB_REPOSITORY|g" "$f" > "$f.tmp" && mv "$f.tmp" "$f"
  done
fi
echo "site -> $OUT ($(du -sh "$OUT" | cut -f1))"
