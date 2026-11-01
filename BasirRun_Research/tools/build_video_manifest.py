"""Builds manifests/video_dataset_manifest.csv for the round-4 library (videos/<category>/*.mp4) plus the
rejected and reference-only items. Technical fields (duration, resolution, fps) come from ffprobe; licence and
source come from SANPO metadata or archive.org item metadata (videos/_ia_raw/ia_fetch.csv); the visual fields
(lines, curves, people, obstacles, lighting, ground seen) are from inspecting 4 frames per clip (contact
sheets), written below as INSPECTED."""
import csv, glob, os, subprocess
R = "/home/user/basir-run-/BasirRun_Research"
sess = {r["session"][:8]: r for r in csv.DictReader(open(R + "/datasets/sanpo/sanpo_real_sessions.csv"))}
ia = {r["identifier"]: r for r in csv.DictReader(open(R + "/videos/_ia_raw/ia_fetch.csv"))}
IA_ID = {"ia_boring_dallas_jogging": "BoringDallas02202017Jogging", "ia_flickr_cliff_street_walk": "Flickr-28584951987", "ia_flickr_table_mountain_dam_wall": "Flickr-33229616254",
         "ia_hiking_long_trail_mt_abe": "HikingLongTrailToMtAbe", "ia_cc0_forest_path_b": "treadmill-pov-walking-path-video-for-workouts"}
# (painted lines, curves, people, obstacles, lighting, ground seen) from the contact sheets
INSPECTED = {
 "owner_athletics_track": ("yes: white lane lines + numbers", "yes: track bend", "none", "none", "daylight", "red tartan track"),
 "owner_night_field": ("no", "no", "none", "bench, plants", "night, phone light", "dirt / grass"),
 "sanpo_cloudy_lowvis_60_ibYCD": ("no", "no", "many, close", "people, shop stalls", "dusk / low light", "pavement"),
 "sanpo_night_or_cloudy_zKsJQMv6": ("curb edges only", "slight", "none", "low wall, curb", "overcast", "concrete sidewalk beside road"),
 "ia_cc0_forest_path_a": ("no", "yes", "none", "trees, roots", "mixed: deep shade, low sun, fog", "dirt trail, asphalt path, sand"),
 "ia_cc0_forest_path_b": ("no", "yes", "none", "trees, hedges", "shade, sun glare", "dirt / leaf trail"),
 "ia_flickr_yugawara_hiking": ("no", "yes", "none", "rocks, roots", "forest shade", "rocky forest trail"),
 "sanpo_rural_terrain_IxFctRJL": ("no", "slight", "none", "trees", "shade", "dirt / grass"),
 "sanpo_rural_terrain_kXJGW5ax": ("no", "no", "few", "fence, roots", "sun flare", "path with roots, grass"),
 "sanpo_rural_terrain_uWmvsRfe": ("no", "yes", "none", "vegetation", "heavy motion blur", "forest dirt trail"),
 "sanpo_gravel_GCV2vQ7-": ("no", "slight", "none", "none", "overcast", "gravel track with grass edges"),
 "sanpo_gravel_nXpof7fY": ("no", "slight", "one runner ahead", "none", "overcast", "gravel track with grass edges"),
 "sanpo_gravel_zsUwfXG4": ("no", "no", "none", "none", "low sun, long shadows", "gravel / dirt"),
 "ia_flickr_table_mountain_dam_wall": ("no", "no", "none", "railing, water", "bright sun", "concrete dam-wall walkway (low contrast)"),
 "sanpo_dark_ground_8xZbveqy": ("no", "no", "none", "fence, puddle", "sun", "dark gravel lane"),
 "sanpo_crowd_eEHioWre": ("no", "slight", "several ahead", "benches, lamp posts", "daylight", "park asphalt path"),
 "sanpo_many_obstacles_dB-D3W80": ("no", "no", "few, close", "railings, signs, snow piles", "sun / shade", "pavers, snow patches"),
 "sanpo_open_terrain_BY-Y6r_c": ("no", "yes", "several, one crossing close", "bollard, fence", "overcast", "park asphalt"),
 "sanpo_open_terrain_N-jJAWL6": ("no", "slight", "pair walking close", "lamp posts, fence", "overcast", "park asphalt with leaves"),
 "sanpo_open_terrain_qLpkgfo5": ("no", "yes", "none", "trees", "motion blur", "forest dirt trail"),
 "sanpo_terrain_ground_pQcPNeko": ("no", "yes", "none", "rocks, trees", "shade", "forest dirt / leaves with rocks"),
 "ia_hiking_long_trail_mt_abe": ("no", "yes", "one hiker", "rocks, roots", "forest shade / open rock", "rocky mountain trail, bare rock"),
 "ia_boring_dallas_jogging": ("no", "yes: road bends", "none", "none", "overcast", "narrow asphalt road with grass edges"),
 "sanpo_jogging_66XHCmoc": ("no", "no", "none", "none", "overcast", "dirt / grass track"),
 "sanpo_jogging_NFJk5-KA": ("no", "no", "group ahead", "bleachers, tent", "sun", "concrete path next to sports field"),
 "sanpo_jogging_NiTQ4jek": ("curb edges", "no", "none", "parked cars, signs, tree", "sun", "sidewalk"),
 "sanpo_jogging_XGAIMFoh": ("curb edges", "no", "none", "signs, cars", "sun", "sidewalk"),
 "sanpo_jogging_fyfUW3nG": ("no", "no", "child with ball, stroller", "fence, gate", "sun", "plaza concrete"),
 "sanpo_jogging_tjIoesYz": ("no", "no", "none", "fence", "low sun, lens flare", "asphalt beside fence"),
 "sanpo_jogging_uy5vEGS6": ("crosswalk", "no", "few", "poles, cars at a junction", "overcast", "sidewalk / crossing"),
 "sanpo_running_OLLkkVZ1": ("no", "yes", "none", "vegetation", "heavy motion blur", "dirt trail"),
 "sanpo_running_oGmaqcOI": ("curb edges", "no", "one ahead", "pole, cars", "sun", "sidewalk"),
 "ia_flickr_cliff_street_walk": ("yes: yellow road line", "yes", "few", "rocks, parked cars", "bright sun", "coastal path / road edge"),
 "ia_walk_through_haiti": ("no", "no", "several", "vehicles, stalls", "daylight", "street, unpaved edges"),
 "sanpo_colour_ground_8WF0e5NO": ("crosswalk", "no", "few", "pillars", "indoor/outdoor mix", "coloured floor, crossing"),
 "sanpo_colour_ground_SWELFgFF": ("no", "no", "few", "trees, posts", "rain, wet", "wet pavement, coloured lane"),
 "sanpo_downhill_k1ElOc9v": ("no", "no", "few", "stairs, railings", "sun / shade", "plaza, stairs down"),
 "sanpo_motion_blur_nLaqyAdE": ("platform edge", "no", "several close", "pillars, people", "indoor", "station platform"),
 "sanpo_rural_1LbUU9SO": ("no", "no", "none", "shrubs", "sun", "worn asphalt / gravel"),
 "sanpo_stairs_ddEl0Jtx": ("curb", "no", "few", "parked cars, wall", "sun", "sidewalk"),
 "sanpo_suburban_9_t59BK_": ("no", "yes", "none", "fence", "low sun", "sloped asphalt path"),
 "sanpo_uphill_OauQwDOm": ("step edges", "no", "none", "stairs", "shade", "concrete, stairs up"),
 "sanpo_rain_wet_6uEJtqNV": ("no", "no", "several", "cars, poles", "rain, overcast", "wet sidewalk"),
 "sanpo_rain_wet_AMWsWL1l": ("no", "no", "few", "poles, construction", "rain, glare", "wet sidewalk"),
 "sanpo_rain_wet_jaFq58LA": ("crosswalk", "no", "many close", "people", "rain", "wet pavement, crossing"),
 "sanpo_snow_bSo4owzN": ("crosswalk", "no", "none", "snow piles", "low sun, long shadows", "asphalt with snow"),
 "sanpo_snow_hITui4Ne": ("no", "no", "few", "planters, sign", "sun / shade", "pavers with snow patches"),
}
def probe(p):
    o = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,r_frame_rate:format=duration", "-of", "default=nw=1", p], capture_output=True, text=True).stdout
    d = dict(l.split("=", 1) for l in o.split() if "=" in l); a, b = d["r_frame_rate"].split("/")
    return float(d["duration"]), f'{d["width"]}x{d["height"]}', round(float(a) / float(b), 2)
cols = ["video_id", "file", "category", "source_url", "download_source", "licence", "motion", "camera", "environment", "ground", "lighting", "painted_lines", "curves",
        "people", "obstacles", "duration_s", "resolution", "fps", "download_status", "inspection_status", "annotation_status"]
rows = []
for p in sorted(glob.glob(R + "/videos/*/*.mp4")):
    if "/_" in p: continue
    cat = p.split("/")[-2]; vid = os.path.basename(p)[:-4]; dur, res, fps = probe(p); ins = INSPECTED[vid]
    if vid.startswith("sanpo_"):
        s = sess[vid[-8:]]
        src = f"https://storage.googleapis.com/gresearch/sanpo_dataset/v0/sanpo-real/{s['session']}/"; dl = "SANPO-Real (Google Research), frames 150-449 of camera_chest/left, encoded 15 fps"
        lic = "CC BY 4.0 (SANPO)"; motion = s["ego_motion"].replace("EGO_MOTION_", "").lower(); cam = "chest stereo camera (ZED, left), ~1.3 m"
        env = s["environment"].replace("ENVIRONMENT_TYPE_", "").lower(); ann = "none (SANPO labels exist only on sparse frames of other sessions; Label Studio tasks exported)"
    elif vid.startswith("ia_"):
        r = ia[IA_ID[vid]] if vid in IA_ID else ia["treadmill-pov-walking-path-video-for-workouts"]
        src = r["source_url"] or "https://archive.org/details/treadmill-pov-walking-path-video-for-workouts"
        dl = f"archive.org item file '{r['file']}' from {r['start_s']} s" if vid in IA_ID else "archive.org item file 'zz silent.mp4' from 300 s"
        lic = r["licence"] if vid in IA_ID else "https://creativecommons.org/publicdomain/zero/1.0/"
        motion = "jogging" if "jogging" in vid else "walking / hiking"; cam = "handheld or body camera (unknown mount)"; env = cat.replace("_", " "); ann = "none"
    else:
        src = "owner's own recording"; dl = "uploaded by the owner: " + ("athletics_tracks/owner_dev_clip/clean.mp4 (overlay removed)" if "athletics" in vid else "raw_videos/user_field/20261030_225434.mp4"); lic = "owner's own video"; motion = "running" if "athletics" in vid else "walking"
        cam = "phone (dev mode, overlay removed)" if "athletics" in vid else "phone, portrait"; env = cat.replace("_", " "); ann = "none"
    rows.append(dict(video_id=vid, file=os.path.relpath(p, R), category=cat, source_url=src, download_source=dl, licence=lic, motion=motion, camera=cam, environment=env,
                     ground=ins[5], lighting=ins[4], painted_lines=ins[0], curves=ins[1], people=ins[2], obstacles=ins[3], duration_s=round(dur, 1), resolution=res, fps=fps,
                     download_status="downloaded, verified by ffprobe", inspection_status="inspected (4 frames)", annotation_status=ann))
# rejected and reference-only items, kept as references (not in the library)
for r in csv.DictReader(open(R + "/videos/_ia_raw/ia_fetch.csv")):
    used = r["identifier"] in IA_ID.values()
    if used: continue
    reason = "rejected: 270x480, below the 360 px minimum" if r["identifier"] == "Flickr-41487924072" else "rejected: 320x240, below the 360 px minimum" if r["identifier"] == "RussRentlerAWalkThroughHaiti" else r["status"] if not r["status"].startswith("downloaded") else ("rejected on inspection: still-photo slideshow, not a walking video" if "-dd-" in r["identifier"] or "dd" in r["identifier"][:12] else "rejected on inspection: static camera / webcam / bike / interviews / too short")
    rows.append(dict(video_id=r["identifier"], file="", category="rejected", source_url=r["source_url"] or f"https://archive.org/details/{r['identifier']}", download_source="archive.org",
                     licence=r["licence"], download_status="deleted after inspection" if r["status"].startswith("downloaded") else r["status"], inspection_status=reason))
for vid, url, why in (("whittier_spooktacular_5k_pov", "https://archive.org/details/cowhca-POV_-_We_re_Running_the_Whittier_Spooktacular_5K", "no reuse licence stated (municipal TV); downloaded before the licence check, deleted"),
                      ("pitt_street_bridge_walking_tour", "https://archive.org/details/tompsc-PITT_STREET_BRIDGE_4K_Full_Walking_Tour_Mount_Pleasant_SC", "no reuse licence stated; downloaded before the licence check, deleted"),
                      ("tokyo_asakusa_walk_mirror", "https://archive.org/details/youtube-xpSP8sp4rCk", "YouTube mirror without a licence; downloaded before the licence check, deleted"),
                      ("walking_tours_dataset", "https://huggingface.co/datasets/shawshankvkt/Walking_Tours", "CC-BY list of YouTube videos; YouTube refuses downloads from this server (bot check); reference only")):
    rows.append(dict(video_id=vid, file="", category="reference only", source_url=url, download_source="", licence="not stated" if "deleted" in why else "CC BY 4.0 (dataset card)", download_status=why, inspection_status="not in library"))
with open(R + "/manifests/video_dataset_manifest.csv", "w", newline="") as f:
    w = csv.DictWriter(f, cols); w.writeheader(); [w.writerow({c: r.get(c, "") for c in cols}) for r in rows]
print(sum(1 for r in rows if r.get("file")), "library clips;", len(rows), "rows")
