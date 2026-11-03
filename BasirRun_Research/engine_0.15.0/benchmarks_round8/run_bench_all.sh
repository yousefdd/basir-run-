#!/bin/sh
# Runs the four models over every SANPO clip (hFOV 58.44 of the ZED 2i left camera, 15 fps).
PY=/tmp/claude-0/-home-user/c32a0cde-c15e-564a-aeae-cd31a719a8ba/scratchpad/venv/bin/python
cd "$(dirname "$0")/.."
while read n s st; do
  [ -f clips/$n/meta.json ] && continue
  $PY tools/bench_models.py raw/$n/rgb clips/$n --fps 15 --hfov 58.44 && echo done $n || echo FAIL $n
done < clips_sanpo.txt
