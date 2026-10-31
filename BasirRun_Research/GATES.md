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
  EVIDENCE: automatic-evidence=v1; definition-sha256=39bf61a50f23dbaee67bed161a490203bafbb071fb81f04d3848d2e9fc1f3fc5; exit=0; EXPECT=matched; output-sha256=b2484477a09b2fbf464383c7cab7b2c8c3040b6d928ba19a8b058288d6ca9fe7; output-bytes=1617; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G6: On real clips (4 SANPO sequences + owner's night video) the new controller gives fewer command changes than the current one, no left/right flips, and no more stops
  CHECK: python3 BasirRun_Research/tools/gates/check_e2e.py
  EXPECT: E2E_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=99f8da978b7f9a1f47d0ce15222577087014c9f1e3e27cc4c88bcf56f07068b4; exit=0; EXPECT=matched; output-sha256=192b6f008416426b7c1d7fb7e2e3d16fae7132a6c67e522d6f47cb9adbeb88d2; output-bytes=137; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G7: Obstacle/person detection recall and false-clear rate measured on human-labelled SANPO frames for the detector the app ships
  CHECK: python3 BasirRun_Research/tools/gates/check_obstacles.py
  EXPECT: OBSTACLES_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=d0f967792d5bd8a8ca6597e8ea46788f985c5edf120558f005e258588c582016; exit=0; EXPECT=matched; output-sha256=07e600531abf27351b84e7ab468b10f1231f0069f060b2d9a36290efd2bdba23; output-bytes=160; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G8: Nothing pushed to GitHub (local commits stay ahead of origin or unpushed)
  CHECK: python3 BasirRun_Research/tools/gates/check_not_pushed.py
  EXPECT: NOT_PUSHED_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=3a458835ed8e104e13cd73d60f68c2895e8fd8843b538a6d5fba3d367e981f09; exit=0; EXPECT=matched; output-sha256=f83e36ee5880819bf7cf3c8ba114d9ba28309efa6b525f5c0544302140f6cc9b; output-bytes=88; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G9: Arabic report published as a private Artifact with measured numbers, failures and remaining problems
  EVIDENCE: https://claude.ai/artifact/E1iUDp12JxmPTrJLC4rVqi (copy: BasirRun_Research/research_notes/03_round2_report.html); numbers re-read from benchmarks/results after the G1-G8 re-run on 2026-10-31

- [ ] G10: Owner confirms in a field test that the vibration patterns are distinguishable
  EVIDENCE: pending
