"""G7: every library clip has, in visual_reports/round4, the same-input runs of the 0.12.0 engine and the round-4
engine (overlay video + per-frame engine record each); video_benchmark.csv has both rows for every clip; its felt
stop totals are recomputed here from the engine records and must match."""
import csv, glob, os, sys
R = "/home/user/basir-run-/BasirRun_Research"
lib = [r for r in csv.DictReader(open(R + "/manifests/video_dataset_manifest.csv")) if r["file"]]
bench = list(csv.DictReader(open(R + "/benchmarks/results/video_benchmark.csv")))
for r in lib:
    d = glob.glob(f"{R}/visual_reports/round4/{r['category']}/{r['video_id']}_segformer256_*/")
    if not d: sys.exit("no run for " + r["video_id"])
    d = d[0]
    for tag in ("visual_base", "visual"):
        if not (os.path.exists(d + f"engine_{tag}.csv") and glob.glob(d + f"*_{tag}.mp4") and os.path.getsize(glob.glob(d + f"*_{tag}.mp4")[0]) > 10_000):
            sys.exit(f"missing {tag} run or overlay for {r['video_id']}")
        e = list(csv.DictReader(open(d + f"engine_{tag}.csv")))
        stops = sum(x["felt"] == "stop" for x in e)
        row = [b for b in bench if b["clip"] == r["video_id"] and b["walkable_channel"] == "segformer256" and b["engine"] == ("0.12.0" if tag == "visual_base" else "round4")]
        if len(row) != 1 or int(row[0]["felt_stops"]) != stops: sys.exit(f"summary mismatch {r['video_id']} {tag}")
    if not os.path.exists(d + "terrain_visual.csv"): sys.exit("no terrain record " + r["video_id"])
for k in ("felt_stops_per_min", "direction_cues_per_min", "side_flips_per_min", "stop_required_share", "uncertain_share", "ms_sum_sequential"):
    if k not in bench[0]: sys.exit("missing metric " + k)
print(f"{len(lib)} clips x 2 engines verified"); print("BENCH_OK")
