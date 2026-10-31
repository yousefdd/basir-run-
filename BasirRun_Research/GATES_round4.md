# Gates: Basir-Run round 4 (deep research, real video library, unified benchmark, terrain understanding)

OWNS: BasirRun_Research/**, engine/src/**, app/src/**, docs/**, .github/workflows/**

Scope: Audit the current engine before changing it; widen the verified survey (repos, papers, datasets) to terrain, traversability, walkable area, blind navigation; build a legally downloaded, validated and diverse library of real walking/running videos with a manifest; pass the same videos through the current engine and candidate engines with overlay videos and metrics; build a Terrain Understanding layer (walkable / caution / obstacle / unclear / unverified); integrate a new channel only if it beats the current one on a held-out split; final report; publish app and site (permission given in the round-4 request: «وبعد الانتهاء قم بتنزيله على موقع»).

- [ ] G1: Audit report exists before any engine change and answers each of the 12 listed problems with file references and measured evidence
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_audit.py
  EXPECT: AUDIT_OK

- [ ] G2: Survey has >=40 distinct repositories, each verified by a real shallow clone (last commit and licence read), covering the six areas A-F
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_survey.py
  EXPECT: SURVEY40_OK

- [ ] G3: Paper table has >=15 papers, every link answers HTTP 200 from here, with year, code link or "none", dataset, phone feasibility, fit and limits
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_papers.py
  EXPECT: PAPERS_OK

- [ ] G4: Dataset table has >=10 datasets with official source, viewpoint, labels, sequence/still, licence, size and domain-gap note
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_datasets.py
  EXPECT: DATASETS_OK

- [ ] G5: Video library has >=30 downloaded clips that ffprobe reads as real video (>=10 s, >=360 px), each with a reuse licence recorded, >=8 distinct categories, no two clips with the same source item and start time
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_videos.py
  EXPECT: VIDEOS_OK

- [ ] G6: Requested folder layout exists and the manifest has every requested column
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_layout.py
  EXPECT: LAYOUT_OK

- [ ] G7: The same clips ran through the current engine and >=1 candidate engine; each run has an overlay video and a per-frame record; behaviour metrics (stops/min, direction changes/min, uncertain share, latency) are tabulated per clip
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_bench.py
  EXPECT: BENCH_OK

- [ ] G8: Labelled metrics (IoU, Dice, precision, recall, F1, boundary F1, obstacle recall, false-negative rate, false-positive rate) recomputed on SANPO with a session-level dev/test split
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_labelled.py
  EXPECT: LABELLED_OK

- [ ] G9: Engine has a Terrain Understanding layer with the five zone kinds and its unit tests pass with the rest of the engine
  CHECK: bash BasirRun_Research/tools/gates/run_engine_tests.sh
  EXPECT: ENGINE_TESTS_OK

- [ ] G10: Integration decision follows pre-registered criteria on the held-out test split (recomputed here, not copied)
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_decision.py
  EXPECT: DECISION_OK

- [ ] G11: App JVM tests pass and changed Android sources compile against stubs
  CHECK: bash BasirRun_Research/tools/gates/r4_app_tests.sh
  EXPECT: APP_TESTS_OK

- [ ] G12: Final report has the 17 requested sections and every number in its results tables appears in a results file
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_report.py
  EXPECT: REPORT_OK

- [ ] G13: App commit and site are published (remote main has the local head; site page links the latest release)
  CHECK: python3 BasirRun_Research/tools/gates/check_published.py
  EXPECT: PUBLISHED_OK

- [ ] G14: Field test with a companion on a controlled track (owner)
  MANUAL: owner runs the app with a sighted companion and reports
