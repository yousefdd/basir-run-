"""Applies the current engine (as on the phone, 0.9.23) and the final engine (0.10) to an owner's video,
frame by frame (15 fps), and writes a side-by-side video with the vibration command in Arabic.
Camera geometry is not recorded in the video: assumed chest 1.3 m, tilt 17 deg (horizon in the frame),
horizontal field of view 40 deg (portrait 16:9 phone video)."""
import csv, glob, os, subprocess, sys, tempfile, numpy as np, onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont
import arabic_reshaper
from bidi.algorithm import get_display
sys.path.insert(0, os.path.dirname(__file__)); from underfoot import underfoot_mask
R = "/home/user/basir-run-/BasirRun_Research"; S = "/tmp/claude-0/-home-user-basir-run-/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad"
video, label = sys.argv[1], sys.argv[2]; HFOV, HEIGHT, PITCH = "40", "1.3", "17"
W, H = 540, 960
FONT = sys.argv[3] if len(sys.argv) > 3 else "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
font = ImageFont.truetype(FONT, 30); small = ImageFont.truetype(FONT, 22)
# Pillow lays out Arabic itself (libraqm): shaping and right-to-left order
def ar(t): return t
t = tempfile.mkdtemp(); os.makedirs(t + "/f"); os.makedirs(t + "/m"); os.makedirs(t + "/o")
subprocess.run(["ffmpeg", "-loglevel", "error", "-i", video, "-vf", f"fps=15,scale={W}:{H}", t + "/f/%05d.jpg"], check=True)
fs = sorted(glob.glob(t + "/f/*.jpg"))
sess = ort.InferenceSession(R + "/pretrained_models/segformer_b0_ade_256.onnx", providers=["CPUExecutionProvider"])
MEAN = np.array([0.485, 0.456, 0.406]); STD = np.array([0.229, 0.224, 0.225])
for f in fs:
    x = ((np.asarray(Image.open(f).resize((256, 256)), dtype=np.float32) / 255 - MEAN) / STD).transpose(2, 0, 1)[None].astype(np.float32)
    lab = sess.run(None, {"pixel_values": x})[0][0].argmax(0)
    Image.fromarray((underfoot_mask(lab) * 255).astype(np.uint8)).resize((W, H), Image.NEAREST).save(t + "/m/" + os.path.basename(f)[:-4] + ".png")
jobs = t + "/jobs.csv"; open(jobs, "w").write("".join(f"{t}/m/{os.path.basename(f)[:-4]}.png,{HFOV},{HEIGHT},{PITCH}\n" for f in fs))
lst = t + "/list.txt"; open(lst, "w").write("\n".join(fs))
kt = f"{S}/kc/kotlinc/lib/kotlin-stdlib.jar"
subprocess.run(["java", "-cp", f"{R}/tools/kt/seq.jar:{kt}", "SequenceHarnessKt", jobs, t + "/new.csv"], check=True, capture_output=True)
subprocess.run(["java", "-cp", f"{R}/tools/kt/seqcur.jar:{kt}", "SeqCurrentHarnessKt", lst, t + "/cur.csv", HFOV, HEIGHT, PITCH], check=True, capture_output=True)
subprocess.run(["java", "-cp", f"{R}/tools/kt/baseline.jar:{kt}", "BaselineHarnessKt", lst, t + "/region"], check=True, capture_output=True)
new = list(csv.DictReader(open(t + "/new.csv"))); cur = list(csv.DictReader(open(t + "/cur.csv")))
region = {os.path.basename(r["image"])[:-4]: r["status"] for r in csv.DictReader(open(t + "/region/baseline.csv"))}
def words(c):
    k, s, lv = c["cmd"], c["side"], c["level"]
    lvl = {"LOW": "خفيف", "MEDIUM": "متوسط", "HIGH": "قوي"}.get(lv, "")
    if k == "STOP": return "توقف", (239, 68, 68)
    if k == "OBSTACLE": return "عائق: انتبه", (245, 158, 11)
    if k == "CORRECT": return ("صحّح يسارًا " if s == "LEFT" else "صحّح يمينًا ") + lvl, (59, 130, 246)
    if k == "CURVE": return ("منعطف يسار قادم" if s == "LEFT" else "منعطف يمين قادم"), (25, 195, 180)
    return "على المسار", (34, 197, 94)
def pts(s): return [(float(p.split(":")[0]) * W, float(p.split(":")[1]) * H) for p in s.split()] if s else []
for i, f in enumerate(fs):
    k = os.path.basename(f)[:-4]; base = Image.open(f).convert("RGB")
    # left: current engine (lines from the contrast detector, as on the phone now)
    L = base.copy(); dl = ImageDraw.Draw(L); p = pts(cur[i]["points"])
    if len(p) > 1: dl.line(p, fill=(255, 80, 80), width=8)
    # right: final engine
    m = np.asarray(Image.open(f"{t}/m/{k}.png")) > 0; a = np.asarray(base).astype(np.float32); a[m] = a[m] * 0.62 + np.array([40, 200, 120]) * 0.38
    Rr = Image.fromarray(a.astype(np.uint8)); dr = ImageDraw.Draw(Rr); p = pts(new[i]["points"])
    if len(p) > 1: dr.line(p, fill=(255, 230, 40), width=9)
    out = Image.new("RGB", (2 * W + 12, H + 110), (8, 18, 38)); out.paste(L, (0, 110)); out.paste(Rr, (W + 12, 110)); d = ImageDraw.Draw(out)
    for x0, title, c in [(0, "المحرك الحالي (في هاتفك)", cur[i]), (W + 12, "المحرك النهائي 0.10", new[i])]:
        txt, col = words(c)
        d.text((x0 + W - 12, 10), ar(title), font=small, fill=(169, 182, 204), anchor="ra")
        d.rounded_rectangle((x0 + 12, 46, x0 + W - 12, 100), 14, fill=col)
        d.text((x0 + W // 2, 73), ar("الاهتزاز: " + txt), font=font, fill=(255, 255, 255), anchor="mm")
    d.text((W + 12 + 16, H + 96), ar("الأخضر: أرض صالحة · الأصفر: المسار الافتراضي"), font=small, fill=(255, 255, 255), anchor="ls")
    d.text((W - 16, H + 96), ar("الأحمر: مسار المحرك الحالي"), font=small, fill=(255, 255, 255), anchor="rs")
    out.save(f"{t}/o/{k}.jpg", quality=88)
os.makedirs(R + "/visual_reports", exist_ok=True)
dst = f"{R}/visual_reports/{label}_compare.mp4"
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-framerate", "15", "-i", f"{t}/o/%05d.jpg", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "26", "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2", dst], check=True)
for s in (2, 6, 9, 12):
    j = min(len(fs) - 1, s * 15); Image.open(f"{t}/o/{os.path.basename(fs[j])[:-4]}.jpg").save(f"{R}/visual_reports/{label}_at{s}s.jpg", quality=85)
import collections
def summary(rows, name):
    kinds = collections.Counter(r["cmd"] for r in rows); n = len(rows)
    sides = [r["side"] for r in rows if r["cmd"] in ("CORRECT", "CURVE")]
    flips = sum(1 for x, y in zip(sides, sides[1:]) if x != y)
    return f"{name}: {n} frames | " + ", ".join(f"{k} {v/n:.0%}" for k, v in kinds.most_common()) + f" | left/right flips {flips}"
print(summary(cur, "current"))
print(summary(new, "final"))
print("image-space fallback:", collections.Counter(region.values()))
# per-second timeline
for sec in range(0, len(fs) // 15 + 1):
    sl = slice(sec * 15, sec * 15 + 15)
    print(sec, "s | current:", collections.Counter(r["cmd"] + ("-" + r["side"] if r["side"] else "") for r in cur[sl]).most_common(2), "| final:", collections.Counter(r["cmd"] + ("-" + r["side"] if r["side"] else "") for r in new[sl]).most_common(2), "| lines seen (current):", sum(int(r["lines"]) > 0 for r in cur[sl]))
print(dst)
