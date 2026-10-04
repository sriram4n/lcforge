#!/usr/bin/env bash
# LCForge test suite: type codec, importer, golden problem set, live JDI trace.
set -uo pipefail
cd "$(dirname "$0")"
mkdir -p build

echo "Building..."
javac -d build -g -encoding UTF-8 --add-modules jdk.jdi $(find src -name '*.java') || exit 1
javac -d build -g -encoding UTF-8 --add-modules jdk.jdi -cp build \
      $(find test -name '*.java') $(find runtime -name '*.java') || exit 1

failed=0
for t in CodecTest ImportHtmlTest CompleteTest AttemptLogTest EmberTest GoldenTest TracerTest GateTest; do
  echo
  echo "--- $t ---"
  java --add-modules jdk.jdi -cp build "$t" || failed=$((failed + 1))
done

echo
if [ "$failed" -eq 0 ]; then echo "All suites passed."; else echo "$failed suite(s) failed."; exit 1; fi
