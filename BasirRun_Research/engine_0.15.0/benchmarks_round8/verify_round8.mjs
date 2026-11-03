// Round-8 completion oracle. Each subcommand measures one outcome directly from the artifacts (per-clip metric files,
// test reports, source, report, archives) and prints "<name> verification passed" only after every assertion holds.
// It never trusts a number written elsewhere: sums are recomputed from the per-clip files.
//
// Usage: node benchmarks_round8/verify_round8.mjs <check> [--out ../bench/out]
import { readFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "..");
const args = process.argv.slice(2);
const check = args[0];
const outDir = resolve(repo, args.includes("--out") ? args[args.indexOf("--out") + 1] : "../bench/out");
const fails = [];
const must = (cond, msg) => { if (!cond) fails.push(msg); };
const finish = () => {
  if (fails.length) { for (const f of fails) console.log("FAIL: " + f); process.exit(1); }
  console.log(`${check} verification passed`);
};

function loadMetrics() {
  if (!existsSync(outDir)) { console.log("FAIL: no results dir " + outDir); process.exit(1); }
  const clips = {};
  for (const f of readdirSync(outDir)) {
    const m = f.match(/^(.*)_(base|new)\.metrics\.json$/);
    if (!m) continue;
    (clips[m[1]] ??= {})[m[2]] = JSON.parse(readFileSync(join(outDir, f), "utf8"));
  }
  for (const [c, v] of Object.entries(clips)) must(v.base && v.new, `clip ${c} lacks a base or new metrics file`);
  return clips;
}
const sum = (clips, v, k, pred = () => true) => Object.entries(clips).filter(([c]) => pred(c)).reduce((a, [, x]) => a + (x[v][k] ?? 0), 0);
const mean = (clips, v, k, pred = () => true) => {
  const xs = Object.entries(clips).filter(([c]) => pred(c)).map(([, x]) => x[v][k]).filter((x) => x !== null && x !== undefined);
  return xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : NaN;
};
const syn = (c) => c.startsWith("syn_");
const sanpo = (c) => !syn(c) && c !== "owner_night_garden";

const checks = {
  tests() {
    const r = spawnSync("gradle", ["--no-daemon", "-q", ":engine:test", ":replay:test", ":appcheck:build", "--rerun-tasks"],
      { cwd: repo, encoding: "utf8", timeout: 1_500_000 });
    must(r.status === 0, `gradle exited ${r.status}: ${(r.stdout + r.stderr).slice(-400)}`);
    let total = 0, failed = 0; const passed = new Set();
    for (const dir of ["engine/build/test-results/test", "tools/replay/build/test-results/test"]) {
      const d = join(repo, dir);
      if (!existsSync(d)) { must(false, `no test results in ${dir}`); continue; }
      for (const f of readdirSync(d).filter((x) => x.endsWith(".xml"))) {
        const x = readFileSync(join(d, f), "utf8");
        const suite = x.match(/<testsuite name="([^"]+)" tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"/);
        if (!suite) continue;
        total += +suite[2]; failed += +suite[3] + +suite[4];
        for (const tc of x.matchAll(/<testcase name="([^"]+)" classname="([^"]+)"[^>]*?(\/>|>\s*<\/testcase>|>(?:(?!<testcase)[\s\S])*?<\/testcase>)/g)) {
          if (!/<failure|<error/.test(tc[0])) passed.add(`${tc[2]}.${tc[1].replace(/\(\)$/, "")}`);
        }
      }
    }
    must(failed === 0, `${failed} failing tests`);
    must(total > 136, `only ${total} tests ran (the 0.14.0 suite alone has 136)`);
    // the round-8 behaviours the owner asked for, each pinned by a named test
    const required = [
      "run.basir.engine.DecisionStabilityTest.oneOppositeFrameNeverChangesTheDirection",
      "run.basir.engine.DecisionStabilityTest.rightLeftRightFlickerGivesNoFlip",
      "run.basir.engine.DecisionStabilityTest.aRealPersistentChangeOfSideIsFollowed",
      "run.basir.engine.DecisionStabilityTest.stopPassesOnTheSameFrameAndIsNeverDelayed",
      "run.basir.engine.DecisionStabilityTest.obstacleWarningPassesAtOnceButItsSideOnlyWhenStable",
      "run.basir.engine.DecisionStabilityTest.strongCorrectionNeedsTwoFramesNotOne",
      "run.basir.engine.DecisionStabilityTest.cooldownIsAdaptiveAndRecordsReasons",
      "run.basir.engine.DecisionStabilityTest.modesFollowTheFourStates",
      "run.basir.engine.CornerNavigationTest.lineCornerIsDetectedPreparedTurnedAndExited",
      "run.basir.engine.CornerNavigationTest.athleticsBendIsNotACorner",
      "run.basir.engine.CornerNavigationTest.oneFrameNeverMakesACorner",
      "run.basir.engine.CornerNavigationTest.turnWithoutVerifiedExitGivesNoDirection",
      "run.basir.engine.Round8HapticsTest.turnPatternsAreDistinctFromEveryOtherCue",
      "run.basir.engine.Round8HapticsTest.aLaterObstacleIsFeltEvenWhenNothingPlayedInBetween",
      "run.basir.replay.DecisionRound8Test.aLateFrameIsACautionNotAStop",
      "run.basir.replay.DecisionRound8Test.headLevelAndDropWarnAtOnceAndStopWhenConfirmed",
      "run.basir.replay.DecisionRound8Test.aCornerIsOneClearCueAndNeverReplacesAnObstacleWarning",
    ];
    for (const t of required) must(passed.has(t), `required test did not pass: ${t}`);
    console.log(`tests run ${total}, failed ${failed}`);
    finish();
  },
  stops() {
    const c = loadMetrics();
    const b = sum(c, "base", "stops"), n = sum(c, "new", "stops");
    const bw = sum(c, "base", "stopsWhileWalkerContinued", sanpo), nw = sum(c, "new", "stopsWhileWalkerContinued", sanpo);
    const bf = sum(c, "base", "falseStops", syn), nf = sum(c, "new", "falseStops", syn);
    console.log(`stops ${b}->${n}, walker-continued ${bw}->${nw}, synthetic false ${bf}->${nf}`);
    must(n < b, "stops did not decrease");
    must(nw < bw, "stops while the real walker went on did not decrease");
    must(nf < bf, "synthetic false stops did not decrease");
    const lateFrameStops = Object.values(c).reduce((a, x) => a + (x.new.stopCauses?.CameraStop ?? 0), 0);
    must(lateFrameStops === 0, `${lateFrameStops} stops from late frames remain`);
    finish();
  },
  safety() {
    const c = loadMetrics();
    // Same inputs; round 8 only downgrades head-level points beyond stopping distance (2.5-4 m) from "close" to a warning,
    // so the close-hazard count may only fall, never rise. Every remaining one must still be warned (below).
    const hb = sum(c, "base", "hazardEpisodes"), hn = sum(c, "new", "hazardEpisodes");
    console.log(`close hazard episodes ${hb}->${hn}`);
    must(hn <= hb && hn >= 0.9 * hb, "close-hazard evidence changed more than the head-level reclassification explains");
    must(sum(c, "new", "hazardEpisodes") > 0, "no hazard episodes at all: the check would be vacuous");
    must(sum(c, "new", "hazardMissed") === 0, "a hazard episode got no warning or stop");
    must(sum(c, "new", "criticalNotPassedFrames") === 0, "a critical decision did not reach the vibration layer");
    must(sum(c, "new", "wayClosedUncovered") === 0, "a closed way got no warning, stop or verified turn cue");
    const bf = sum(c, "base", "hazardFeltMissed"), nf = sum(c, "new", "hazardFeltMissed");
    console.log(`felt-missed hazards ${bf}->${nf}`);
    must(nf <= bf, "more hazards go unfelt than in 0.14.0");
    finish();
  },
  flips() {
    const c = loadMetrics();
    const fb = mean(c, "base", "lrFlipsPerMin"), fn = mean(c, "new", "lrFlipsPerMin");
    const ub = mean(c, "base", "unnecessaryChangesPerMin"), un = mean(c, "new", "unnecessaryChangesPerMin");
    const cb = mean(c, "base", "commandChangesPerMin"), cn = mean(c, "new", "commandChangesPerMin");
    console.log(`flips/min ${fb.toFixed(3)}->${fn.toFixed(3)}, changes/min ${cb.toFixed(1)}->${cn.toFixed(1)}, unnecessary ${ub.toFixed(1)}->${un.toFixed(1)}`);
    must(fn < fb, "left/right flips did not decrease");
    must(sum(c, "new", "lrFlipsUnjustifiedPerMin", syn) === 0, "a flip not justified by the geometry remains (synthetic)");
    must(sum(c, "new", "conflictingVibrationsPerMin") === 0, "conflicting opposite-side vibrations remain");
    must(cn < cb && un < ub, "command changes did not decrease");
    must(mean(c, "new", "meanCommandDurationS") > mean(c, "base", "meanCommandDurationS"), "decisions are not more stable");
    finish();
  },
  corners() {
    const c = loadMetrics();
    const corners = sum(c, "new", "corners", syn), det = sum(c, "new", "cornersDetected", syn), early = sum(c, "new", "cornersAnnouncedBeforeTurn", syn);
    console.log(`synthetic corners ${corners}, cued right side ${det}, before the turn ${early}`);
    must(corners >= 12, "fewer synthetic corners than the suite defines");
    must(det / corners >= 0.75, "less than 75% of corners cued with the right side");
    must(early === det, "a cued corner was announced only after the turn began");
    must(sum(c, "new", "falseCornerFrames", syn) === 0, "false corner frames on synthetic clips");
    must(sum(c, "new", "cornerFramesOutsideTurns", sanpo) === 0, "corner phases outside real turns on SANPO");
    must(sum(c, "new", "wrongSideFramesInCorners", syn) < sum(c, "base", "wrongSideFramesInCorners", syn), "wrong-side cues in corners did not decrease");
    const a = c.syn_athletics_bend;
    must(a && a.new.corners === 0 && a.new.falseCornerFrames === 0 && a.new.turnCuesOnStraights === 0, "the athletics bend was taken for a corner");
    const loop = c.syn_rect_loop_4_corners;
    must(loop && loop.new.corners === 4, "the four-corner pitch lap is missing");
    must(loop && loop.new.cornersDetected === 4 && loop.new.cornersAnnouncedBeforeTurn === 4, `four-corner lap: ${loop?.new.cornersDetected}/4 cued`);
    must(loop && loop.new.stops === 0, "the four-corner lap stops the runner");
    finish();
  },
  coverage() {
    const c = loadMetrics();
    // task 1 section 9 and task 2 section 8 scenarios -> the clips that exercise them
    const need = {
      "straight line": ["straight_park", "syn_pitch_change_straight"], "long curve": ["syn_athletics_bend", "bend_park_fast"],
      "consecutive curves/turns": ["syn_zigzag_turns"], "line disappears": ["syn_faded_corner"], "tree shadows": ["syn_shadows_corner", "owner_night_garden"],
      "low contrast": ["syn_lowcontrast_corner"], "camera shake": ["syn_shake_corner"], "camera angle change": ["syn_pitch_change_straight"],
      "people crossing": ["crowd_obstacles", "turn90_crowd", "bend_crowd"], "static and moving obstacles": ["park_obstacles", "crowd_obstacles", "syn_barrier_before_corner"],
      "real change of direction": ["turn90_a", "turn90_c", "turn90_d", "turn127"], "rectangular pitch, four corners": ["syn_rect_loop_4_corners"],
      "90 deg right": ["syn_rect_right_sharp"], "90 deg left": ["syn_rect_left_sharp"], "rounded corner": ["syn_rect_right_rounded"],
      "obstacle before a turn": ["syn_barrier_before_corner"], "running": ["run_urban", "jog_park", "jog_urban", "turn90_jog"],
    };
    for (const [what, clips] of Object.entries(need)) for (const k of clips) must(c[k]?.base && c[k]?.new, `scenario "${what}": clip ${k} has no before/after result`);
    console.log(`${Object.keys(c).length} clips with before/after results`);
    finish();
  },
  metrics() {
    const c = loadMetrics();
    // every metric named in task 1 section 9 and task 2 section 9, measured for every clip
    const keys = ["stops", "lrFlipsPerMin", "unnecessaryChangesPerMin", "conflictingVibrationsPerMin", "meanCommandDurationS",
      "meanDirectionalPersistenceS", "meanGapBetweenVibrationsS", "hazardReactionMeanS", "guidanceLossShare", "commandChangesPerMin"];
    for (const [k, v] of Object.entries(c)) for (const key of keys) must(key in v.new && key in v.base, `${k} lacks ${key}`);
    for (const [k, v] of Object.entries(c)) {
      if (syn(k)) for (const key of ["falseStops", "cornerPhaseAgreement", "falseCornerFrames", "cornersDetected"]) must(key in v.new, `${k} lacks ${key}`);
      if (sanpo(k)) for (const key of ["stopsWhileWalkerContinued", "dirAgreement", "walkerTurnsCued"]) must(key in v.new, `${k} lacks ${key}`);
    }
    finish();
  },
  audit() {
    // every critical cue the decision layer can produce and every planner stop reason is reviewed in the report
    const src = readFileSync(join(repo, "app_decision/DecisionEngine.kt"), "utf8");
    const report = readFileSync(join(repo, "docs/BasirRun_Round8_Report.md"), "utf8");
    const critical = new Set([...src.matchAll(/Severity\.Critical, Cue\.(\w+)/g)].map((m) => m[1]));
    must(critical.size >= 5, `only ${critical.size} critical cues found: the scan is broken`);
    const planner = readFileSync(join(repo, "engine/src/main/kotlin/run/basir/engine/IntelligentPathPlanner.kt"), "utf8");
    const reasons = new Set([...planner.matchAll(/StopReason\.([A-Z_]+)/g)].map((m) => m[1]).filter((r) => r !== "NONE"));
    for (const cue of critical) must(report.includes(cue), `critical cue ${cue} is not reviewed in the report`);
    for (const r of reasons) must(report.includes(r), `planner stop reason ${r} is not reviewed in the report`);
    console.log(`reviewed ${critical.size} critical cues and ${reasons.size} planner stop reasons`);
    finish();
  },
  report() {
    // the report's "all clips" stop numbers equal a fresh recomputation from the per-clip files
    const c = loadMetrics();
    const report = readFileSync(join(repo, "docs/BasirRun_Round8_Report.md"), "utf8");
    const row = report.match(/\| قرارات التوقف \(عدد\) \| (\d+) \| (\d+) \|/);
    must(!!row, "stop row not found in the report");
    if (row) must(+row[1] === sum(c, "base", "stops") && +row[2] === sum(c, "new", "stops"), `report says ${row[1]}/${row[2]}, measured ${sum(c, "base", "stops")}/${sum(c, "new", "stops")}`);
    const clipsRow = report.match(/### كل المقاطع — (\d+) مقطعًا/);
    must(clipsRow && +clipsRow[1] === Object.keys(c).length, `report covers ${clipsRow?.[1]} clips, results have ${Object.keys(c).length}`);
    finish();
  },
  backup() {
    const h = (p) => createHash("sha256").update(readFileSync(p)).digest("hex");
    const orig = resolve(repo, "../BasirRun_Detection_Engine_0.14.0.zip"), bak = resolve(repo, "../BACKUP_BasirRun_Detection_Engine_0.14.0_original.zip");
    must(existsSync(orig) && existsSync(bak), "original or backup archive missing");
    if (existsSync(orig) && existsSync(bak)) must(h(orig) === h(bak), "backup differs from the released original");
    const first = spawnSync("git", ["rev-list", "--max-parents=0", "HEAD"], { cwd: repo, encoding: "utf8" }).stdout.trim();
    const msg = spawnSync("git", ["log", "-1", "--format=%s", first], { cwd: repo, encoding: "utf8" }).stdout;
    must(/Baseline: BasirRun Detection Engine 0\.14\.0 as released/.test(msg), "baseline commit missing");
    finish();
  },
  package() {
    const zip = resolve(repo, "../BasirRun_Detection_Engine_0.15.0.zip");
    must(existsSync(zip), "final archive missing");
    if (!existsSync(zip)) return finish();
    // GATES.md is excluded: the checker itself writes evidence into it after this gate runs
    const head = spawnSync("git", ["status", "--porcelain"], { cwd: repo, encoding: "utf8" }).stdout.split("\n")
      .filter((l) => l.trim() && !l.endsWith("GATES.md")).join("\n");
    must(head === "", "working tree has uncommitted changes the archive would not contain:\n" + head);
    for (const f of ["engine/src/main/kotlin/run/basir/engine/CornerNavigation.kt", "engine/src/main/kotlin/run/basir/engine/GuidanceDecisionStability.kt",
      "app_decision/DecisionEngine.kt", "engine/src/main/kotlin/run/basir/engine/Haptics.kt", "docs/BasirRun_Round8_Report.md", "README_AR.txt",
      "benchmarks_round8/verify_round8.mjs", "models/dfine_n_coco.onnx"]) {
      const inZip = spawnSync("unzip", ["-p", zip, "BasirRun_Detection_Engine_0.15.0/" + f], { maxBuffer: 64 << 20 });
      must(inZip.status === 0, `${f} missing from the archive`);
      if (inZip.status === 0) must(Buffer.compare(inZip.stdout, readFileSync(join(repo, f))) === 0, `${f} in the archive differs from the committed file`);
    }
    finish();
  },
};

if (!checks[check]) { console.log("usage: verify_round8.mjs <" + Object.keys(checks).join("|") + ">"); process.exit(2); }
checks[check]();
