#!/usr/bin/env bash
# G11: (1) the app's JVM unit tests (engine, vision, vision/depth) pass; (2) the Android-only files changed in
# round 4 type-check against stand-ins: LineEngine/SurfaceChannel/Haptics/lessons (compile_android_changes.sh)
# and the walkable segmenter SurfaceModels.kt with ONNX Runtime stand-ins. Prints APP_TESTS_OK.
set -uo pipefail
S=/tmp/claude-0/-home-user-basir-run-/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad; KC=$S/kc/kotlinc/bin/kotlinc
B=/home/user/basir-run-; G=$B/BasirRun_Research/tools/gates; A=$B/app/src/main/java/com/basir/run
cd $B
SRC=$(grep -L "^import android\|^import androidx\|^import com.google\|^import ai.onnx\|^import java.util.concurrent\|R\.string" app/src/main/java/com/basir/run/engine/*.kt app/src/main/java/com/basir/run/vision/*.kt app/src/main/java/com/basir/run/vision/depth/*.kt)
TT=app/src/test/java/com/basir/run; D=$(mktemp -d)
$KC -nowarn -Xallow-kotlin-package -cp /tmp/json.jar engine/src/main/kotlin $SRC $TT/engine/*.kt $TT/vision/depth/*.kt $TT/vision/*.kt $S/kt/JunitStub.kt /tmp/apptest/Run.kt /tmp/apptest/Assume.kt -d $D/a.jar > $D/c.log 2>&1 || { grep error: $D/c.log | head; exit 1; }
CLS=$(cd app/src/test/java && ls com/basir/run/engine/*Test.kt com/basir/run/vision/*Test.kt com/basir/run/vision/depth/*Test.kt | sed 's#/#.#g; s#\.kt$##')
OUT=$(cd app && java -cp $D/a.jar:$S/kc/kotlinc/lib/kotlin-stdlib.jar:/tmp/json.jar RunKt $CLS 2>&1 | grep -v JAVA_TOOL)
echo "$OUT" | tail -3
echo "$OUT" | grep "FAIL" | grep -vq "AssumptionViolatedException" && { echo "$OUT" | grep FAIL | grep -v AssumptionViolated; exit 1; }
bash $G/compile_android_changes.sh | tail -2 | grep -q ANDROID_CHANGES_COMPILE_OK || { echo "android changes do not compile"; exit 1; }
# the segmenter with ONNX stand-ins (replaces the segmenter stub)
$KC -nowarn -d $D/seg.jar engine/src/main/kotlin/run/basir/engine/*.kt $A/vision/seg/SurfaceModels.kt $G/android_stubs_seg/*.kt $(ls $G/android_stubs/*.kt | grep -v SegStubs) > $D/s.log 2>&1
grep "error:" $D/s.log | head -5; grep -q "error:" $D/s.log && exit 1
echo APP_TESTS_OK
