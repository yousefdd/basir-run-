#!/bin/sh
# Renders every synthetic scene, then runs the four models on it (hFOV 45, portrait, 15 fps; pitch 30 deg known).
PY=/tmp/claude-0/-home-user/c32a0cde-c15e-564a-aeae-cd31a719a8ba/scratchpad/venv/bin/python
cd "$(dirname "$0")/.."
for s in rect_right_sharp rect_left_sharp rect_right_rounded rect_parallel_walk athletics_bend zigzag_turns faded_corner shadows_corner lowcontrast_corner shake_corner pitch_change_straight barrier_before_corner; do
  [ -f clips/syn_$s/meta.json ] && continue
  $PY tools/render_track.py $s raw/syn_$s 2>/dev/null || { echo FAIL render $s; continue; }
  P=$($PY -c "import json;v=json.load(open('raw/syn_$s/truth.json'))['pitchDeg'];print('' if v is None else '--pitch %s'%v)")
  $PY tools/bench_models.py raw/syn_$s/rgb clips/syn_$s --fps 15 --hfov 45 $P && cp raw/syn_$s/truth.json clips/syn_$s/ && $PY tools/clear_synthetic_detections.py clips/syn_$s && echo done $s || echo FAIL bench $s
done
