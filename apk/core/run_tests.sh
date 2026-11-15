#!/bin/sh
# Compiles the pure-Java core with --release 8 (Android-safe API level) and runs the harness.
# Usage: core/run_tests.sh [hunter_analyze.py] [sample.txt] [synth.txt]
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
PY="${1:-$HERE/testdata/hunter_analyze.py}"
SAMPLE="${2:-$HERE/testdata/sample.txt}"
SYNTH="${3:-$HERE/testdata/synth.txt}"
APPSRC="$HERE/../app/src/main/java/com/hunter/canlogger"
OUT="${TMPDIR:-/tmp}/hunter_test"
rm -rf "$OUT"
mkdir -p "$OUT/classes"
python3 -I "$HERE/../tools/make_expected.py" "$PY" "$SAMPLE" "$SYNTH" "$OUT"
javac --release 8 -Xlint:all -Xlint:-options -d "$OUT/classes" $(find "$HERE/src" -name '*.java')
java -cp "$OUT/classes" com.hunter.canlogger.core.CoreTest "$SAMPLE" "$OUT" "$SYNTH" "$PY" "$APPSRC"
