#!/usr/bin/env bash
# Type-checks the app files changed this round that the JVM tests cannot reach: LineEngine.kt,
# Haptics.kt (with Android stand-ins) and the lessons block of HapticTrainingScreen.kt (extracted
# verbatim, with stand-ins for the Compose screen around it). Prints ANDROID_CHANGES_COMPILE_OK.
set -euo pipefail
S=/tmp/claude-0/-home-user-basir-run-/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad; KC=$S/kc/kotlinc/bin/kotlinc
A=/home/user/basir-run-/app/src/main/java/com/basir/run; G=/home/user/basir-run-/BasirRun_Research/tools/gates; T=$(mktemp -d)
SRC=$(grep -L "^import android\|^import androidx\|^import com.google\|^import ai.onnx\|^import java.util.concurrent\|R\.string" $A/engine/*.kt $A/vision/*.kt $A/vision/depth/*.kt)
# lessons block from the training screen, verbatim
{ echo "package com.basir.run"; echo "import com.basir.run.engine.Phrase"; echo "import run.basir.engine.HapticLanguage"; echo "import run.basir.engine.HapticPattern"; echo "import run.basir.engine.Level"; echo "import run.basir.engine.Side"
  sed -n '/^private class Lesson/,/^)$/p' $A/HapticTrainingScreen.kt
  echo 'private val QUIZ = LESSONS.filter { it.answer.isNotEmpty() }'
  echo 'fun lessonCheck(): Int = LESSONS.sumOf { it.patterns(HapticLanguage()).size } + QUIZ.size'; } > $T/Lessons.kt
grep -q "obstacleDetour(Side.LEFT)" $T/Lessons.kt && grep -q "backOnPath()" $T/Lessons.kt
$KC -nowarn -d $T/out.jar /home/user/basir-run-/engine/src/main/kotlin/run/basir/engine/*.kt $SRC \
  $A/vision/line/LineEngine.kt $A/vision/seg/SurfaceChannel.kt $A/Haptics.kt $T/Lessons.kt $G/android_stubs/*.kt > $T/log 2>&1 || true; grep -v "^warning\|JAVA_TOOL" $T/log || true
! grep -q "error:" $T/log
cat > $T/Main.kt <<'K'
fun main() { println("lessons+quiz patterns " + com.basir.run.lessonCheck()) }
K
$KC -nowarn -cp $T/out.jar -d $T/m.jar $T/Main.kt 2>&1 | grep -v JAVA_TOOL | grep error && exit 1
java -cp $T/out.jar:$T/m.jar:$S/kc/kotlinc/lib/kotlin-stdlib.jar MainKt 2>&1 | grep -v JAVA_TOOL
echo ANDROID_CHANGES_COMPILE_OK
