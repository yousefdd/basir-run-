"""Before/after scenario suite: the 0.10.0 engine (commit 1dab1c4, tools/kt/OldHarness.kt) vs the current
engine (tools/kt/VisualHarness.kt) on the same real clips, the same walkable masks (SegFormer-B0 +
underfoot rule) and the same D-FINE-N boxes. Perturbed scenarios are real clips with a controlled change
(image shift / darkening re-segmented by the model; a tilt offset; a 0.7 s mask dropout) and say so.
Writes benchmarks/results/scenario_suite.csv and scenario_suite.txt."""
import csv, glob, json, os, shutil, subprocess, sys, numpy as np, onnxruntime as ort
from PIL import Image, ImageEnhance
sys.path.insert(0, os.path.dirname(__file__)); from underfoot import underfoot_mask
R = "/home/user/basir-run-/BasirRun_Research"; S = "/tmp/claude-0/-home-user-basir-run-/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad"
MC = R + "/experiment_results/masks_cache"; SC = R + "/experiment_results/scenarios"; os.makedirs(SC, exist_ok=True)
seg = ort.InferenceSession(R + "/pretrained_models/segformer_b0_ade_256.onnx", providers=["CPUExecutionProvider"])
det = ort.InferenceSession(R + "/pretrained_models/dfine/dfine_n_coco.onnx", providers=["CPUExecutionProvider"])
COCO = json.load(open("/home/user/basir-run-/app/src/main/assets/coco80_names.json"))
MEAN = np.array([0.485, 0.456, 0.406]); STD = np.array([0.229, 0.224, 0.225])

def mask_of(img, W, H):
    x = ((np.asarray(img.resize((256, 256)), np.float32) / 255 - MEAN) / STD).transpose(2, 0, 1)[None].astype(np.float32)
    lab = seg.run(None, {"pixel_values": x})[0][0].argmax(0)
    return Image.fromarray((underfoot_mask(lab) * 255).astype(np.uint8)).resize((W, H), Image.NEAREST)

def boxes_of(img):
    w0, h0 = img.size; r = min(640 / w0, 640 / h0); nw, nh = round(w0 * r), round(h0 * r); L, T = (640 - nw) // 2, (640 - nh) // 2
    c = Image.new("RGB", (640, 640)); c.paste(img.resize((nw, nh), Image.BILINEAR), (L, T))
    lg, bx = det.run(None, {"pixel_values": (np.asarray(c, np.float32) / 255).transpose(2, 0, 1)[None]})
    p = 1 / (1 + np.exp(-lg[0])); out = []
    for k in np.nonzero(p.max(1) >= 0.4)[0]:
        cx, cy, bw, bh = bx[0][k] * 640
        x1, y1, x2, y2 = (cx - bw / 2 - L) / r / w0, (cy - bh / 2 - T) / r / h0, (cx + bw / 2 - L) / r / w0, (cy + bh / 2 - T) / r / h0
        lab = COCO.get(str(int(p[k].argmax())), "obj").replace(" ", "_")
        out.append(f"{max(0, x1):.4f}:{max(0, y1):.4f}:{min(1, x2):.4f}:{min(1, y2):.4f}:{float(p[k].max()):.2f}:{lab}")
    return ";".join(out)

def ensure_masks(clip, video=None):
    d = f"{MC}/{clip}"
    if not os.path.isdir(d + "/m"):
        subprocess.run(["/tmp/tfl/bin/python", R + "/tools/felt_bench.py", video, "960", "540", "58.4", "1.3", "10"], capture_output=True)
    return d

def build(name, clip, geo, perturb=None, video=None):
    """Writes jobs for a scenario: mask path, hfov, height, pitch, boxes. Perturbations make new masks."""
    d = ensure_masks(clip, video); frames = sorted(glob.glob(d + "/f/*.jpg")); masks = sorted(glob.glob(d + "/m/*.png"))
    out = f"{SC}/{name}"; os.makedirs(out + "/m", exist_ok=True); jobs = []
    rng = np.random.default_rng(7); W, H = Image.open(masks[0]).size
    for i, (fr, mk) in enumerate(zip(frames, masks)):
        hf, hm, pt = geo
        img = Image.open(fr).convert("RGB"); m = mk
        if perturb == "shake":      # hand-held/chest shake: the whole frame jumps up to 24 px (of 960) each frame
            dx, dy = rng.integers(-24, 25, 2); img = Image.fromarray(np.roll(np.roll(np.asarray(img), dy, 0), dx, 1))
            m = f"{out}/m/{i:05d}.png"; mask_of(img, W, H).save(m)
        elif perturb == "darken" and 40 <= i < 75:   # walking into deep shade: frames 2.7-5 s at 30 % brightness
            img = ImageEnhance.Brightness(img).enhance(0.3); m = f"{out}/m/{i:05d}.png"; mask_of(img, W, H).save(m)
        elif perturb == "dropout" and 40 <= i < 50:  # the line/ground vanishes for 0.67 s (nothing walkable seen)
            m = f"{out}/m/{i:05d}.png"; Image.new("L", (W, H), 0).save(m)
        elif perturb == "tilt" and i >= 45:          # the harness slips: camera 8 deg further down from 3 s on
            pt = pt + 8
        jobs.append(f"{m},{hf},{hm},{pt},{boxes_of(img)}")
    open(out + "/jobs.csv", "w").write("\n".join(jobs) + "\n")
    return out + "/jobs.csv"

def run(jobs, speed):
    o_old, o_new = jobs[:-4] + "_old.csv", jobs[:-4] + "_new.csv"
    subprocess.run(["java", "-cp", f"{R}/tools/kt/old.jar:{S}/kc/kotlinc/lib/kotlin-stdlib.jar", "OldHarnessKt", jobs, o_old, str(speed)], check=True, capture_output=True)
    subprocess.run(["java", "-cp", f"{R}/tools/kt/visual.jar:{S}/kc/kotlinc/lib/kotlin-stdlib.jar", "VisualHarnessKt", jobs, o_new, str(speed), "ONE_EURO", "0"], check=True, capture_output=True)
    return list(csv.DictReader(open(o_old))), list(csv.DictReader(open(o_new)))

def metrics(rows, turn=None, obstacle_end=False):
    n = len(rows); felt = [(i, r["felt"]) for i, r in enumerate(rows) if r["felt"]]
    side = lambda s: "left" if "left" in s else "right" if "right" in s else None
    fs = [(i, side(f)) for i, f in felt if side(f) and (f.startswith("correct") or f.startswith("curve"))]
    opp = sum(1 for (a, sa), (b, sb) in zip(fs, fs[1:]) if sa != sb and b - a <= 15)
    dirs = [r["side"] for r in rows if r["cmd"] in ("CORRECT", "CURVE") and r["side"]]
    flips = sum(a != b for a, b in zip(dirs, dirs[1:]))
    b3 = [float(r["bearing3"]) for r in rows if r["bearing3"]]
    stab = float(np.median(np.abs(np.diff(b3)))) if len(b3) > 2 else float("nan")
    lost = sum(r["cmd"] == "STOP" or r.get("state") in ("TRACK_LOST", "VISION_UNCERTAIN") for r in rows) / n
    m = dict(frames=n, stop_vibrations=sum(f.startswith("stop") for _, f in felt), stop_frames=sum(r["cmd"] == "STOP" for r in rows),
             vibrations=len(felt), warnings=sum(f.startswith(("obstacle", "caution")) for _, f in felt), opposite_within_1s=opp,
             direction_changes=flips, track_loss=round(lost, 2), path_jitter_deg=round(stab, 2))
    if turn:
        t = turn.lower(); after = rows[30:]
        cues = [(i, side(r["felt"])) for i, r in enumerate(after) if r["felt"] and side(r["felt"])]
        m["cues_toward_turn"] = sum(s == t for _, s in cues); m["cues_against_turn"] = sum(s and s != t for _, s in cues)
        first = next((i for i, s in cues if s == t), None); m["first_turn_cue_s"] = round(first / 15, 1) if first is not None else "none"
    if obstacle_end:  # bench at the end of the owner's video: warned or stopped in the last 3 s?
        tail = rows[-45:]
        m["obstacle_handled"] = any(r["felt"].startswith(("obstacle", "stop")) for r in tail) or any(r["cmd"] in ("OBSTACLE", "STOP") for r in tail)
    return m

SANPO = (58.4, 1.3, 10); NIGHT = (40, 1.3, 17)
SCEN = [  # name, clip, geometry, speed, perturbation, extra
    ("01_straight_no_painted_line", "sanpo_gravel_5n1pzdkv", SANPO, 1.4, None, {}),
    ("02_winding_painted_line", "sanpo_curve4_L_pP-G4Xoz", SANPO, 1.4, None, {}),
    ("03_curve_right", "sanpo_curve4_L_pP-G4Xoz", SANPO, 1.4, None, {"turn": "RIGHT"}),
    ("04_curve_left", "sanpo_curve5_R_hnVXDPEU", SANPO, 1.4, None, {"turn": "LEFT"}),
    ("06_park_path_no_lines", "sanpo_terrain_park_-F9N8JhM", SANPO, 1.4, None, {}),
    ("07_tree_shadows", "sanpo_curve0_R_WCzpj9Vi", SANPO, 1.4, None, {}),
    ("08_night_low_contrast", "20261030_225434", NIGHT, 1.4, None, {}),
    ("09_leaves_moss_dirt", "sanpo_curve3_R_-F9N8JhM", SANPO, 1.4, None, {}),
    ("10_people_ahead", "sanpo_jogging_0X2rC_H2", SANPO, 2.5, None, {}),
    ("11_static_obstacle_bench", "20261030_225434", NIGHT, 1.4, None, {"obstacle_end": True}),
    ("12_camera_tilt_change", "sanpo_gravel_5n1pzdkv", SANPO, 1.4, "tilt", {}),
    ("13_phone_shake", "sanpo_gravel_5n1pzdkv", SANPO, 1.4, "shake", {}),
    ("14_ground_vanishes_0.7s", "sanpo_gravel_5n1pzdkv", SANPO, 1.4, "dropout", {}),
    ("15_lighting_change_shade", "sanpo_gravel_5n1pzdkv", SANPO, 1.4, "darken", {}),
    ("16_running_speed", "sanpo_running_1uta1HvN", SANPO, 2.8, None, {}),
]
VIDEOS = {"sanpo_curve0_R_WCzpj9Vi": R + "/walking_videos/curves/sanpo_curve0_R_WCzpj9Vi.mp4"}
rows_out = []
for name, clip, geo, speed, pert, extra in SCEN:
    jobs = build(name, clip, geo, pert, VIDEOS.get(clip))
    old, new = run(jobs, speed)
    for ver, rows in (("0.10.0", old), ("current", new)):
        m = metrics(rows, extra.get("turn"), extra.get("obstacle_end", False))
        rows_out.append(dict(scenario=name, version=ver, perturbation=pert or "", speed=speed, **m))
    print(name, "done", flush=True)
rows_out.append(dict(scenario="05_athletics_track", version="-", perturbation="no openly licensed first-person track video found (see video_dataset_manifest.csv)"))
keys = list(dict.fromkeys(k for r in rows_out for k in r))
with open(R + "/benchmarks/results/scenario_suite.csv", "w", newline="") as fh:
    w = csv.DictWriter(fh, keys); w.writeheader(); w.writerows(rows_out)
tot = {}
for r in rows_out:
    if r["version"] in ("0.10.0", "current"):
        t = tot.setdefault(r["version"], {}); [t.__setitem__(k, t.get(k, 0) + r[k]) for k in ("stop_vibrations", "stop_frames", "vibrations", "warnings", "opposite_within_1s", "direction_changes")]
lines = ["Scenario suite: 0.10.0 vs current on the same inputs (15 scenarios; athletics track unavailable)."]
for v, t in tot.items(): lines.append(f"TOTAL {v}: " + ", ".join(f"{k} {val}" for k, val in t.items()))
open(R + "/benchmarks/results/scenario_suite.txt", "w").write("\n".join(lines) + "\n"); print("\n".join(lines))
