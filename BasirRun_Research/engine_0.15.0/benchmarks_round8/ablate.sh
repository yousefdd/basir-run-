#!/bin/sh
# Round-8 variant with one setting changed (env R8_OBSTACLE_RELEASE), written as out_<tag>/<clip>_new.*; base copied.
cd "$(dirname "$0")/.."
TAG=$1; shift
R=../BasirRun_Detection_Engine_0.14.0/tools/replay/build/install/replay/bin/replay
PY=/tmp/claude-0/-home-user/c32a0cde-c15e-564a-aeae-cd31a719a8ba/scratchpad/venv/bin/python
APP="--visibility-coast --temporal --stabilize --centerline --adaptive --ego --ground-cover --flat-detections --intrusion-depth --guidance-stab --latency 130,130 --phone-timing"
mkdir -p out_$TAG
for c in ${CLIPS:-clips/*/}; do
  c=${c%/}; n=$(basename $c); [ -f out/${n}_base.metrics.json ] || continue
  SPEED="--walking"
  case $n in syn_*) SPEED="--speed $($PY -c "import json;print(json.load(open('$c/truth.json'))['speedMps'])")";; *jog*|*run*) SPEED="";; esac
  POSE=""; [ -f $c/ego.json ] && POSE="--pose-yaw"
  $R $c out_$TAG/${n}_new.jsonl $APP $SPEED $POSE --corners --decision-stab --round8-stops 2>&1 | grep -v JAVA_TOOL
  $PY tools/metrics.py $c out_$TAG/${n}_new.jsonl > out_$TAG/${n}_new.metrics.json
  cp out/${n}_base.metrics.json out_$TAG/
done
