# Gates: Basir-Run R&D round 2 (predictive path following, stable guidance, verified survey)

OWNS: BasirRun_Research/**, engine/src/**, app/src/main/java/com/basir/run/engine/**, app/src/main/java/com/basir/run/vision/**, app/src/test/**, docs/voice/**

Scope: A verified >=20-candidate survey, measured smoothing and controller comparisons, a state-based stable guidance controller with predictive curve following, persistent-side obstacle detours and an event-based vibration language, all measured old-vs-new on real egocentric clips; local only, no push.

- [x] G1: Survey lists >=20 distinct candidates, each verified by a real clone (last commit date and license read from the repo) or a reachable official page
  CHECK: python3 BasirRun_Research/tools/gates/check_survey.py
  EXPECT: SURVEY_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=76c8ffce562852ef649f0ebc98dc4b02b31dd1b4c230a8174fb25e8cf80f0a9d; exit=0; EXPECT=matched; output-sha256=ec28c8e99f6ec891d26e18fc2882545069eba55bab03a1d3e2080019b0ed170f; output-bytes=63; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G2: Manifest keeps all required columns and has athletics-track rows (or an explicit unavailable reason per row)
  CHECK: python3 BasirRun_Research/tools/gates/check_manifest.py
  EXPECT: MANIFEST_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=47bf2f246fced49a89384799f79fae638769dabd390c5ec88ccbc7b271af9503; exit=0; EXPECT=matched; output-sha256=44b83a8c67ee9545f218038bd7ddd031de0efd9f0dd56df9b7ccebea33146cb1; output-bytes=105; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G3: Smoothing filters (EMA, One Euro, Kalman, median) compared on real sequences with jitter, lag and flip metrics
  CHECK: python3 BasirRun_Research/tools/gates/check_smoothing.py
  EXPECT: SMOOTHING_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=b5bd6dd697e3f8c73437bbe10be3fb415016fba24e54f4fe65b1ba2e417fb690; exit=0; EXPECT=matched; output-sha256=1c613b4f19ba96f9ce032d4b9513e8371b65be3d7d41a243220415fc7b829738; output-bytes=13; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G4: Path-following algorithms (pure pursuit, Stanley, MPC, DWA, potential fields, A*, curvature-based, risk-aware) compared under one human-runner model with discrete cues
  CHECK: python3 BasirRun_Research/tools/gates/check_planners.py
  EXPECT: PLANNERS_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=1ea42fb9282d56f0b972e7fa142110b74571ecccd25c2180501f3285c7615de8; exit=0; EXPECT=matched; output-sha256=37f0d5695ff076751e54e0cc43ee43694750a4e7a37b9084481242767917896f; output-bytes=12; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G5: Engine has the nine guidance states and the unit tests for curve-vs-offset, hysteresis, dwell, low-confidence-no-direction, stop priority, side persistence and vibration distinctness pass
  CHECK: bash BasirRun_Research/tools/gates/run_engine_tests.sh
  EXPECT: ENGINE_TESTS_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=39bf61a50f23dbaee67bed161a490203bafbb071fb81f04d3848d2e9fc1f3fc5; exit=0; EXPECT=matched; output-sha256=25f0b939ffeced1d7a13e1494d2b30c89aa50350e5214a62617299352dc9f8fc; output-bytes=1617; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G6: On real clips (4 SANPO sequences + owner's night video) the new controller gives fewer command changes than the current one, no left/right flips, and no more stops
  CHECK: python3 BasirRun_Research/tools/gates/check_e2e.py
  EXPECT: E2E_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=99f8da978b7f9a1f47d0ce15222577087014c9f1e3e27cc4c88bcf56f07068b4; exit=0; EXPECT=matched; output-sha256=19a2e30a0c5276e6d7ae81f446a98ddf4999514388ac30cccd495c53e6f62cea; output-bytes=136; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G7: Obstacle/person detection recall and false-clear rate measured on human-labelled SANPO frames for the detector the app ships
  CHECK: python3 BasirRun_Research/tools/gates/check_obstacles.py
  EXPECT: OBSTACLES_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=d0f967792d5bd8a8ca6597e8ea46788f985c5edf120558f005e258588c582016; exit=0; EXPECT=matched; output-sha256=9a69b90493b76e2063c7fd51868d5353d5b38f7e66d0a89668515572bd259512; output-bytes=160; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G8: Published only after the owner's permission (given 2026-10-31: "upload to the site and the app"); GitHub main contains version 0.12.0
  CHECK: python3 BasirRun_Research/tools/gates/check_published.py
  EXPECT: PUBLISHED_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=9ff4b52dc11b28c512d51570e7b5a9ba35c7251d9df961b9450cf49d62d10f4b; exit=0; EXPECT=matched; output-sha256=4c89f2b929748a65df43252cb0d62796c9ed4d8e1ae5310fca64ca64c275b36f; output-bytes=49; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G9: Arabic report published as a private Artifact with measured numbers, failures and remaining problems
  EVIDENCE: https://claude.ai/artifact/E1iUDp12JxmPTrJLC4rVqi (copy: BasirRun_Research/research_notes/03_round2_report.html); numbers re-read from benchmarks/results after the G1-G8 re-run on 2026-10-31

- [ ] G10: Owner confirms in a field test that the vibration patterns are distinguishable
  EVIDENCE: pending

- [x] G11: Visual test tool renders the ten requested layers (frame, lines, segmentation map, walkable boundaries, path centre, virtual path, expected curve, obstacles, state+confidence, frame time) into an MP4 for a chosen clip, model and settings
  CHECK: python3 BasirRun_Research/tools/gates/check_visual_tool.py
  EXPECT: VISUAL_TOOL_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=62e551608af3f4b1aec4050058ab28fd8d76ef48daa0d0401155928cff196e8d; exit=0; EXPECT=matched; output-sha256=6ac8aabf0938bbfd657333f29c87a5573e434af95b26ae21d07fb99a1bbdc341; output-bytes=15; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G12: Missing metrics measured: Dice/F1 per walkable method, mean absolute centreline error vs the human-mask path, path continuity and tracking-failure rate per clip, per-stage latency and model memory (desktop)
  CHECK: python3 BasirRun_Research/tools/gates/check_metrics.py
  EXPECT: METRICS_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=23ac86a392ddc14675727524721ed06e75d731351851b6af69d4db4432f9ca6b; exit=0; EXPECT=matched; output-sha256=5eb79fc6aa0468826518d030552aeac2d4962bde5d4519a5f441463b354bbfb1; output-bytes=11; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [ ] G13: New human annotation (CVAT/Label Studio) of the owner's video and of athletics-track footage
  EVIDENCE: pending

- [ ] G14: Frame time and memory measured on the owner's phone
  EVIDENCE: pending

ABANDON: G13 needs a human annotator and track footage that does not exist yet (no openly licensed track video found; owner recording requested); fabricated labels are forbidden. Handoff: a Label Studio task file is prepared by G11's tool so frames can be labelled once footage exists.
ABANDON: G14 needs the APK on the owner's phone, which needs a CI build after the owner allows a push. Handoff: the app already logs per-frame p50/p90 timings in the session CSV.

- [x] G15: Owner request "no stop except extreme cases, guide the turn": named engine and app tests pass, the curved park path has no stop and the night video stops once (at the bench), with the detector in the loop
  CHECK: python3 BasirRun_Research/tools/gates/check_stop_policy.py
  EXPECT: STOP_POLICY_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=b38c38bda76bbaa801e3ec8d10056d001a6ecd9746ecd1663a56ad34bdb906c9; exit=0; EXPECT=matched; output-sha256=c79b3cd2d67d9e21eb6ec57e672190b7bf0254a5a037ff62cea2cc3b8d8e7682; output-bytes=106; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G16: Owner request "the stop vibration does not stop": stop pattern <= 1.1 s of vibration, played once plus at most two reminders >= 5 s apart (named tests + night video)
  CHECK: python3 BasirRun_Research/tools/gates/check_stop_vibration.py
  EXPECT: STOP_VIBRATION_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=d90a1f661894f615eb23f633b2bee98f6cbc7f8c19ba170869f903aef9c397b6; exit=0; EXPECT=matched; output-sha256=774aae0f180c73a352c81559a7e74ddf8725b13eb5f7ce840ea74de9654f232d; output-bytes=114; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G17: Android files changed this round (LineEngine, app Haptics, training lessons) type-check against Android stand-ins
  CHECK: bash BasirRun_Research/tools/gates/compile_android_changes.sh
  EXPECT: ANDROID_CHANGES_COMPILE_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=e5411ab2ea179d4244297f4b70c21cc7e4fc3665d5cc2fb0a170b5961238c9a4; exit=0; EXPECT=matched; output-sha256=cddc67a41be948a50aa869c4be4464024b1b01c0ba2f2349a2c7317d62d09a87; output-bytes=52; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G18: The app's depth chain is measured on fixed obstacles (labelled SANPO), and the shipped depth settings catch more of them in both session halves with at most 2 more false alarms than the old settings
  CHECK: python3 BasirRun_Research/tools/gates/check_depth.py
  EXPECT: DEPTH_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=4d017eddc0ffca0e12fa331f8faa4dd0074deb2e77a03d137ea4398ebb9dd246; exit=0; EXPECT=matched; output-sha256=bd58e72bb75ca01e558b571d64f781b864b8afe7af1ec43cf0ca6c14884adb4f; output-bytes=99; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G19: Angles: sensor tilt/roll and the ground projection agree with an independent 3-D camera model (signs, right = right), the app is locked to portrait, and the angle check and "recalibrate" request are gone
  CHECK: python3 BasirRun_Research/tools/gates/check_angles.py
  EXPECT: ANGLES_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=c4304b113acbc26e325c135655b0184908307ca9ba318614d23eb04868f532bd; exit=0; EXPECT=matched; output-sha256=55cd423b4962dfa250c7c29edda80c4766ffb3632b9a694d67265b4b3ced28c8; output-bytes=10; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G20: Unified vibration table generated from the engine (all correction levels, bend, obstacle, caution, stop, back on path) matches the committed doc; the confirm lesson is removed
  CHECK: python3 BasirRun_Research/tools/gates/check_haptic_table.py
  EXPECT: HAPTIC_TABLE_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=f7bd2c2e6fa62cd1122b52ccfdfecf053ecf88c1a82b59b2479dcb871f65c017; exit=0; EXPECT=matched; output-sha256=d96b790168dc90900ccf8a70e83bd3da1a24bf755928f1aa27a78cf59abdaca2; output-bytes=16; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G21: 16-scenario before/after suite: fewer felt stops and stop frames than 0.10.0, no opposite cue within 1 s, bench still handled, right turn cued correctly, no scenario with more stops
  CHECK: python3 BasirRun_Research/tools/gates/check_scenarios.py
  EXPECT: SCENARIOS_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=5131c2672582b1fbfc583ff5fc2919e04e8d2362a26cb090fd32a62bbdd847c5; exit=0; EXPECT=matched; output-sha256=6f7f143ba975d90bcb4c9932cbb07515cde531c55168d62a2d5f31a71eb9679b; output-bytes=175; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries
