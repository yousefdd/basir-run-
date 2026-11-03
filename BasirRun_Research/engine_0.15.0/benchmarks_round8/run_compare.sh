#!/bin/sh
# Replays every finished clip with the 0.14.0 app settings ("base") and with round 8 ("new"), then computes metrics.
# Same clips, same model outputs, same settings; round 8 adds only --corners --decision-stab.
cd "$(dirname "$0")/.."
R=../BasirRun_Detection_Engine_0.14.0/tools/replay/build/install/replay/bin/replay
PY=/tmp/claude-0/-home-user/c32a0cde-c15e-564a-aeae-cd31a719a8ba/scratchpad/venv/bin/python
APP="--visibility-coast --temporal --stabilize --centerline --adaptive --ego --ground-cover --flat-detections --intrusion-depth --guidance-stab --latency 130,130 --phone-timing"
mkdir -p out
for c in ${CLIPS:-clips/*/}; do
  c=${c%/}; n=$(basename $c)
  [ -f $c/meta.json ] || continue
  [ -f raw/$n/source.json ] && cp raw/$n/source.json $c/
  SPEED="--walking"
  case $n in syn_*) SPEED="--speed $($PY -c "import json;print(json.load(open('$c/truth.json'))['speedMps'])")";; *jog*|*run*) SPEED="";; esac
  POSE=""
  # gyroscope stand-in (the app reads the phone's gyroscope): SANPO poses / synthetic camera yaw; none for the owner's video
  if [ ! -f $c/ego.json ] && { [ -f $c/source.json ] || [ -f $c/truth.json ]; }; then $PY tools/make_ego.py $c >/dev/null; fi
  [ -f $c/ego.json ] && POSE="--pose-yaw"
  for v in base new; do
    X=""; [ $v = new ] && X="--corners --decision-stab --round8-stops"
    $R $c out/${n}_$v.jsonl $APP $SPEED $POSE $X 2>&1 | grep -v JAVA_TOOL
    $PY tools/metrics.py $c out/${n}_$v.jsonl > out/${n}_$v.metrics.json
  done
done
