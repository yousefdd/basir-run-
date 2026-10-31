import csv, os, sys
R = "/home/user/basir-run-/BasirRun_Research"
need = ["video_id", "source_url", "license", "motion", "environment", "ground", "path_type", "lighting", "painted_lines", "curves", "obstacles", "camera", "duration", "download_status", "annotation_status"]
rows = list(csv.DictReader(open(R + "/video_dataset_manifest.csv")))
missing = [k for k in need if k not in rows[0]]
assert not missing, f"missing columns {missing}"
ath = [r for r in rows if r.get("category") == "athletics_track" or "athletics" in r["environment"]]
assert len(ath) >= 3, "athletics rows missing"
for r in ath:
    last = r["download_status"].split()[-1]
    ok = r["download_status"].startswith("downloaded") and (os.path.exists(last) or os.path.exists(R + "/" + last)) or r["download_status"].startswith("unavailable: ") and len(r["download_status"]) > 40
    assert ok, f"athletics row {r['video_id']} has neither a file nor a reason"
dl = [r for r in rows if r["download_status"].startswith("downloaded")]
curves = [r for r in rows if r.get("category") == "curve"]
for r in curves:
    assert os.path.exists(R + "/" + r["download_status"].split()[-1]), r["video_id"]
print(f"{len(rows)} rows, {len(dl)} downloaded, {len(curves)} curve clips present on disk, {len(ath)} athletics rows with explicit status")
print("MANIFEST_OK")
