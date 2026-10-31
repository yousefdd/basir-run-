"""Metrics the brief asked for that round 2 had not computed yet. Writes benchmarks/results/metrics_round2.txt.
  1 Dice and F1 (= Dice for binary masks), precision, recall of walkable ground in the corridor ahead,
    per method, from the per-frame scores of bench_walkable.py / bench_yolop.py (280 SANPO frames).
  2 Mean absolute centreline error: the engine's free-space path on each model's mask vs the same
    planner on the human mask (reference), lateral error at 1, 2, 3, 4 m ahead (pinhole conversion
    from image columns with each frame's field of view; frames where both have a path).
  3 Path continuity (share of frames with a path) and tracking-failure rate (share in TRACK_LOST,
    VISION_UNCERTAIN or a stop) per clip, from the round-2 controller runs.
  4 Time per stage (median, this desktop CPU) from the visual test, and model memory: resident-set
    growth when each ONNX model is loaded and run once in a fresh process.
"""
import csv, json, math, os, subprocess, sys, statistics, collections
R = "/home/user/basir-run-/BasirRun_Research"
out = ["Round-2 metrics (desktop CPU; no phone measurements)."]
# 1 Dice / F1
rows = list(csv.DictReader(open(R + "/experiment_results/sanpo/per_frame.csv"))) + list(csv.DictReader(open(R + "/experiment_results/sanpo/per_frame_yolop.csv")))
out.append("\n1. Walkable ground in the corridor ahead, 280 SANPO frames")
out.append("Note: segformer_b0_sanpo_walkable (blind-assist/Segf0rmer_SANPO) was trained on SANPO, so its score here is not a fair test (off-road RUGD IoU 0.01 in round 1); it is listed, not compared.")
out.append("method | Dice = F1 | precision | recall | IoU")
for m in dict.fromkeys(r["method"] for r in rows):
    tp = fp = fn = 0
    for r in rows:
        if r["method"] != m: continue
        d = json.loads(r["corridor"]); tp += d["tp"]; fp += d["fp"]; fn += d["fn"]
    out.append(f"{m} | {2*tp/max(1,2*tp+fp+fn):.3f} | {tp/max(1,tp+fp):.3f} | {tp/max(1,tp+fn):.3f} | {tp/max(1,tp+fp+fn):.3f}")
# shipped variant (underfoot) and fusions scored from saved masks
import numpy as np
from PIL import Image
frames = [l.strip() for l in open(R + "/benchmarks/sanpo_list.txt") if l.strip()]
GT = [3, 5, 6, 17, 30]
for tag, d in [("segformer_underfoot (shipped)", "masks_ade_b0_256_underfoot"), ("segformer_underfoot + D-FINE boxes", "masks_underfoot_dfine")]:
    tp = fp = fn = 0
    for f in frames:
        gt = np.asarray(Image.open(f[:-4] + "_sem.png")); H, W = gt.shape
        m = np.asarray(Image.open(f"{R}/experiment_results/sanpo/{d}/{f.split('/')[-2]}__{os.path.basename(f)[:-4]}.png").resize((W, H), Image.NEAREST)) > 0
        c = np.zeros((H, W), bool); c[int(H * .55):, int(W * .3):int(W * .7)] = True; reg = c & (gt != 0); g = np.isin(gt, GT)
        tp += (m & g & reg).sum(); fp += (m & ~g & reg).sum(); fn += (~m & g & reg).sum()
    out.append(f"{tag} | {2*tp/(2*tp+fp+fn):.3f} | {tp/(tp+fp):.3f} | {tp/(tp+fn):.3f} | {tp/(tp+fp+fn):.3f}")
# 2 centreline error
GEO = {(r["session"], r["frame"]): r for r in csv.DictReader(open(R + "/datasets/sanpo/frame_geometry.csv"))}
def routes(tag):
    res = {}
    for r in csv.DictReader(open(f"{R}/experiment_results/sanpo/paths/routes_{tag}.csv")):
        if r["status"] != "ROUTE": continue
        key = os.path.basename(r["mask"])[:-4]; sess, fr = key.split("__")
        g = GEO.get((sess, fr))
        if not g: continue
        fx = 0.5 / math.tan(math.radians(float(g["hfov_deg"])) / 2)  # in frame widths
        pts = [tuple(map(float, p.split(":"))) for p in r["points"].split()]
        res[key] = [((u - 0.5) / fx * fwd, fwd) for u, v, fwd in pts]
    return res
def lat_at(pts, f):
    pts = sorted(pts, key=lambda p: p[1])
    for (l0, f0), (l1, f1) in zip(pts, pts[1:]):
        if f0 <= f <= f1 and f1 > f0: return l0 + (l1 - l0) * (f - f0) / (f1 - f0)
    return None
ref = routes("gt")
out.append("\n2. Mean absolute centreline error vs the planner on the human mask (metres; frames where both have a path)")
out.append("mask | frames | 1 m | 2 m | 3 m | 4 m")
for tag, name in [("ade_uf", "segformer_underfoot (shipped)"), ("uf_dfine", "shipped + D-FINE boxes"), ("yolop", "yolop320"), ("and", "segformer AND yolop"), ("ade", "segformer fixed classes")]:
    rt = routes(tag); errs = collections.defaultdict(list); n = 0
    for k, pts in rt.items():
        if k not in ref: continue
        n += 1
        for f in (1, 2, 3, 4):
            a, b = lat_at(pts, f), lat_at(ref[k], f)
            if a is not None and b is not None: errs[f].append(abs(a - b))
    out.append(f"{name} | {n} | " + " | ".join(f"{statistics.mean(errs[f]):.2f} (n={len(errs[f])})" if errs[f] else "-" for f in (1, 2, 3, 4)))
# 3 continuity / tracking failure per clip
out.append("\n3. Path continuity and tracking failure per clip (new controller, One Euro)")
out.append("clip | frames | with a path | tracking failure (lost / uncertain / stopped) | of which a STOP_REQUIRED")
for clip in ["sanpo_gravel_5n1pzdkv", "sanpo_terrain_park_-F9N8JhM", "sanpo_jogging_0X2rC_H2", "sanpo_running_1uta1HvN", "20261030_225434", "sanpo_curve4_L_pP-G4Xoz", "sanpo_curve5_R_hnVXDPEU", "sanpo_curve3_R_-F9N8JhM"]:
    rs = list(csv.DictReader(open(f"{R}/experiment_results/masks_cache/{clip}/guidance.csv")))
    fail = sum(r["ONE_EURO_state"] in ("TRACK_LOST", "VISION_UNCERTAIN", "STOP_REQUIRED") for r in rs)
    out.append(f"{clip} | {len(rs)} | {sum(r['mode'] != 'STOP' for r in rs)/len(rs):.0%} | {fail/len(rs):.0%} | {sum(r['ONE_EURO_state']=='STOP_REQUIRED' for r in rs)}")
# 4 latency + memory
out.append("\n4. Time per stage (median ms, desktop CPU, visual test on the owner's night video) and model memory")
fr = list(csv.DictReader(open(R + "/visual_reports/visual_test/20261030_225434_segformer256_ONE_EURO_cl0/frames.csv")))
for k in ("ms_walkable", "ms_line", "ms_detector", "ms_grid", "ms_plan", "ms_guide"):
    out.append(f"{k}: {statistics.median(float(r[k]) for r in fr):.2f}")
probe = r'''
import resource, sys, numpy as np, onnxruntime as ort
before = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
s = ort.InferenceSession(sys.argv[1], providers=["CPUExecutionProvider"])
i = s.get_inputs()[0]; shape = [d if isinstance(d, int) else 1 for d in i.shape]
x = (np.random.rand(*shape) * 255).astype(np.uint8) if "uint8" in i.type else np.random.rand(*shape).astype(np.float32)
s.run(None, {i.name: x})
print((resource.getrusage(resource.RUSAGE_SELF).ru_maxrss - before) / 1024)
'''
for name, path in [("SegFormer-B0 256", "segformer_b0_ade_256.onnx"), ("Project Guideline 513", "guideline.onnx"), ("D-FINE-N 640", "dfine/dfine_n_coco.onnx"), ("YOLOP 320", "yolop/yolop-320-320.onnx")]:
    mb = subprocess.run(["/tmp/tfl/bin/python", "-c", probe, f"{R}/pretrained_models/{path}"], capture_output=True, text=True).stdout.strip()
    size = os.path.getsize(f"{R}/pretrained_models/{path}") / 1e6
    out.append(f"{name}: file {size:.1f} MB, resident memory growth after load + one run {float(mb):.0f} MB")
open(R + "/benchmarks/results/metrics_round2.txt", "w").write("\n".join(out) + "\n"); print("\n".join(out))
