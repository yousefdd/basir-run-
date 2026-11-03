# Gates: Basir-Run round 8 — guidance stability, fewer stops, 90° corners, dominant command, adaptive cooldown

OWNS: app_decision/**, app_vision/**, engine/**, tools/replay/**, docs/BasirRun_Round8_Report.md, README_AR.txt, benchmarks_round8/**, GATES.md

Scope: both owner task lists implemented in the engine, measured before/after on the same clips, documented, backed up and packaged.

- [x] G0: this ledger states outcomes that can fail
  CHECK: node /root/.claude/skills/synced/cbf4c598-d5ea-49f7-911f-493ccaf6fcb2_41e07f9b-9a13-4429-8e5e-139c7f185223/unlazy/scripts/gate-lint.mjs GATES.md
  EXPECT: LINT OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=db41dc433a483b014ff4271e107090a809ed5eabc1e5a5498b01ee86ed4e3450; exit=0; EXPECT=matched; output-sha256=48630b7361dd44ee870917b12c3d19b9d7bdea738aaca16bb04d4cab83b772d2; output-bytes=8; shell=/bin/sh; cwd=/home/user/downloads/BasirRun_Detection_Engine_0.14.0; path=7f4fb4b02918/15 entries

- [x] G1: the full test suite and the Android compile check pass, including a named test for each round-8 rule
  CHECK: node benchmarks_round8/verify_round8.mjs tests
  EXPECT: tests verification passed
  EVIDENCE: automatic-evidence=v1; definition-sha256=40a4bc9b1f548b8cf4161ebcac3791da0e1deb78c1bfb9a1676303fba74876d1; exit=0; EXPECT=matched; output-sha256=05d092215de883550fbf202f4b80953098e0f4bda96c9a5cb31ef189cb58bb1d; output-bytes=50; shell=/bin/sh; cwd=/home/user/downloads/BasirRun_Detection_Engine_0.14.0; path=7f4fb4b02918/15 entries

- [x] G2: stop decisions, stops while the real walker went on, and synthetic false stops all decrease; no stop comes from a late frame
  CHECK: node benchmarks_round8/verify_round8.mjs stops
  EXPECT: stops verification passed
  EVIDENCE: automatic-evidence=v1; definition-sha256=f2a9ef2819285eef9dcdbb8ebe74118f2811581606adaac4eb12715dfaa3d88f; exit=0; EXPECT=matched; output-sha256=ff532b677a8688fc63ace0a764efc3cc96d5ac4a34909c844ad8d10e81951b9c; output-bytes=87; shell=/bin/sh; cwd=/home/user/downloads/BasirRun_Detection_Engine_0.14.0; path=7f4fb4b02918/15 entries

- [x] G3: no hazard loses its warning or stop, no critical decision is held back, and no more hazards go unfelt than before
  CHECK: node benchmarks_round8/verify_round8.mjs safety
  EXPECT: safety verification passed
  EVIDENCE: automatic-evidence=v1; definition-sha256=14d50aa1932c62a627bf917480ecfb93be98bf1632d9d3b063fd2ba017f6b7a4; exit=0; EXPECT=matched; output-sha256=721ab0a05c2663f23c0fbeebbdb8b5abe9be187bf9e2b88178e97ac9593b13dd; output-bytes=83; shell=/bin/sh; cwd=/home/user/downloads/BasirRun_Detection_Engine_0.14.0; path=7f4fb4b02918/15 entries

- [x] G4: left/right flips, command changes and unnecessary changes decrease; no unjustified flip and no conflicting vibration remains
  CHECK: node benchmarks_round8/verify_round8.mjs flips
  EXPECT: flips verification passed
  EVIDENCE: automatic-evidence=v1; definition-sha256=d36f207a3e44ef8a191ee0ec8ae68325cca728d4dc57341c4d90be5a28bdc8ee; exit=0; EXPECT=matched; output-sha256=ca1eefdad61b0963b148246d926edb4559283ba9528a409195bfecd6e5e823f6; output-bytes=97; shell=/bin/sh; cwd=/home/user/downloads/BasirRun_Detection_Engine_0.14.0; path=7f4fb4b02918/15 entries

- [x] G5: corners are cued on the right side before the turn (incl. a full four-corner pitch lap) with no false corners and no athletics-bend confusion
  CHECK: node benchmarks_round8/verify_round8.mjs corners
  EXPECT: corners verification passed
  EVIDENCE: automatic-evidence=v1; definition-sha256=e83f7b2395d0143a72aa6864d7071ac50a8aebc80094afcc352f8188d2286754; exit=0; EXPECT=matched; output-sha256=5a49fc99e919ebf348d5c124f0599414e8fb37913611e8456487292be5cace3d; output-bytes=89; shell=/bin/sh; cwd=/home/user/downloads/BasirRun_Detection_Engine_0.14.0; path=7f4fb4b02918/15 entries

- [x] G6: every test scenario listed in both tasks has a before/after result
  CHECK: node benchmarks_round8/verify_round8.mjs coverage
  EXPECT: coverage verification passed
  EVIDENCE: automatic-evidence=v1; definition-sha256=6f117faa17d7981ec3ec026209d7536cf6f1b64484e4ce2e93897d46086e2e48; exit=0; EXPECT=matched; output-sha256=ec65d4d6251c6b9920d360b787bca04a7b7189b8ca7c2516217387d6df2c0d5a; output-bytes=64; shell=/bin/sh; cwd=/home/user/downloads/BasirRun_Detection_Engine_0.14.0; path=7f4fb4b02918/15 entries

- [x] G7: every metric listed in both tasks is measured for every clip, before and after
  CHECK: node benchmarks_round8/verify_round8.mjs metrics
  EXPECT: metrics verification passed
  EVIDENCE: automatic-evidence=v1; definition-sha256=45d12704cba9f8bde68e0e0b3dce37563288059c063c9f08bd75d8b56658b914; exit=0; EXPECT=matched; output-sha256=9af40cc6e83d89aec4d3f40712275c53ba2e29621bdfc09d1cd1ad817d179e5d; output-bytes=28; shell=/bin/sh; cwd=/home/user/downloads/BasirRun_Detection_Engine_0.14.0; path=7f4fb4b02918/15 entries

- [x] G8: every critical cue and planner stop reason in the code is reviewed in the report's stop audit
  CHECK: node benchmarks_round8/verify_round8.mjs audit
  EXPECT: audit verification passed
  EVIDENCE: automatic-evidence=v1; definition-sha256=acfc7309c78c2ae0749cbf6db37677d19089284bc6d4869343be75228b319673; exit=0; EXPECT=matched; output-sha256=2456609ca347f7db8f76f7419e12b86c4efb8a70775da8d938ac92e8eab27582; output-bytes=79; shell=/bin/sh; cwd=/home/user/downloads/BasirRun_Detection_Engine_0.14.0; path=7f4fb4b02918/15 entries

- [x] G9: the report's numbers equal a fresh recomputation from the per-clip results
  CHECK: node benchmarks_round8/verify_round8.mjs report
  EXPECT: report verification passed
  EVIDENCE: automatic-evidence=v1; definition-sha256=62b1570134675b8c2fefb146b2fe1261a60a12101787f5bd0dc1bad39af22d20; exit=0; EXPECT=matched; output-sha256=7180cafee8d8eb21dfcfd97ef267631cbe85f093c38828f6c4a9a58a64da98b7; output-bytes=27; shell=/bin/sh; cwd=/home/user/downloads/BasirRun_Detection_Engine_0.14.0; path=7f4fb4b02918/15 entries

- [x] G10: the released 0.14.0 is kept byte-identical as a backup and as the baseline commit
  CHECK: node benchmarks_round8/verify_round8.mjs backup
  EXPECT: backup verification passed
  EVIDENCE: automatic-evidence=v1; definition-sha256=c6dacb475ae73d66db6ea6e694a15e07b427ff802072654c9eb610241a278981; exit=0; EXPECT=matched; output-sha256=85b6e5d45dc776a68d9e786b4750a8dfdee08540c85873d71675c420f6fb5605; output-bytes=27; shell=/bin/sh; cwd=/home/user/downloads/BasirRun_Detection_Engine_0.14.0; path=7f4fb4b02918/15 entries

- [x] G11: the delivered 0.15.0 archive contains exactly the committed final files
  CHECK: node benchmarks_round8/verify_round8.mjs package
  EXPECT: package verification passed
  EVIDENCE: automatic-evidence=v1; definition-sha256=70fe8f113a7fb61711326ac3df4288c66c48e4303368407efe46a0998910112f; exit=0; EXPECT=matched; output-sha256=c850c81904635ad7959d062e988e40520e6c3dbddb2705720643e99b7bef1955; output-bytes=28; shell=/bin/sh; cwd=/home/user/downloads/BasirRun_Detection_Engine_0.14.0; path=7f4fb4b02918/15 entries

- [ ] G12: guidance verified with a live phone camera on a closed track with a companion (task 2, section 8)
  EVIDENCE: pending

- [ ] G13: a blind user can tell "prepare to turn" from "turn now" before the patterns are relied on (task 2, section 7)
  EVIDENCE: pending

ABANDON: G12 needs a phone, a closed track and a companion; impossible from this cloud session. Handoff: owner field test, protocol in README_AR.txt (next test) and report section 8.
ABANDON: G13 needs testing with a blind user; impossible here. Handoff: owner runs the learning screen and recognition quiz (HapticLanguage.catalog) with the user before relying on the corner patterns.
