#!/usr/bin/env bash
# Compiles the pure-Kotlin engine and all its tests with a minimal kotlin.test stub (no Gradle/Android
# SDK in this container) and runs every @Test. Prints ENGINE_TESTS_OK only when all pass and the
# nine guidance states exist in the engine.
set -euo pipefail
S=/tmp/claude-0/-home-user-basir-run-/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad
KC=$S/kc/kotlinc/bin/kotlinc
E=/home/user/basir-run-/engine
OUT=$(mktemp -d)
for s in TRACK_CENTERED CORRECT_LEFT CORRECT_RIGHT CURVE_LEFT CURVE_RIGHT OBSTACLE_WARNING STOP_REQUIRED TRACK_LOST VISION_UNCERTAIN; do
  grep -q "^    $s," $E/src/main/kotlin/run/basir/engine/StableGuidance.kt || { echo "missing state $s"; exit 1; }
done
$KC -nowarn -Xallow-kotlin-package -cp /tmp/json.jar -d $OUT/t.jar $E/src/main/kotlin/run/basir/engine/*.kt $E/src/test/kotlin/run/basir/engine/*.kt $S/kt2/Kt.kt $S/kt2/Run.kt 2>&1 | grep -v "^warning" || true
cd $E
classes=$(ls src/test/kotlin/run/basir/engine/*.kt | xargs -n1 basename | sed 's/\.kt$//' | sed 's/^/run.basir.engine./')
res=$(java -cp $OUT/t.jar:/tmp/json.jar:$S/kc/kotlinc/lib/kotlin-stdlib.jar RunKt $classes)
echo "$res" | grep -E "FAIL|failures" || true
[ "${SHOW:-0}" = 1 ] && echo "$res" | grep "^PASS" || true
n=$(echo "$res" | grep -c '^PASS')
echo "passed $n"
echo "$res" | grep -q '^failures: 0$' && [ "$n" -ge 50 ] && echo ENGINE_TESTS_OK
