"""Unified visual test: one clip, one model and settings -> an MP4 with every layer the brief asked for,
plus a per-frame CSV and a Label Studio task file (for human ground truth later).

Layers drawn on each frame:
  1 original frame            2 guide line (Project Guideline model, magenta)
  3 segmentation map (green tint of walkable ground)   4 walkable boundaries (white contour)
  5 path centre (cyan, when the corridor centreline is enabled and found)
  6 virtual path (amber ribbon 0.6 m wide, from the first visible metre to where the ground ends)
  7 expected continuation (dashed: the path's own curve carried 3 m further; not used for guidance)
  8 obstacles (D-FINE-N boxes, red when tracked as confirmed on the ground)
  9 guidance state, command and confidence     10 time per stage (ms, this desktop CPU)

Usage:
  /tmp/tfl/bin/python visual_test.py --video V.mp4 [--model segformer256|yolop320|and] [--fps 15] [--width 960]
         [--speed 1.4] [--hfov 58.4 --height 1.3 --pitch 10] [--smoothing ONE_EURO] [--centreline 0|1] [--out DIR]
"""
import argparse, csv, glob, json, os, subprocess, sys, time
import numpy as np, onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont, ImageFilter
sys.path.insert(0, os.path.dirname(__file__)); from underfoot import underfoot_mask, UnderfootMemory
R = "/home/user/basir-run-/BasirRun_Research"; S = "/tmp/claude-0/-home-user-basir-run-/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad"
P = R + "/pretrained_models"
ap = argparse.ArgumentParser()
ap.add_argument("--video", required=True); ap.add_argument("--model", default="segformer256", choices=["segformer256", "yolop320", "and"])
ap.add_argument("--fps", type=float, default=15); ap.add_argument("--width", type=int, default=960); ap.add_argument("--speed", type=float, default=1.4)
ap.add_argument("--hfov", type=float, default=58.4); ap.add_argument("--height", type=float, default=1.3); ap.add_argument("--pitch", type=float, default=10)
ap.add_argument("--smoothing", default="ONE_EURO", choices=["NONE", "EMA", "ONE_EURO", "KALMAN", "MEDIAN"]); ap.add_argument("--centreline", default="0")
ap.add_argument("--out", default=R + "/visual_reports/visual_test"); ap.add_argument("--max-frames", type=int, default=0)
A = ap.parse_args()
name = f"{os.path.basename(A.video)[:-4]}_{A.model}_{A.smoothing}_cl{A.centreline}"
out = os.path.join(A.out, name); os.makedirs(out + "/f", exist_ok=True); os.makedirs(out + "/m", exist_ok=True); os.makedirs(out + "/o", exist_ok=True)
for f in glob.glob(out + "/f/*.jpg") + glob.glob(out + "/o/*.jpg"): os.remove(f)
vw, vh = map(int, subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", A.video], capture_output=True, text=True, check=True).stdout.split()[0].split(","))
H = int(A.width * vh / vw) // 2 * 2                                              # keep the clip's aspect (portrait phone clips too)
subprocess.run(["ffmpeg", "-loglevel", "error", "-i", A.video, "-vf", f"fps={A.fps},scale={A.width}:{H}", out + "/f/%05d.jpg"], check=True)
frames = sorted(glob.glob(out + "/f/*.jpg"))
if A.max_frames: frames = frames[:A.max_frames]
MEAN = np.array([0.485, 0.456, 0.406]); STD = np.array([0.229, 0.224, 0.225])
seg = ort.InferenceSession(P + "/segformer_b0_ade_256.onnx", providers=["CPUExecutionProvider"]) if A.model in ("segformer256", "and") else None
yop = ort.InferenceSession(P + "/yolop/yolop-320-320.onnx", providers=["CPUExecutionProvider"]) if A.model in ("yolop320", "and") else None
gl = ort.InferenceSession(P + "/guideline.onnx", providers=["CPUExecutionProvider"])
det = ort.InferenceSession(P + "/dfine/dfine_n_coco.onnx", providers=["CPUExecutionProvider"])
COCO = json.load(open("/home/user/basir-run-/app/src/main/assets/coco80_names.json"))
def letterbox(img, s, fill):
    w, h = img.size; r = min(s / w, s / h); nw, nh = round(w * r), round(h * r)
    c = Image.new("RGB", (s, s), fill); dw, dh = (s - nw) // 2, (s - nh) // 2; c.paste(img.resize((nw, nh), Image.BILINEAR), (dw, dh)); return c, dw, dh, nw, nh, r
def walkable(img):
    t = {}; m = None
    if seg is not None:
        t0 = time.perf_counter()
        x = ((np.asarray(img.resize((256, 256)), np.float32) / 255 - MEAN) / STD).transpose(2, 0, 1)[None].astype(np.float32)
        lab = seg.run(None, {"pixel_values": x})[0][0].argmax(0)
        # same under-the-feet rule with memory as the app (engine UnderfootClasses)
        m = np.asarray(Image.fromarray((MEMORY.mask(lab, NOW[0]) * 255).astype(np.uint8)).resize(img.size, Image.NEAREST)) > 0
        t["seg"] = (time.perf_counter() - t0) * 1000
    if yop is not None:
        t0 = time.perf_counter()
        c, dw, dh, nw, nh, _ = letterbox(img, 320, (114, 114, 114))
        x = ((np.asarray(c, np.float32) / 255 - MEAN) / STD).transpose(2, 0, 1)[None].astype(np.float32)
        da = yop.run(["drive_area_seg"], {"images": x})[0][0].argmax(0)[dh:dh + nh, dw:dw + nw]
        y = np.asarray(Image.fromarray((da == 1).astype(np.uint8) * 255).resize(img.size, Image.NEAREST)) > 0
        m = y if m is None else (m & y); t["yolop"] = (time.perf_counter() - t0) * 1000
    return m, t
def line(img):
    t0 = time.perf_counter()
    x = np.asarray(img.resize((513, 513)), np.uint8)[None]
    o = gl.run(None, {gl.get_inputs()[0].name: x})[0][0]
    p = o[..., 1] if o.shape[-1] == 2 else o[..., 0]
    return np.asarray(Image.fromarray((p * 255).clip(0, 255).astype(np.uint8)).resize(img.size, Image.BILINEAR)) > 127, (time.perf_counter() - t0) * 1000
def boxes(img):
    t0 = time.perf_counter()
    c, L, T, nw, nh, r = letterbox(img, 640, (0, 0, 0))
    lg, bx = det.run(None, {"pixel_values": (np.asarray(c, np.float32) / 255).transpose(2, 0, 1)[None]})
    p = 1 / (1 + np.exp(-lg[0])); out_ = []
    for k in np.nonzero(p.max(1) >= 0.4)[0]:
        cx, cy, bw, bh = bx[0][k] * 640; W0, H0 = img.size
        x1, y1, x2, y2 = ((cx - bw / 2 - L) / r) / W0, ((cy - bh / 2 - T) / r) / H0, ((cx + bw / 2 - L) / r) / W0, ((cy + bh / 2 - T) / r) / H0
        out_.append((max(0, x1), max(0, y1), min(1, x2), min(1, y2), float(p[k].max()), COCO.get(str(int(p[k].argmax())), str(int(p[k].argmax())))))
    return out_, (time.perf_counter() - t0) * 1000
MEMORY = UnderfootMemory(); NOW = [0]
per = []; jobs = []
for i, f in enumerate(frames):
    NOW[0] = int(i * 1000 / A.fps)
    img = Image.open(f).convert("RGB")
    m, tm = walkable(img); ln, tl = line(img); bxs, td = boxes(img)
    mp = f"{out}/m/{i:05d}.png"; Image.fromarray(m.astype(np.uint8) * 255).save(mp)
    np.save(f"{out}/m/{i:05d}_line.npy", np.packbits(ln))
    per.append(dict(tm, line=tl, det=td, boxes=bxs))
    jobs.append(f"{mp},{A.hfov},{A.height},{A.pitch}," + ";".join(f"{a:.4f}:{b:.4f}:{c:.4f}:{d:.4f}:{s:.2f}:{l.replace(':', ' ').replace(',', ' ').replace(';', ' ')}" for a, b, c, d, s, l in bxs) + f",{f}")
open(out + "/jobs.csv", "w").write("\n".join(jobs) + "\n")
subprocess.run(["java", "-cp", f"{R}/tools/kt/visual.jar:{S}/kc/kotlinc/lib/kotlin-stdlib.jar", "VisualHarnessKt", out + "/jobs.csv", out + "/engine.csv", str(A.speed), A.smoothing, A.centreline], check=True, capture_output=True)
eng = list(csv.DictReader(open(out + "/engine.csv")))
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"; big = ImageFont.truetype(FONT, max(14, A.width // 40)); sm = ImageFont.truetype(FONT, max(11, A.width // 64))
STATE_AR = {"TRACK_CENTERED": "على المسار", "CORRECT_LEFT": "صحّح يساراً", "CORRECT_RIGHT": "صحّح يميناً", "CURVE_LEFT": "منعطف يسار", "CURVE_RIGHT": "منعطف يمين",
            "OBSTACLE_WARNING": "عائق أمامك", "STOP_REQUIRED": "توقف", "TRACK_LOST": "فُقد المسار", "VISION_UNCERTAIN": "رؤية غير مؤكدة"}
def P2(s, W, H): return [(float(a) * W, float(b) * H) for a, b in (q.split(":")[:2] for q in s.split())] if s else []
for i, f in enumerate(frames):
    img = Image.open(f).convert("RGB"); W, H_ = img.size; e = eng[i]; pf = per[i]
    m = np.asarray(Image.open(f"{out}/m/{i:05d}.png")) > 0
    ln = np.unpackbits(np.load(f"{out}/m/{i:05d}_line.npy"))[:W * H_].reshape(H_, W).astype(bool)
    base = np.asarray(img).astype(np.float32)
    base[m] = base[m] * 0.6 + np.array([40, 200, 90]) * 0.4                     # 3 segmentation map
    edge = np.asarray(Image.fromarray(m.astype(np.uint8) * 255).filter(ImageFilter.FIND_EDGES)) > 0
    base[edge] = [255, 255, 255]                                                 # 4 boundaries
    base[ln] = [230, 40, 230]                                                    # 2 guide line
    im = Image.fromarray(base.astype(np.uint8)); d = ImageDraw.Draw(im, "RGBA")
    rb = [tuple(map(float, q.split(":"))) for q in e["ribbon"].split()] if e["ribbon"] else []
    if len(rb) >= 2:                                                             # 6 virtual path
        poly = [(a * W, b * H_) for a, b, _, _ in rb] + [(c * W, dd * H_) for _, _, c, dd in reversed(rb)]
        d.polygon(poly, fill=(255, 170, 30, 110), outline=(255, 170, 30, 255))
    ext = P2(e["extension"], W, H_)
    for k in range(0, len(ext) - 1, 2): d.line([ext[k], ext[k + 1]], fill=(255, 200, 90, 255), width=3)  # 7 dashed continuation
    cen = P2(e["centre"], W, H_)
    if len(cen) >= 2: d.line(cen, fill=(60, 220, 255, 255), width=3)              # 5 path centre
    conf_obs = [o for o in e["obstacles"].split(";") if o.endswith("true")]
    for a, b, c, dd, s, l in pf["boxes"]:                                        # 8 obstacles
        d.rectangle([a * W, b * H_, c * W, dd * H_], outline=(255, 60, 60, 255) if conf_obs else (255, 140, 140, 255), width=3)
        d.text((a * W + 3, b * H_ + 2), f"{l} {s:.2f}", font=sm, fill=(255, 255, 255, 255))
    d.rectangle([0, 0, W, big.size * 3.4], fill=(10, 16, 34, 200))               # 9 state + 10 timing
    st = STATE_AR.get(e["state"], e["state"]); cmd = e["cmd"] + (" " + e["side"] if e["side"] else "")
    d.text((W - 10, 6), f"{st}  ·  ثقة {float(e['conf']):.2f}", font=big, fill=(255, 255, 255, 255), anchor="ra")
    d.text((10, 6), f"{e['state']}  {cmd}  mode {e['mode']}", font=sm, fill=(200, 210, 230, 255))
    tm = "  ".join(f"{k} {v:.0f}" for k, v in pf.items() if k != "boxes") + f"  grid {float(e['ms_grid']):.1f}  plan {float(e['ms_plan']):.1f}  guide {float(e['ms_guide']):.2f} ms"
    d.text((10, 6 + big.size * 1.5), tm, font=sm, fill=(255, 210, 120, 255))
    d.text((10, 6 + big.size * 2.4), f"offset {float(e['offset']):+.2f} m  heading {float(e['heading']):+.1f}°  curvature {float(e['curv']):+.3f}  frame {i}", font=sm, fill=(200, 210, 230, 255))
    im.save(f"{out}/o/{i:05d}.jpg", quality=88)
mp4 = f"{out}/{name}.mp4"
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-framerate", str(A.fps), "-i", f"{out}/o/%05d.jpg", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "24", mp4], check=True)
# per-frame record (what the video shows, as numbers) and a Label Studio import for human ground truth
with open(f"{out}/frames.csv", "w", newline="") as fh:
    w = csv.writer(fh); w.writerow(["frame", "state", "cmd", "side", "conf", "mode", "ms_walkable", "ms_line", "ms_detector", "ms_grid", "ms_plan", "ms_guide", "boxes"])
    for i, e in enumerate(eng):
        pf = per[i]; w.writerow([i, e["state"], e["cmd"], e["side"], e["conf"], e["mode"], round(sum(v for k, v in pf.items() if k in ("seg", "yolop")), 1), round(pf["line"], 1), round(pf["det"], 1), e["ms_grid"], e["ms_plan"], e["ms_guide"], len(pf["boxes"])])
json.dump([{"data": {"image": f"/data/local-files/?d={os.path.relpath(fr, R)}"}} for fr in frames[::max(1, int(A.fps))]], open(f"{out}/label_studio_tasks.json", "w"), indent=1)
open(f"{out}/label_studio_config.xml", "w").write("""<View>
  <Image name="img" value="$image"/>
  <PolygonLabels name="ground" toName="img"><Label value="walkable"/><Label value="not_walkable"/><Label value="unknown"/></PolygonLabels>
  <PolygonLabels name="lines" toName="img"><Label value="guide_line"/><Label value="lane_line"/></PolygonLabels>
  <RectangleLabels name="obj" toName="img"><Label value="person"/><Label value="runner"/><Label value="bicycle"/><Label value="static_obstacle"/></RectangleLabels>
</View>
""")
print(json.dumps(dict(video=mp4, frames=len(frames), states={s: sum(e["state"] == s for e in eng) for s in set(e["state"] for e in eng)},
                      median_ms={k: round(float(np.median([p[k] for p in per])), 1) for k in per[0] if k != "boxes"})))
