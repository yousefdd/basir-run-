"""Lists SANPO-Real sessions and their attributes (environment, ground, motion, light) into a CSV."""
import json, urllib.request, csv, sys
from concurrent.futures import ThreadPoolExecutor
API = "https://storage.googleapis.com/storage/v1/b/gresearch/o"
RAW = "https://storage.googleapis.com/gresearch/"
out = sys.argv[1]
sessions, tok = [], None
while True:
    u = f"{API}?prefix=sanpo_dataset/v0/sanpo-real/&delimiter=/&maxResults=1000&fields=prefixes,nextPageToken" + (f"&pageToken={tok}" if tok else "")
    d = json.load(urllib.request.urlopen(u)); sessions += d.get("prefixes", []); tok = d.get("nextPageToken")
    if not tok: break
sessions = [s for s in sessions if s.count("/") == 4 and "splits" not in s]
def info(p):
    try:
        d = json.load(urllib.request.urlopen(RAW + p + "description.json", timeout=30))
        m = d.get("session_video_metadata", {})
        return dict(session=p.split("/")[-2], ego_motion=m.get("ego_motion",""), environment=";".join(m.get("environment_types",[])),
                    ground=";".join(m.get("ground_appearances",[])), visibility=m.get("visibility",""), weather=";".join(m.get("weather_conditions",[])),
                    obstacles=m.get("num_obstacles",""), human_traffic=m.get("human_traffic",""), elevation=";".join(m.get("elevation_changes",[])),
                    motion_blur=m.get("motion_blur",""), keys=";".join(d.keys()))
    except Exception as e:
        return dict(session=p.split("/")[-2], keys="ERR " + str(e)[:60])
with ThreadPoolExecutor(16) as ex: rows = list(ex.map(info, sessions))
fields = ["session","ego_motion","environment","ground","visibility","weather","obstacles","human_traffic","elevation","motion_blur","keys"]
with open(out, "w", newline="") as f:
    w = csv.DictWriter(f, fields); w.writeheader(); [w.writerow({k: r.get(k, "") for k in fields}) for r in rows]
print(len(rows), "sessions")
