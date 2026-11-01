"""Round 4 unified video benchmark. Every clip in videos/<category>/ goes through the SAME frames, masks and
detections, then through two engines: visual_base.jar (0.12.0 engine, unchanged source at cc93aa5) and
visual.jar (round-4 engine: DetectionFilter v2 + TerrainZones/SurfaceMonitor). Trail-like categories also run
the research variant with the off-road SegFormer-B2 walkable channel (tag offroad).
5 fps, at most 600 frames (120 s) per clip. Overlays and per-frame records land in visual_reports/round4/."""
import glob, json, os, subprocess, sys
R = "/home/user/basir-run-/BasirRun_Research"; OUT = R + "/visual_reports/round4"
TRAIL = {"forest_trails", "park_paths", "gravel_paths", "rocky_surfaces"}
clips = sorted(p for p in glob.glob(R + "/videos/*/*.mp4") if "/_" not in p)
only = set(sys.argv[1:])
for p in clips:
    cat = p.split("/")[-2]; name = os.path.basename(p)[:-4]
    if only and name not in only: continue
    if not os.path.exists(p): continue
    if glob.glob(f"{OUT}/{cat}/{name}_segformer256_*/engine_visual.csv") and not only: print(cat, name, "done before", flush=True); continue
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", p, "-frames:v", "1", "/tmp/claude-0/bench_probe.png"], check=True)
    from PIL import Image; w, h = Image.open("/tmp/claude-0/bench_probe.png").size
    portrait = h > w
    speed = "2.8" if cat == "running" else "1.4"
    pitch = "17" if name.startswith("sanpo_") else "10"
    common = ["python3", R + "/tools/visual_test.py", "--video", p, "--fps", "5", "--max-frames", "600", "--speed", speed, "--pitch", pitch,
              "--hfov", "40" if portrait else "58.4", "--width", "540" if portrait else "960", "--out", f"{OUT}/{cat}"]
    runs = [common + ["--jar", "visual_base.jar,visual.jar"]]
    if cat in TRAIL: runs.append(common + ["--model", "offroad512", "--jar", "visual.jar", "--tag", "offroad"])
    for r in runs:
        res = subprocess.run(r, capture_output=True, text=True, timeout=3600)
        print(cat, name, "ok" if res.returncode == 0 else "FAILED " + res.stderr.strip()[-200:], flush=True)
