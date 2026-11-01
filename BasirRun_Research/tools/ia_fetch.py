"""Fetches one segment from each archive.org item whose licence allows reuse (Creative Commons or public
domain, read from the item's own metadata at fetch time), without downloading the whole file:
ffmpeg seeks over HTTP and copies `seconds` of video (no audio) starting at `start` (default: 25 % into
the item). Writes <out>/<id>.mp4 and appends a row to <out>/ia_fetch.csv with the item's licence, creator,
source URL, file name, and the start/length used.
Usage: python ia_fetch.py <out dir> <seconds> <identifier>[@start] ..."""
import csv, json, os, subprocess, sys, urllib.request
out, secs = sys.argv[1], int(sys.argv[2]); os.makedirs(out, exist_ok=True)
log = os.path.join(out, "ia_fetch.csv"); new = not os.path.exists(log)
env = dict(os.environ, http_proxy=os.environ.get("HTTPS_PROXY", ""))
with open(log, "a", newline="") as fh:
    w = csv.writer(fh)
    if new: w.writerow(["identifier", "title", "licence", "creator", "source_url", "file", "start_s", "length_s", "status"])
    for spec in sys.argv[3:]:
        ident, _, st = spec.partition("@")
        try:
            md = json.load(urllib.request.urlopen(f"https://archive.org/metadata/{ident}", timeout=60))
            m = md.get("metadata", {}); lic = m.get("licenseurl", "")
            if not any(k in lic for k in ("creativecommons.org", "publicdomain")):
                w.writerow([ident, m.get("title", ""), lic or "none stated", m.get("creator", ""), "", "", "", "", "skipped: no reuse licence"]); continue
            vids = [f for f in md.get("files", []) if f["name"].lower().endswith((".mp4", ".mpeg4", ".m4v", ".mov", ".ogv", ".webm", ".avi"))]
            def h(f):
                try: return int(f.get("height", 0))
                except ValueError: return 0
            good = [f for f in vids if 360 <= h(f) <= 1080] or [f for f in vids if h(f) == 0] or vids
            if not good:
                w.writerow([ident, m.get("title", ""), lic, m.get("creator", ""), "", "", "", "", "skipped: no video file"]); continue
            f = min(good, key=lambda f: int(f.get("size", 1 << 40)))
            length = float(f.get("length", 0) or 0)
            if ":" in str(f.get("length", "")):
                p = [float(x) for x in str(f["length"]).split(":")]; length = sum(v * 60 ** i for i, v in enumerate(reversed(p)))
            start = float(st) if st else max(0.0, length * 0.25 if length > secs * 2 else 0.0)
            url = f"https://archive.org/download/{ident}/" + urllib.request.quote(f["name"])
            dst = os.path.join(out, f"{ident}.mp4")
            r = subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ca_file", "/root/.ccr/ca-bundle.crt", "-ss", str(start), "-t", str(secs),
                                "-i", url, "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-vf", "scale='min(960,iw)':-2", dst],
                               env=env, capture_output=True, text=True, timeout=900)
            ok = r.returncode == 0 and os.path.exists(dst) and os.path.getsize(dst) > 50_000
            w.writerow([ident, m.get("title", ""), lic, m.get("creator", ""), f"https://archive.org/details/{ident}", f["name"], round(start, 1), secs,
                        "downloaded" if ok else "failed: " + r.stderr.strip()[-120:]])
            print(ident, "ok" if ok else "FAILED", lic, flush=True)
        except Exception as e:
            w.writerow([ident, "", "", "", "", "", "", "", f"failed: {e}"[:150]]); print(ident, "ERROR", e, flush=True)
        fh.flush()
