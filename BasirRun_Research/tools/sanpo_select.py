"""Picks SANPO-Real TEST sessions with chest segmentation for each scene category of interest."""
import csv, json, urllib.request, random, sys
from concurrent.futures import ThreadPoolExecutor
RAW = "https://storage.googleapis.com/gresearch/sanpo_dataset/v0/sanpo-real/"
rows = {r["session"]: r for r in csv.DictReader(open("sanpo_real_sessions.csv"))}
test = [l.strip().split("/")[-1] for l in open("test_ids.txt") if l.strip()]
def has_masks(s):
    try:
        urllib.request.urlopen(RAW + s + "/camera_chest/left/frame_segmentation_annotation_type.json", timeout=20); return True
    except Exception: return False
cands = [s for s in test if s in rows]
with ThreadPoolExecutor(16) as ex: ok = dict(zip(cands, ex.map(has_masks, cands)))
cands = [s for s in cands if ok[s]]
cats = {
    "running": lambda r: r["ego_motion"] in ("EGO_MOTION_JOGGING", "EGO_MOTION_RUNNING"),
    "terrain_gravel": lambda r: "TERRAIN" in r["ground"] or "GRAVEL" in r["ground"],
    "park": lambda r: "PARK" in r["environment"],
    "pavers_colored": lambda r: "PAVERS" in r["ground"] or "COLOR" in r["ground"],
    "rain_snow_lowvis": lambda r: "RAIN" in r["weather"] or "SNOW" in r["weather"] or r["visibility"] != "VISIBILITY_HIGH",
    "crowded_obstacles": lambda r: r["obstacles"] == "NUM_OBSTACLES_HIGH" or r["human_traffic"] == "HUMAN_TRAFFIC_HIGH",
    "urban_lightgray": lambda r: "URBAN" in r["environment"] and "LIGHT_GRAY" in r["ground"],
}
random.seed(7); chosen = {}
for c, f in cats.items():
    pool = [s for s in cands if f(rows[s]) and s not in chosen]
    random.shuffle(pool)
    for s in pool[:4]: chosen[s] = c
print(len(cands), "test sessions with chest masks;", len(chosen), "chosen")
with open("selected_sessions.csv", "w", newline="") as fh:
    w = csv.writer(fh); w.writerow(["session", "category"] + list(next(iter(rows.values())).keys())[1:-1])
    for s, c in chosen.items(): w.writerow([s, c] + [rows[s][k] for k in list(rows[s].keys())[1:-1]])
