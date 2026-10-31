"""Runs the visual test tool on 30 frames of a SANPO clip and checks every layer is produced:
an MP4, per-frame state/command/confidence and stage times, a virtual path, the dashed continuation,
detector boxes in the record, segmentation and line masks, and overlaid frames that differ from the source."""
import csv, glob, json, os, subprocess
import numpy as np
from PIL import Image
R = "/home/user/basir-run-/BasirRun_Research"
o = subprocess.run(["/tmp/tfl/bin/python", R + "/tools/visual_test.py", "--video", R + "/running_videos/sanpo_running_1uta1HvN.mp4", "--max-frames", "30",
                    "--speed", "2.8", "--model", "segformer256", "--out", "/tmp/claude-0/-home-user/db717bb8-cb66-5ecd-9762-13ebad485a32/scratchpad/vt_check"], capture_output=True, text=True, check=True).stdout
res = json.loads(o.strip().splitlines()[-1]); d = os.path.dirname(res["video"])
assert os.path.getsize(res["video"]) > 50_000 and res["frames"] == 30
eng = list(csv.DictReader(open(d + "/engine.csv"))); fr = list(csv.DictReader(open(d + "/frames.csv")))
assert len(eng) == 30 and len(fr) == 30
assert all(e["state"] and e["cmd"] and e["conf"] for e in eng), "state/command/confidence"
assert sum(bool(e["ribbon"]) for e in eng) >= 10, "virtual path"
assert sum(bool(e["extension"]) for e in eng) >= 10, "expected continuation"
assert all(float(r["ms_walkable"]) > 0 and float(r["ms_line"]) > 0 and float(r["ms_detector"]) > 0 for r in fr), "stage times"
assert sum(int(r["boxes"]) for r in fr) > 0, "detector boxes"
assert len(glob.glob(d + "/m/*.png")) == 30 and len(glob.glob(d + "/m/*_line.npy")) == 30, "segmentation + line masks"
a = np.asarray(Image.open(d + "/f/00010.jpg"), np.int16); b = np.asarray(Image.open(d + "/o/00010.jpg"), np.int16)
assert np.abs(a - b).mean() > 5, "overlay drawn"
assert os.path.exists(d + "/label_studio_tasks.json") and os.path.exists(d + "/label_studio_config.xml")
print("VISUAL_TOOL_OK")
