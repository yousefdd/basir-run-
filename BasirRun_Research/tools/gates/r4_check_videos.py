"""G5: the library (manifest rows with a file) has >=30 clips; each file exists and ffprobe (re-run here) reads a
video stream of >=10 s with a short side >=360 px; each has a reuse licence (not empty / none / not stated);
>=8 distinct categories; no two clips share the same source and download segment."""
import csv, os, subprocess, sys
R = "/home/user/basir-run-/BasirRun_Research"
rows = [r for r in csv.DictReader(open(R + "/manifests/video_dataset_manifest.csv")) if r["file"]]
if len(rows) < 30: sys.exit(f"only {len(rows)} clips")
seen = set(); cats = set()
for r in rows:
    p = os.path.join(R, r["file"])
    if not os.path.exists(p): sys.exit("missing " + r["file"])
    o = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_type,width,height:format=duration", "-of", "default=nw=1", p], capture_output=True, text=True).stdout
    d = dict(l.split("=", 1) for l in o.split() if "=" in l)
    if d.get("codec_type") != "video": sys.exit("not a video: " + r["file"])
    if float(d["duration"]) < 10 or min(int(d["width"]), int(d["height"])) < 360: sys.exit(f"too short or small: {r['file']} {d}")
    lic = r["licence"].strip().lower()
    if not lic or lic in ("none", "not stated", "none stated") or "not stated" in lic: sys.exit("no licence: " + r["file"])
    k = (r["source_url"], r["download_source"])
    if k in seen: sys.exit("duplicate source segment: " + r["file"])
    seen.add(k); cats.add(r["category"])
if len(cats) < 8: sys.exit(f"categories {sorted(cats)}")
print(f"{len(rows)} clips verified, {len(cats)} categories"); print("VIDEOS_OK")
