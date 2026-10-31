"""Street-scene segmentation models (they know pole, traffic sign, sidewalk) vs the shipped SegFormer-B0
ADE20K, on the same 280 human-labelled SANPO chest frames and the same ground truth:
  walkable: sidewalk always; road / terrain only when under the feet (same under-the-feet rule)
  fixed obstacles: blobs of pole / traffic light / traffic sign placed on the ground by their base,
                   frame-level recall within 4 m in arm reach, false alarms on clear frames (the same
                   ground truth as bench_depth_eval / bench_seg_obstacles), alone and OR the app's depth
  path: masks with D-FINE boxes carved, for eval_paths.py
Label maps are cached per model."""
import csv, glob, hashlib, json, math, os, sys, time, numpy as np
from PIL import Image
from scipy import ndimage
R = "/home/user/basir-run-/BasirRun_Research"; D = R + "/experiment_results/sanpo/depth/"
frames = [l.strip() for l in open(R + "/benchmarks/sanpo_list.txt") if l.strip()]
jobs = {(j["session"], j["frame"]): j for j in csv.DictReader(open(D + "jobs.csv"))}
dep = {(r["session"], r["frame"]): r["level"] in ("Possible", "Hazard") for r in csv.DictReader(open(D + "check_shipped.csv"))}
MEAN = np.array([0.485, 0.456, 0.406]); STD = np.array([0.229, 0.224, 0.225])
# Cityscapes ids
ROAD, SIDEWALK, FENCE, POLE, LIGHT, SIGN, TERRAIN, PERSON = 0, 1, 4, 5, 6, 7, 9, 11

def pidnet():
    from ai_edge_litert.interpreter import Interpreter
    it = Interpreter(R + "/pretrained_models/street/pidnet_s.tflite", num_threads=4); it.allocate_tensors()
    i, o = it.get_input_details()[0]["index"], it.get_output_details()[0]["index"]
    def run(img):
        x = ((np.asarray(img.resize((1024, 1024), Image.BILINEAR), np.float32) / 255 - MEAN) / STD).transpose(2, 0, 1)[None].astype(np.float32)
        it.set_tensor(i, x); it.invoke(); return it.get_tensor(o)[0].argmax(0)
    return run

def segformer_cs(size):
    import torch, transformers
    torch.set_num_threads(4)
    snap = transformers.SegformerForSemanticSegmentation.from_pretrained("nvidia/segformer-b0-finetuned-cityscapes-1024-1024", cache_dir=R + "/pretrained_models/hf/hub").eval()
    def run(img):
        x = torch.from_numpy(((np.asarray(img.resize((size, size), Image.BILINEAR), np.float32) / 255 - MEAN) / STD).transpose(2, 0, 1)[None].astype(np.float32))
        with torch.no_grad(): return snap(pixel_values=x).logits[0].argmax(0).numpy()
    return run

MODELS = {"pidnet_s_cityscapes_1024": pidnet, "segformer_b0_cityscapes_768": lambda: segformer_cs(768), "segformer_b0_cityscapes_512": lambda: segformer_cs(512), "segformer_b0_cityscapes_384": lambda: segformer_cs(384)}

def walk_mask(lab):
    h, w = lab.shape; foot = lab[int(h * 0.8):, int(w * 0.35):int(w * 0.65)]
    keep = [SIDEWALK] + [c for c in (ROAD, TERRAIN) if (foot == c).mean() >= 0.15]
    return np.isin(lab, keep)

def blobs_in_corridor(lab, j, ids, min_px, max_m):
    hf, height, pitch = float(j["hfov"]), float(j["height"]), float(j["pitch"])
    H, W = lab.shape; fxl = (W / 2) / math.tan(math.radians(hf) / 2); fy = fxl * (H / W) * (16 / 9)
    cy, cx = (H - 1) / 2, (W - 1) / 2; comp, n = ndimage.label(np.isin(lab, ids)); hits = []
    for k in range(1, n + 1):
        ys, xs = np.nonzero(comp == k)
        if len(ys) < min_px: continue
        foot = ys.max(); a = math.radians(pitch) + math.atan((foot - cy) / fy)
        if a <= 0.02: continue
        fwd = height / math.tan(a); slant = height / math.sin(a)
        x0, x1 = xs[ys >= foot - max(1, H // 64)].min(), xs[ys >= foot - max(1, H // 64)].max()
        if fwd <= max_m and (x0 - cx) / fxl * slant <= 0.6 and (x1 - cx) / fxl * slant >= -0.6: hits.append(fwd)
    return hits

GT = [3, 5, 6, 17, 30]
f_ = lambda x: float(x) if x else None
out_lines = []
for name, make in MODELS.items():
    cache = f"{R}/experiment_results/sanpo/street_{name}"; os.makedirs(cache, exist_ok=True); run = None; times = []
    labels = {}
    for f in frames:
        key = (f.split("/")[-2], os.path.basename(f)[:-4]); p = f"{cache}/{key[0]}__{key[1]}.npy"
        if not os.path.exists(p):
            run = run or make(); t0 = time.perf_counter(); lab = run(Image.open(f).convert("RGB")); times.append((time.perf_counter() - t0) * 1000)
            np.save(p, lab.astype(np.uint8))
        labels[key] = np.load(p)
    # walkable + masks for the path test (D-FINE boxes carved as in the shipped chain)
    tp = fp = fn = neg = 0; mdir = f"{R}/experiment_results/sanpo/masks_{name}_dfine"; os.makedirs(mdir, exist_ok=True)
    for f in frames:
        key = (f.split("/")[-2], os.path.basename(f)[:-4]); gt = np.asarray(Image.open(f[:-4] + "_sem.png")); H, W = gt.shape
        m = np.asarray(Image.fromarray(walk_mask(labels[key]).astype(np.uint8) * 255).resize((W, H), Image.NEAREST)) > 0
        corr = np.zeros((H, W), bool); corr[int(H * .55):, int(W * .3):int(W * .7)] = True; reg = corr & (gt != 0); q = np.isin(gt, GT)
        tp += (m & q & reg).sum(); fp += (m & ~q & reg).sum(); fn += (~m & q & reg).sum(); neg += (reg & ~q).sum()
        shipped = np.asarray(Image.open(f"{R}/experiment_results/sanpo/masks_ade_b0_256_underfoot/{key[0]}__{key[1]}.png").resize((W, H), Image.NEAREST)) > 0
        carved = np.asarray(Image.open(f"{R}/experiment_results/sanpo/masks_underfoot_dfine/{key[0]}__{key[1]}.png").resize((W, H), Image.NEAREST)) > 0
        Image.fromarray(((m & ~(shipped & ~carved)) * 255).astype(np.uint8)).save(f"{mdir}/{key[0]}__{key[1]}.png")
    line = f"{name}: walkable corridor IoU {tp/(tp+fp+fn):.3f}, false-walkable {fp/neg:.3f}" + (f", {np.median(times):.0f} ms/frame desktop CPU" if times else "")
    # fixed obstacles
    for ids_name, ids in (("pole/light/sign", [POLE, LIGHT, SIGN]), ("+fence", [POLE, LIGHT, SIGN, FENCE])):
        s4 = [j for k, j in jobs.items() if f_(j["static_m"]) is not None and f_(j["static_m"]) <= 4]
        cl = [j for k, j in jobs.items() if all(f_(j[c]) is None or f_(j[c]) > 6 for c in ("static_m", "dynamic_m", "wall_m"))]
        mp = max(2, int(labels[next(iter(labels))].size * 0.0004))
        hit = lambda j, m: bool(blobs_in_corridor(labels[(j["session"], j["frame"])], j, ids, mp, m))
        halves = [sum(hit(j, 4.5) for j in s4 if int(hashlib.md5(j["session"].encode()).hexdigest(), 16) % 2 == h) for h in (0, 1)]
        u = sum(hit(j, 4.5) or dep[(j["session"], j["frame"])] for j in s4); ufa = sum(hit(j, 4.0) or dep[(j["session"], j["frame"])] for j in cl)
        line += f"\n   fixed obstacles {ids_name}: alone {sum(halves)}/{len(s4)} ({halves[0]}+{halves[1]}), false alarms {sum(hit(j, 4.0) for j in cl)}/{len(cl)}; OR depth {u}/{len(s4)}, false {ufa}/{len(cl)}"
    out_lines.append(line); print(line, flush=True)
base = f"reference (shipped): depth only 16/54 fixed obstacles, false 4/145; SegFormer ADE20K walkable IoU 0.739, false-walkable 0.366"
open(R + "/benchmarks/results/street_models_sanpo.txt", "w").write(base + "\n" + "\n".join(out_lines) + "\n"); print(base)
