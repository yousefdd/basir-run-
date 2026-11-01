# Round 4 — integration criteria (written before tuning)

Written after the first untuned run of `tools/bench_terrain.py` and before any rule was tuned. The untuned
test-split numbers had already been seen, and this note says so.

Split: SANPO sessions, md5(session) even = dev (all tuning), odd = test (report only).

## Walkable channel (replaces or fuses with the shipped ADE channel)

The rule is chosen on **dev only**. It is the highest dev IoU whose dev false-walkable rate is no worse than
the shipped channel's dev false-walkable rate.

The chosen rule is adopted only if, on **test**, all three hold:
1. IoU >= shipped IoU − 0.01;
2. false-walkable rate <= shipped false-walkable rate;
3. on the forest-trail clips (`videos/forest_trails/ia_cc0_forest_path_a.mp4`, `ia_cc0_forest_path_b.mp4`), the
   share of frames where less than half the corridor ahead is walkable is lower than with the shipped channel.

## Fixed-obstacle channel (adds to depth)

Blob parameters are chosen on **dev only**: the highest dev recall with a dev false-positive rate <= 0.08 when
combined with depth.

Adopted only if, on **test**:
1. recall (OR depth) >= depth-only recall + 0.15;
2. false-positive rate (OR depth) <= 0.10.

## Phone feasibility

The model must run on CPU through ONNX Runtime 1.20. Desktop time per frame is recorded. If a model
passes on accuracy but its desktop time exceeds 4× the shipped walkable model, it is integrated **behind a
flag that is off by default** and kept for phone measurement (owner handoff G14). It is not made the
default without phone numbers.

## Added before the video benchmark finished (round-4 engine changes)

**DetectionFilter v2** (wild animals need score ≥ 0.8; worn items such as "tie" dropped) is adopted if, over all
library clips, these hold on the same inputs:
- felt stop vibrations with the round-4 engine ≤ with the 0.12.0 engine;
- felt obstacle warnings ≤ 0.12.0;
- no clip gains more than 1 felt stop.

**Terrain zones + SurfaceMonitor "rough surface" caution** is adopted if all three hold:
- across all library clips the mean is ≤ 1 cue per minute;
- across the urban categories (walking, wet_surfaces, obstacles, difficult_lighting, low_contrast and running clips with urban SANPO
  environment) there is at most 1 cue in total;
- it fires at least once on a clip with a visible smooth → natural-ground transition (`ia_cc0_forest_path_a`, which goes from
  asphalt to dirt trail).
