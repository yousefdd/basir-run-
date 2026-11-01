# Gates: Basir-Run round 4 (deep research, real video library, unified benchmark, terrain understanding)

OWNS: BasirRun_Research/**, engine/src/**, app/src/**, docs/**, .github/workflows/**

Scope: Audit the current engine before changing it; widen the verified survey (repos, papers, datasets) to terrain, traversability, walkable area, blind navigation; build a legally downloaded, validated and diverse library of real walking/running videos with a manifest; pass the same videos through the current engine and candidate engines with overlay videos and metrics; build a Terrain Understanding layer (walkable / caution / obstacle / unclear / unverified); integrate a new channel only if it beats the current one on a held-out split; final report; publish app and site (permission given in the round-4 request: «وبعد الانتهاء قم بتنزيله على موقع»).

- [x] G1: Audit report exists before any engine change and answers each of the 12 listed problems with file references and measured evidence
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_audit.py
  EXPECT: AUDIT_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=b46ecc854bbad6c476a60bbc016086931fb6cd13cd1c4fa983729bcd36b06512; exit=0; EXPECT=matched; output-sha256=8316d7fee73df3b72a299e378b3a95edadf9a2c67f1444e9c0688500bedc0392; output-bytes=28; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G2: Survey has >=40 distinct repositories, each verified by a real shallow clone (last commit and licence read), covering the six areas A-F
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_survey.py
  EXPECT: SURVEY40_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=55f5c751d95df4afffd58ce996b1288d911b9ffd329b2b9cd2a9c937fda7d0be; exit=0; EXPECT=matched; output-sha256=6ce28fdb5df494099a4579fccc99cb73cc56c751f489e84b3f2773e23bf4247a; output-bytes=104; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G3: Paper table has >=15 papers, every link answers HTTP 200 from here, with year, code link or "none", dataset, phone feasibility, fit and limits
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_papers.py
  EXPECT: PAPERS_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=a08b8cddc1fe800a30355850b2f35953a001d8c6c295a132edd33c88f88f1a85; exit=0; EXPECT=matched; output-sha256=f48eac7cb05890950a686f8fb27f4d8ad7c536cf1bcf4fe96576c72280db2cce; output-bytes=47; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G4: Dataset table has >=10 datasets with official source, viewpoint, labels, sequence/still, licence, size and domain-gap note
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_datasets.py
  EXPECT: DATASETS_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=ca01428e1348fc676c2415e1455b6761249e8445bb9abca7d2ee96464969bb26; exit=0; EXPECT=matched; output-sha256=09c8c7bdce47b52e2d8072e5e882690c3fa72d7dfb6056a87c88c0b5ecdf2992; output-bytes=24; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G5: Video library has >=30 downloaded clips that ffprobe reads as real video (>=10 s, >=360 px), each with a reuse licence recorded, >=8 distinct categories, no two clips with the same source item and start time
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_videos.py
  EXPECT: VIDEOS_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=95a449f9332b5a7168a6dd630019c1873c7950e705600f7255d8aa311d642702; exit=0; EXPECT=matched; output-sha256=55a81443a032c85c10ff9a2bbe7c2f52f436840b63a8f69cad6960f44323ff00; output-bytes=43; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G6: Requested folder layout exists and the manifest has every requested column
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_layout.py
  EXPECT: LAYOUT_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=b34e181a8bdb953940f2f57e75560f3f639a2bdabce5e05e8b50aa4d6d8c5750; exit=0; EXPECT=matched; output-sha256=34241fd95b38676dd15723be0976deb9979c0f5ad5d393e21e118e3a7dfa812a; output-bytes=10; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G7: The same clips ran through the current engine and >=1 candidate engine; each run has an overlay video and a per-frame record; behaviour metrics (stops/min, direction changes/min, uncertain share, latency) are tabulated per clip
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_bench.py
  EXPECT: BENCH_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=c62e4c8b139ea4391597c95d7b112ebaf820f433e7094d35ee2223e071aaeccf; exit=0; EXPECT=matched; output-sha256=c4e432b7b40847719ac2b9069da6d19801c77c282b59f0983c72c030800831d0; output-bytes=39; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G8: Labelled metrics (IoU, Dice, precision, recall, F1, boundary F1, obstacle recall, false-negative rate, false-positive rate) recomputed on SANPO with a session-level dev/test split
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_labelled.py
  EXPECT: LABELLED_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=86e4738b44d763547e5518807ad43c404e8a72e436f880b01df07bdb24faa08f; exit=0; EXPECT=matched; output-sha256=6fc6267c7efe7b423cec9ed119b01d8ef9cdbc23c0aa80de9de4b63ba52df4f5; output-bytes=68; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G9: Engine has a Terrain Understanding layer with the five zone kinds and its unit tests pass with the rest of the engine
  CHECK: bash BasirRun_Research/tools/gates/run_engine_tests.sh
  EXPECT: ENGINE_TESTS_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=39bf61a50f23dbaee67bed161a490203bafbb071fb81f04d3848d2e9fc1f3fc5; exit=0; EXPECT=matched; output-sha256=854e76053302eb6defb20457fd9215df37bc6ab3c405863e09b0528742e02197; output-bytes=1617; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G10: Integration decision follows pre-registered criteria on the held-out test split (recomputed here, not copied)
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_decision.py
  EXPECT: DECISION_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=1b2cbda867122a3c6f99170f48986afbc2e00f308995e376d1b7013dec3dfcc9; exit=0; EXPECT=matched; output-sha256=f2f230a7cf1bad875b6f6a08975e6f1e90e032a4d2d9fcdcb0c5563d133cf700; output-bytes=508; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G11: App JVM tests pass and changed Android sources compile against stubs
  CHECK: bash BasirRun_Research/tools/gates/r4_app_tests.sh
  EXPECT: APP_TESTS_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=b0254d8cc6a96a310ebeb63b99f3a5e15b72904e7b68d1cc3b8a874259a24b2b; exit=0; EXPECT=matched; output-sha256=7f81912749469115f806ab3fdeff8bfd170440322fcf51589a7d5a5828b8a94c; output-bytes=208; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [x] G12: Final report has the 17 requested sections and every number in its results tables appears in a results file
  CHECK: python3 BasirRun_Research/tools/gates/r4_check_report.py
  EXPECT: REPORT_OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=54f51788d1b6333226ca3e00f53b4dfbde0d65e344a1e3a4754d816da0cd73e7; exit=0; EXPECT=matched; output-sha256=8dcb09ed833912554121501fd0e9257d735cdc7ca097e32a9e47e62451129dff; output-bytes=52; shell=/bin/sh; cwd=/home/user/basir-run-; path=7f4fb4b02918/15 entries

- [ ] G13: App commit and site are published (remote main has the local head; site page links the latest release)
  CHECK: python3 BasirRun_Research/tools/gates/check_published.py
  EXPECT: PUBLISHED_OK

- [ ] G14: Field test with a companion on a controlled track (owner)
  MANUAL: owner runs the app with a sighted companion and reports
