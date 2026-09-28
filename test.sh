#!/data/data/com.termux/files/usr/bin/bash
# Host-JVM checks for Lean Workbench: the pure domain (src/leanwb) only.
#
# What this reaches: JSON, the bridge payload contract (against a payload the
# real bridge produced, fixtures/), the pairing link, and edit line arithmetic.
# What it cannot reach: everything in src/com/leanworkbench/app (Compose, HTTP,
# SharedPreferences). Those are checked on the device.
#
# Purity guard: src/leanwb must compile against kotlin-stdlib alone, so a domain
# file importing android.* fails here instead of drifting out of reach.
set -euo pipefail
cd "$(dirname "$0")"
trap 'echo ""; echo "TESTS FAILED at line ${LINENO}: ${BASH_COMMAND}" >&2; exit 1' ERR

STDLIB="$PREFIX/opt/kotlin/lib/kotlin-stdlib.jar"
rm -rf out-test; mkdir -p out-test
echo "== Compiling domain + tests against kotlin-stdlib alone =="
kotlinc -jvm-target 21 -cp "$STDLIB" -d out-test $(find src/leanwb test -name '*.kt') 2>&1 \
  | command grep -v '^warning:' || true
[ -f out-test/TestsKt.class ] || { echo "TESTS FAILED: compile"; exit 1; }
echo "== Running =="
java -cp "out-test:$STDLIB" TestsKt
echo "PASSED"
