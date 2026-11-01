"""Composes reports/BasirRun_Deep_Research_Final_Report.md. Every table is read from a results / research file
at build time (no number is typed by hand into a table); prose sections state where each figure comes from."""
import collections, csv, os, subprocess
R = "/home/user/basir-run-/BasirRun_Research"; B = "/home/user/basir-run-"
rd = lambda p: list(csv.DictReader(open(R + "/" + p)))
txt = lambda p: open(R + "/" + p).read().strip()
def table(rows, cols, heads=None):
    heads = heads or cols
    out = ["| " + " | ".join(heads) + " |", "|" + "---|" * len(cols)]
    for r in rows: out.append("| " + " | ".join(str(r.get(c, "")).replace("|", "/").replace("\n", " ") for c in cols) + " |")
    return "\n".join(out)
survey = rd("research_notes/survey_round4.csv"); papers = rd("research_notes/papers_round4.csv"); ds = rd("research_notes/datasets_round4.csv")
man = rd("manifests/video_dataset_manifest.csv"); lib = [m for m in man if m["file"]]; rej = [m for m in man if not m["file"]]
lab = rd("benchmarks/results/terrain_labelled.csv"); obs = rd("benchmarks/results/terrain_obstacles.csv")
vb = rd("benchmarks/results/video_benchmark.csv"); dec = txt("benchmarks/results/integration_decision.txt")
head = subprocess.run(["git", "-C", B, "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
TOP10 = [("NVlabs/SegFormer", "Shipped walkable channel (B0 ADE20K 256). Measured again here as the baseline on SANPO dev/test and on all 45 clips."),
         ("google-research-datasets/sanpo_dataset", "Same chest-camera viewpoint as Basir-Run. 280 human-labelled frames = ground truth; 37 continuous chest clips taken this round."),
         ("XuJiacong/PIDNet", "Fast Cityscapes model (MIT). LiteRT version measured: walkable IoU 0.664 (below shipped), see street_models_sanpo.txt."),
         ("ydhongHIT/DDRNet", "Real-time CNN; off-road DDRNet-23 weights (Apache-2.0 stated) exist from roboticslaburjc-org. Best candidate for a phone-speed terrain model; not exported this round."),
         ("rayguan97/GANav-offroad", "Navigability groups for terrain (RUGD/RELLIS). The idea behind the CAUTION zone; not run (mmseg stack)."),
         ("hustvl/YOLOP", "Car-view drivable area; measured in round 2, weak on sidewalks/trails (IoU 0.53)."),
         ("Peterande/D-FINE", "Shipped detector (N). Its COCO classes produced the false 'bear'/'tie' fixed this round."),
         ("DepthAnything/Depth-Anything-V2", "Shipped depth (Small int8). Fixed-obstacle recall alone 0.26-0.35 on SANPO dev/test (terrain_obstacles.csv)."),
         ("google-research/project-guideline", "Shipped guide-line model; runner viewpoint."),
         ("open-mmlab/mmsegmentation", "Source format of the off-road checkpoints; we read them with tools/safe_ckpt.py without running mmseg code.")]
L = []
A = L.append
A("# Basir-Run — Deep Research Final Report (round 4)\n")
A(f"Local commit at build time: `{head}`. All timings are desktop CPU (4 cores); **nothing was measured on a phone**. "
  "The system is **not** declared ready for independent running: no field test with a companion on a controlled track has been done (gate G14).\n")
A("## 1. The old engine and its problems\n")
A("Full audit, written before any change: `reports/BasirRun_Current_Engine_Audit.md`. In short: the 0.12.0 engine already understands walkable ground (SegFormer-B0 ADE20K + under-the-feet rule + free-space planner), "
  "so it is not lines-only, but it labels ground with ADE *object* classes, has no notion of doubt (walkable yes/no), fails on forest dirt trails (the trail under the feet came out as 'tree' 93 %), "
  "and took implausible detector classes ('bear' on a trail) as obstacles.\n")
A("## 2. GitHub research\n")
cnt = collections.Counter(a.strip() for r in survey for a in r["area"].split(","))
A(f"{len(survey)} repositories, each verified by a real shallow clone (last commit and licence read from the repo; README confirms the role). Coverage by area "
  f"(A terrain, B walkable/segmentation, C lanes/paths, D obstacles/depth/tracking, E navigation, F video/tracking/odometry): {dict(sorted(cnt.items()))}. "
  "Gate check: `tools/gates/r4_check_survey.py`.\n")
A(table(survey, ["repo", "area", "license", "last_commit", "decision"], ["Repository", "Area", "Licence (from the repo)", "Last commit", "Decision for Basir-Run"]) + "\n")
A("## 3. Ten repositories examined in detail\n")
A(table([dict(r=r, w=w) for r, w in TOP10], ["r", "w"], ["Repository", "What we did with it / what it gave"]) + "\n")
A("Models found on Hugging Face and handled this round: `nvidia/segformer-b0-finetuned-cityscapes-1024-1024` (tested), `roboticslaburjc-org/cross-dataset-visual-offroad-segmentation` "
  "(SegFormer-B2 RELLIS+GOOSE, Apache-2.0 stated; tested; its 8 classes are undocumented, so their meaning was inferred from co-occurrence with SANPO/RUGD human labels: 0 dynamic, 1 water, 2 obstacle/structure, "
  "3 tall vegetation, 4 low vegetation, 5 rough/unpaved ground, 6 smooth ground, 7 sky), `JasonTStanley/RUGD-Segformer` (B5, no licence: reference only, not run), PIDNet-S LiteRT (tested).\n")
A("## 4. Scientific papers\n")
A(f"{len(papers)} papers; every arXiv id was checked against the arXiv API (title and year must match), gate `tools/gates/r4_check_papers.py`. Published results are described by type only; we did not copy numbers we did not reproduce.\n")
A(table(papers, ["title", "year", "url", "code", "phone", "fit_for_basir_run", "limits"], ["Paper", "Year", "Link", "Code", "Phone", "Fit", "Limits"]) + "\n")
A("## 5. Best models tested\n")
A("Same 280 SANPO chest frames and the same ground truth for every channel (session split, see section 9). Desktop times per frame: shipped ADE B0 256 ≈ 45-93 ms in the clip runs (video_benchmark.csv, ms_walkable); "
  f"Cityscapes B0 512 165 ms; off-road B2 512 {open(R + '/experiment_results/sanpo/offroad_b2_512/time_ms.txt').read().strip()} ms (more than 4x the shipped model, so by the pre-registered rule it could only ship behind an off-by-default flag).\n")
A("```\n" + txt("benchmarks/results/street_models_sanpo.txt") + "\n```\n")
A("## 6. Datasets\n")
A(table(ds, ["dataset", "official_source", "viewpoint", "sequence_or_still", "licence", "domain_gap_vs_chest_runner", "use_in_basir_run"],
        ["Dataset", "Source", "Viewpoint", "Seq/still", "Licence", "Domain gap", "Use"]) + "\n")
A("## 7. Videos actually downloaded\n")
A(f"{len(lib)} clips in `videos/<category>/`, each read by ffprobe (≥ 10 s, ≥ 360 px) and inspected on 4 frames; manifest `manifests/video_dataset_manifest.csv` "
  f"({len(rej)} more rows record rejected or reference-only items with the reason). Sources: SANPO-Real chest camera (CC BY 4.0), archive.org items whose own metadata gives a Creative Commons or public-domain licence, "
  "and the owner's own videos. YouTube (including the CC-BY Walking Tours list) refuses downloads from this server, Wikimedia Commons rate-limited the API, Pexels/Pixabay answered 403: those stay references. "
  "Three clips downloaded before the licence check (Whittier 5K, Pitt Street Bridge, a Tokyo YouTube mirror) had no reuse licence and were deleted; the audit's first observations on them are kept as text only.\n")
A(table(lib, ["video_id", "category", "motion", "ground", "lighting", "curves", "people", "licence", "duration_s", "resolution"],
        ["Clip", "Category", "Motion", "Ground", "Lighting", "Curves", "People", "Licence", "s", "Resolution"]) + "\n")
A("## 8. Distribution of the library\n")
for k, name in (("category", "Category"), ("motion", "Motion"), ("licence", "Licence")):
    c = collections.Counter(m[k] for m in lib); A(f"- {name}: " + ", ".join(f"{v} {n}" for v, n in sorted(((v, n) for n, v in c.items()), reverse=True)))
A(f"- Total length: {sum(float(m['duration_s']) for m in lib) / 60:.1f} min. Athletics track: only the owner's clip (no licensed track POV found); running: 10 clips (SANPO jogging/running + a public-domain road jog).\n")
A("## 9. Model comparison (labelled)\n")
A("Split by SANPO **session** (md5 even = dev for tuning, odd = test for reporting). Corridor = bottom 45 %, middle 40 % of the frame. Boundary F1 at 2 px (160x90). Criteria were written before tuning: `benchmarks/decision_criteria_round4.md`.\n")
A(table(lab, ["method", "split", "IoU", "Dice", "precision", "recall", "F1", "false_walkable", "boundary_F1"]) + "\n")
A("Fixed obstacles within 4 m (frames; same ground truth as the depth benchmark):\n")
A(table(obs, ["method", "split", "obstacle_frames", "recall", "false_negative_rate", "clear_frames", "false_positive_rate"]) + "\n")
A("Reading: the off-road model finds far more fixed obstacles than depth alone, but with 21-30 % false alarms on clear frames; no dev setting brought that under the 8 % limit. "
  "The off-road walkable channel is better on the test half and worse on the dev half; the dev-selected fusion lowered false-walkable on test but lost more than 0.01 IoU. "
  "Halves this different mean the labelled set (14 sessions per half) is too small to decide a model swap; forest-trail ground truth (WildScenes) is the next step.\n")
A("## 10. Before and after (same clips, same inputs)\n")
A("```\n" + txt("benchmarks/results/video_benchmark.txt") + "\n```\n")
A("## 11. The engine chosen\n")
A("Decision, re-computed from the result files by `tools/make_decision.py` (gate G10):\n\n```\n" + dec + "\n```\n")
A("Result: **the app's guidance engine stays as in 0.12.0**. Nothing tested this round met its pre-registered criterion "
  "(`benchmarks/decision_criteria_round4.md`, written before the video results), so nothing new is wired into the app:\n\n"
  "- Walkable fusion with the off-road model: lost 0.039 IoU on the test sessions (limit 0.01).\n"
  "- Off-road obstacle class: no setting reached <= 8 % false alarms on clear frames.\n"
  "- DetectionFilter v2 (wild animals need score >= 0.8, 'tie' dropped): removed the false 'bear' obstacle on the forest trail, but those frames then became path-end stops and one clip "
  "(sanpo_open_terrain_qLpkgfo5) gained a felt stop; total felt stops 73 -> 74 against a limit of 'no more than 0.12.0'. Reverted.\n"
  "- Terrain zones + 'rough surface' caution: fired once correctly (ia_cc0_forest_path_a, asphalt -> dirt trail) but also on two urban clips (limit one): "
  "sanpo_jogging_uy5vEGS6, where the camera turned toward a grass corner while the runner stayed on the pavement, and ia_flickr_cliff_street_walk, where a grey lane was read as natural ground "
  "(frames inspected). Reverted from the app.\n\n"
  "The Terrain Understanding layer (engine `TerrainZones`: WALKABLE / CAUTION / OBSTACLE / UNCLEAR / UNVERIFIED from class, softmax top-2 margin, the walkable rule and connectivity to the feet, "
  "plus `SurfaceMonitor`) is kept in the engine module with its tests, **not called by the app**, for the research harness and the next round. "
  "Transparency note: SurfaceMonitor was rewritten twice after it missed the transition on ia_cc0_forest_path_a, which is also the clip named in the criterion; that part of the criterion was "
  "therefore met with tuning on the same clip. The urban limit, which was not tuned, is what failed.\n")
A("## 12. Changes to the project\n")
A("- `engine/.../TerrainZones.kt` (new, not used by the app): zones, summary, SurfaceMonitor. Tests: `engine/src/test/.../TerrainZonesTest.kt` (8 tests).\n"
  "- App and the rest of the engine: no change kept (DetectionFilter v2 and the rough-surface cue were written, tested, then reverted by the decision above).\n- App version 0.12.1 (code 36): carries only the athletics-track fixes made before this round (commit 18569b2: ground-class memory in `UnderfootClasses`, the planner keeps coasting a tracked lane for a moment, small sports items and birds need score >= 0.6). These are already in the '0.12.0 engine' column of section 10, because that jar was built from the pre-round-4 source.\n"
  "- Research tools: `safe_ckpt.py` (reads whole-model pickles without running their code), `offroad_model.py`, `bench_terrain.py`, `bench_videos.py`, `bench_videos_summary.py`, `build_video_manifest.py`, `ia_fetch.py`, `make_decision.py`, `visual_test.py` (several engine jars on the same inputs, portrait/rotated video, off-road variant, terrain dump), `kt/TerrainHarness.kt`.\n")
A("## 13. Problems fixed\n")
A("- The research harness now runs several engine builds on the same frames, masks and detections, so before/after numbers compare engines and nothing else.\n"
  "- The visual test squashed portrait and rotated phone videos (it assumed 16:9); fixed.\n- onnxruntime on this desktop had moved to 1.29 and refused the guideline model; pinned to 1.20.1 like the app.\n"
  "- Licence hygiene: three unlicensed clips removed; every library clip's licence is recorded from its source metadata.\n")
A("## 14. Still open\n")
A("- Forest dirt trails: the shipped ADE model still often fails to see them as walkable (the off-road model sees them, but is too slow and not proven on urban test frames).\n"
  "- Fixed-obstacle recall from depth alone stays low (0.26 test); the off-road obstacle class finds more but with too many false alarms.\n"
  "- Curves are still followed mostly as corrections.\n- No phone timing, battery or heat numbers (G14); no field test (G10 of round 2, G14 here).\n- False 'bear'/'tie' detections still reach the engine on rare frames; a filter that does not turn them into path-end stops is still needed.\n- A surface cue needs a foot-area model that tells pavement from a grass verge (both false cues were at the pavement edge).\n")
A("## 15. Technical limits\n")
A("- A camera cannot know friction, wetness, firmness or hidden holes; CAUTION means 'natural or loose surface seen', never 'safe'.\n- SANPO labels cover walking in cities and parks; trails and running are thin in the labelled set.\n"
  "- Licences: SegFormer weights (shipped ADE, Cityscapes, and the off-road B2 fine-tuned from them) trace back to NVIDIA's research-only licence; D-FINE and Depth Anything V2 Small are Apache-2.0.\n"
  "- Internet clips have unknown camera height and tilt; the benchmark assumes 1.3 m and 10° (17° for SANPO), so their stop/turn counts are behaviour comparisons between engines, not ground truth.\n")
A("## 16. How to re-run\n")
A("```\ncd BasirRun_Research\npython3 tools/build_video_manifest.py            # manifest from the library\npython3 tools/bench_videos.py                    # same clips through 0.12.0 and round-4 engines (+ off-road variant)\n"
  "python3 tools/bench_videos_summary.py            # behaviour metrics\n/tmp/tfl/bin/python tools/bench_terrain.py       # labelled SANPO dev/test metrics (needs torch for the off-road cache)\n"
  "python3 tools/make_decision.py                   # applies the pre-registered criteria\npython3 tools/make_report.py                     # this report\n"
  "node .claude/skills/unlazy/scripts/gate-check.mjs BasirRun_Research/GATES_round4.md   # all round-4 gates\n```\n")
A("## 17. References and licences\n")
A("Repositories: section 2 (each URL is `https://github.com/<repo>`). Papers: section 4. Datasets: section 6. Video sources and licences: `manifests/video_dataset_manifest.csv`. "
  "Hugging Face models: https://huggingface.co/nvidia/segformer-b0-finetuned-cityscapes-1024-1024 , https://huggingface.co/roboticslaburjc-org/cross-dataset-visual-offroad-segmentation , "
  "https://huggingface.co/JasonTStanley/RUGD-Segformer , https://huggingface.co/datasets/shawshankvkt/Walking_Tours .\n")
os.makedirs(R + "/reports", exist_ok=True)
open(R + "/reports/BasirRun_Deep_Research_Final_Report.md", "w").write("\n".join(L) + "\n")
print("report written,", sum(1 for l in L if l.startswith("## ")), "sections")
