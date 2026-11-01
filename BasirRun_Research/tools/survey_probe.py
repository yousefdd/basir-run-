"""Shallow-clones each candidate repo (metadata + README + LICENSE only, no code is run) and records
what the repository itself says: last commit date, license file head, README keywords about weights,
ONNX/TFLite export, video, FPS. Output: github_repositories/survey_probe.csv"""
import csv, os, re, subprocess, sys
from concurrent.futures import ThreadPoolExecutor
R = "/home/user/basir-run-/BasirRun_Research"
P = R + "/github_repositories/_probe"
# (repo, category, role for Basir-Run, README keywords that must appear to confirm the role)
C = [
 ("hustvl/YOLOP", "drivable+lane multi-task", "unified road/lane model", ["drivable", "lane"]),
 ("CAIC-AD/YOLOPv2", "drivable+lane multi-task", "unified road/lane model", ["drivable", "lane"]),
 ("datvuthanh/HybridNets", "drivable+lane multi-task", "unified road/lane model", ["lane", "drivable"]),
 ("cfzd/Ultra-Fast-Lane-Detection-v2", "lane detection", "painted lines on curves", ["lane"]),
 ("Turoad/CLRNet", "lane detection", "painted lines", ["lane"]),
 ("google-research/project-guideline", "guide-line segmentation", "running line (shipped)", ["guideline"]),
 ("NVlabs/SegFormer", "semantic segmentation", "walkable ground (shipped, ADE20K B0)", ["segformer"]),
 ("XuJiacong/PIDNet", "real-time segmentation", "fast road segmentation", ["cityscapes"]),
 ("CoinCheung/BiSeNet", "real-time segmentation", "fast segmentation", ["bisenet"]),
 ("PaddlePaddle/PaddleSeg", "segmentation toolkit (PP-LiteSeg, PP-MobileSeg)", "mobile segmentation", ["paddleseg"]),
 ("mit-han-lab/efficientvit", "efficient ViT segmentation", "mobile segmentation", ["segment"]),
 ("hustvl/TopFormer", "mobile segmentation", "mobile segmentation", ["ade20k"]),
 ("facebookresearch/Mask2Former", "universal segmentation", "accuracy reference", ["mask2former"]),
 ("rayguan97/GANav-offroad", "off-road traversability segmentation", "rough terrain", ["rugd", "rellis"]),
 ("leggedrobotics/wild_visual_navigation", "self-supervised traversability", "rough terrain, online adaptation", ["traversab"]),
 ("nianticlabs/footprints", "walkable surface from one image", "walkable/traversable area incl. hidden", ["footprints", "traversable"]),
 ("google-research-datasets/sanpo_dataset", "egocentric walking dataset", "chest-camera ground truth (used)", ["sanpo"]),
 ("unmannedlab/RELLIS-3D", "off-road dataset", "trail ground truth", ["rellis"]),
 ("Peterande/D-FINE", "real-time detector", "objects/people (shipped, N)", ["d-fine"]),
 ("ShihuaHuang95/DEIM", "real-time detector", "objects/people, D-FINE successor", ["deim"]),
 ("lyuwenyu/RT-DETR", "real-time detector", "objects/people", ["rt-detr"]),
 ("ultralytics/ultralytics", "detector/segmenter toolkit", "objects (AGPL, not usable)", ["yolo"]),
 ("DepthAnything/Depth-Anything-V2", "monocular depth", "obstacle distance (shipped, Small)", ["depth"]),
 ("DepthAnything/Video-Depth-Anything", "video depth", "temporally stable depth", ["video"]),
 ("ifzhang/ByteTrack", "multi-object tracking", "track people across frames", ["track"]),
 ("NirAharon/BoT-SORT", "multi-object tracking", "track with camera-motion compensation", ["camera"]),
 ("princeton-vl/RAFT", "optical flow", "ego-motion / stabilisation", ["flow"]),
 ("casiez/OneEuroFilter", "signal filter", "low-lag smoothing of cues", ["filter"]),
 ("AtsushiSakai/PythonRobotics", "path tracking/planning reference", "pure pursuit, Stanley, MPC, DWA, A*", ["stanley", "path tracking"]),
 ("google-ai-edge/mediapipe", "on-device ML runtime", "EfficientDet-Lite0 fallback (shipped)", ["mediapipe"]),
 ("microsoft/onnxruntime", "inference runtime", "runs all models on Android (shipped)", ["onnx"]),
 ("open-mmlab/mmsegmentation", "segmentation toolkit", "training/eval reference", ["segmentation"]),
 # round 4: terrain, traversability, walkable area, blind navigation, tracking, odometry, video segmentation
 ("leggedrobotics/traversability_estimation", "traversability mapping", "rough-terrain traversability (robot, elevation map)", ["traversab"]),
 ("ctu-vras/traversability_estimation", "traversability segmentation", "images + point clouds traversability", ["traversab"]),
 ("RPL-CS-UCL/STEPP-Code", "semantic traversability", "traversability from pose-projected features", ["traversab"]),
 ("VIDA-NYU/city-surfaces", "sidewalk surface material segmentation", "surface type of sidewalks (material)", ["sidewalk"]),
 ("charbel-a-hC/SidewalkDetection", "sidewalk segmentation", "sidewalk for a robot", ["sidewalk"]),
 ("mknz/dsr-road-roughness-prediction", "sidewalk/road roughness", "surface roughness classes", ["segmentation"]),
 ("vivek3141/NavigationForBlind", "blind navigation (sidewalk)", "sidewalk following for blind users", ["sidewalk"]),
 ("navblind/Sidewalk-semantic-seg", "sidewalk segmentation for blind navigation", "sidewalk mask for blind users", ["sidewalk"]),
 ("SoulmateB/CurveLanes", "curved lane dataset", "curve benchmark", ["curvelanes"]),
 ("mo-vic/BezierLaneNet", "curve lane model", "Bezier-curve lane representation", ["bezier"]),
 ("fabiotosi92/ZipDepth", "lightweight monocular depth", "zero-shot depth for mobile", ["depth"]),
 ("FoundationVision/ByteTrack", "multi-object tracking", "track people (official home)", ["bytetrack"]),
 ("roboflow/trackers", "multi-object tracking", "clean tracker implementations", ["track"]),
 ("tryolabs/norfair", "multi-object tracking", "lightweight Kalman tracking", ["norfair"]),
 ("avisingh599/mono-vo", "monocular visual odometry", "camera ego-motion", ["odometry"]),
 ("irfanICMLL/ETC-Real-time-Per-frame-Semantic-video-segmentation", "temporal consistency for video segmentation", "stable masks over time", ["temporal"]),
 ("csiro-robotics/WildScenes", "natural-environment dataset (hand-carried)", "forest trails, 2D+3D labels", ["wildscenes"]),
 ("chaytonmin/Off-Road-Freespace-Detection", "off-road free space (ORFD)", "free space on trails", ["off-road"]),
 ("castacks/tartan_drive", "off-road driving dataset", "terrain + vehicle dynamics", ["tartan"]),
 ("robodhruv/visualnav-transformer", "visual navigation foundation model (ViNT/NoMaD)", "image-goal navigation", ["navigation"]),
 ("ydhongHIT/DDRNet", "real-time segmentation", "DDRNet official (fast street segmentation)", ["ddrnet"]),
 ("facebookresearch/sam2", "promptable video segmentation", "temporal mask propagation (reference)", ["video"]),
 ("microsoft/soundscape", "assistive navigation for blind people", "audio navigation app (open-sourced)", ["soundscape"]),
 ("NVIDIA/semantic-segmentation", "high-accuracy segmentation (Mapillary/Cityscapes)", "accuracy reference", ["cityscapes"]),
 ("isl-org/MiDaS", "monocular depth", "relative depth reference", ["depth"]),
]
def probe(c):
    repo, cat, role, kws = c
    d = f"{P}/{repo.replace('/', '__')}"
    row = dict(repo=repo, url=f"https://github.com/{repo}", category=cat, role=role)
    if not os.path.isdir(d):
        r = subprocess.run(["git", "clone", "-q", "--depth", "1", "--filter=blob:none", "--no-checkout", row["url"] + ".git", d], capture_output=True, text=True, timeout=300)
        if r.returncode:
            row.update(status="clone_failed", note=r.stderr.strip()[:120]); return row
    g = lambda *a: subprocess.run(["git", "-C", d, *a], capture_output=True, text=True, timeout=300).stdout
    row["last_commit"] = g("log", "-1", "--format=%cs").strip()
    files = g("ls-tree", "-r", "--name-only", "HEAD").splitlines()
    lic = next((f for f in files if re.fullmatch(r"(LICENSE|LICENCE|COPYING)(\.\w+)?", f, re.I)), None)
    text = g("show", f"HEAD:{lic}") if lic else ""
    head = " ".join(text.split()[:40])
    for k, v in [("Apache License", "Apache-2.0"), ("MIT License", "MIT"), ("Permission is hereby granted, free of charge", "MIT"), ("GNU AFFERO", "AGPL-3.0"),
                 ("GNU GENERAL PUBLIC", "GPL"), ("Creative Commons Attribution-NonCommercial", "CC BY-NC"), ("NVIDIA Source Code License", "NVIDIA SCL (non-commercial)"),
                 ("BSD", "BSD"), ("Niantic", "Niantic non-commercial"), ("Attribution 4.0", "CC BY 4.0"), ("Mozilla", "MPL-2.0")]:
        if k.lower() in text.lower(): row["license"] = v; break
    else: row["license"] = (head[:60] or "no license file")
    readmes = [f for f in files if re.fullmatch(r"readme(_en)?(\.\w+)?", f, re.I)]
    rt = " ".join(g("show", f"HEAD:{f}") for f in readmes).lower()
    row["readme_confirms_role"] = all(k in rt for k in kws)
    row["mentions_weights"] = bool(re.search(r"releases/download|drive\.google|huggingface\.co|pretrained|checkpoint|\.pth|weights", rt))
    row["mentions_onnx"] = "onnx" in rt; row["mentions_tflite"] = "tflite" in rt or "tensorflow lite" in rt or "litert" in rt
    row["mentions_video"] = "video" in rt
    fps = re.findall(r"(\d{2,4}(?:\.\d)?)\s*fps", rt)
    row["fps_claims_in_readme"] = ";".join(sorted(set(fps), key=float)[:6])
    row["files"] = len(files); row["status"] = "cloned"
    return row
rows = list(ThreadPoolExecutor(8).map(probe, C))
keys = ["repo", "url", "category", "role", "status", "last_commit", "license", "readme_confirms_role", "mentions_weights", "mentions_onnx", "mentions_tflite", "mentions_video", "fps_claims_in_readme", "files", "note"]
with open(R + "/github_repositories/survey_probe.csv", "w", newline="") as f:
    w = csv.DictWriter(f, keys); w.writeheader(); [w.writerow({k: r.get(k, "") for k in keys}) for r in rows]
for r in rows: print(r["repo"], r.get("status"), r.get("last_commit"), r.get("license"), r.get("readme_confirms_role"), r.get("fps_claims_in_readme"))
