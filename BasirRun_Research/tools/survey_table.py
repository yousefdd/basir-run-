"""Merges the repo probe (survey_probe.csv: what each repository itself says, read from a real clone)
with our evaluation for Basir-Run: architecture, training data and viewpoint, Android path, licence
for this app, what WE measured (only where we ran it), and the decision."""
import csv
R = "/home/user/basir-run-/BasirRun_Research"
E = {  # repo: (architecture, training data / viewpoint, android path, measured by us, decision)
 "hustvl/YOLOP": ("CSPDarknet multi-task: detection + drivable + lane heads", "BDD100K, car dash camera", "ONNX files in repo (320/640/1280)", "SANPO 280 chest frames: corridor IoU 0.53 (320), false-walkable 0.19; with planner: 164 safe / 7 through obstacle; 41 ms desktop CPU", "not integrated: car viewpoint, low recall on sidewalks/trails; AND-fusion cuts obstacle crossings 16->4 but no-path frames 43->113"),
 "CAIC-AD/YOLOPv2": ("E-ELAN multi-task (YOLOv7-based)", "BDD100K, car", "TorchScript weights; ONNX needs export", "not run", "not tried: same car-view limits as YOLOP, YOLOv7 lineage"),
 "datvuthanh/HybridNets": ("EfficientNet-B3 + BiFPN multi-task", "BDD100K, car", "ONNX export script in repo", "not run", "not tried: heavier than YOLOP, same viewpoint gap"),
 "cfzd/Ultra-Fast-Lane-Detection-v2": ("row/column anchor classification on ResNet", "CULane/TuSimple/CurveLanes, car", "ONNX/TensorRT export in repo", "not run", "not suitable: assumes 2-4 lanes ahead in car perspective, no walkable area"),
 "Turoad/CLRNet": ("cross-layer refinement lane detector", "CULane/TuSimple/LLAMAS, car", "PyTorch only", "not run", "not suitable: car lanes; heavier"),
 "google-research/project-guideline": ("DeepLabV3+ MobileNetV3-small line segmentation", "purple guide line, chest phone camera", "TFLite in repo -> ONNX (99.97% agreement)", "line IoU 0.94 on its labelled frame vs 0.27-0.71 colour detector", "INTEGRATED (round 1) for purple lines; other lines stay on the colour detector"),
 "NVlabs/SegFormer": ("MiT-B0 transformer encoder + MLP decoder", "ADE20K (150 classes, mixed viewpoints)", "ONNX export 256x256 (tools/models/export_segformer.py)", "SANPO corridor IoU 0.74 (underfoot rule), RUGD 0.84; corridor false-walkable 0.37; with D-FINE + planner 195/280 safe, 16 through obstacle", "INTEGRATED (round 1); licence research/evaluation only -> must be replaced before any commercial use"),
 "XuJiacong/PIDNet": ("three-branch real-time segmentation (PID-inspired)", "Cityscapes/CamVid, car", "PyTorch; ONNX possible", "not run (weights on Google Drive)", "candidate replacement for SegFormer licence (MIT) but trained on car scenes; would need fine-tuning on SANPO/RUGD"),
 "CoinCheung/BiSeNet": ("BiSeNet V1/V2 two-path real-time segmentation", "Cityscapes/COCO-stuff/ADE20K", "ONNX/TensorRT/ncnn/OpenVINO in repo", "not run", "best licence-clean (MIT) candidate with ADE20K weights: next to benchmark against SegFormer"),
 "PaddlePaddle/PaddleSeg": ("toolkit incl. PP-LiteSeg / PP-MobileSeg", "Cityscapes/ADE20K", "Paddle -> ONNX via paddle2onnx", "not run (weights host bcebos blocked here)", "PP-MobileSeg ADE20K is a strong Apache-2.0 option; blocked by weight download"),
 "mit-han-lab/efficientvit": ("EfficientViT-Seg (linear attention)", "Cityscapes/ADE20K", "ONNX/TensorRT export in repo", "not run", "Apache-2.0 candidate with ADE20K weights; next to benchmark"),
 "hustvl/TopFormer": ("token pyramid mobile transformer seg", "ADE20K", "ONNX/TFLite demo mentioned in paper", "not run", "Apache-2.0 mobile candidate; repo inactive since 2022"),
 "facebookresearch/Mask2Former": ("masked-attention universal segmentation", "COCO/ADE20K/Cityscapes", "too heavy for phone", "not run", "accuracy reference only"),
 "rayguan97/GANav-offroad": ("group-wise attention off-road segmentation", "RUGD/RELLIS-3D (robot, off-road)", "mmseg PyTorch", "not run", "relevant for rough terrain; weights + mmseg 0.x stack heavy; candidate for trail fine-tuning ideas"),
 "leggedrobotics/wild_visual_navigation": ("self-supervised traversability from robot experience (DINO features)", "robot walking data, online learning", "ROS/GPU, not phone", "not run", "idea (learn traversability online) not portable to phone now"),
 "nianticlabs/footprints": ("predicts visible + hidden walkable ground from one image", "human-walked videos (KITTI/Matterport)", "PyTorch", "not run", "non-commercial licence; useful idea for occluded ground"),
 "google-research-datasets/sanpo_dataset": ("dataset tools (chest/head ZED cameras, panoptic labels, depth, poses)", "egocentric walking, urban + nature", "data", "USED: 280 labelled frames, 4 clips + 7 curve windows (camera poses)", "main benchmark source (CC BY 4.0 data)"),
 "unmannedlab/RELLIS-3D": ("off-road multimodal dataset", "robot (Warthog) off-road", "data", "not used (robot viewpoint, large)", "possible extra trail data; licence CC BY-NC-SA"),
 "Peterande/D-FINE": ("DETR with fine-grained distribution refinement", "COCO (80 classes)", "ONNX export (tools/models/export_dfine.py, verified vs PyTorch)", "SANPO: people recall 0.96, vehicles 0.91, static obstacles 0.24 (poles/signs not in COCO); 9% boxes on nothing; 55 ms desktop CPU", "INTEGRATED (earlier); static obstacles need depth/segmentation, not this detector"),
 "ShihuaHuang95/DEIM": ("D-FINE with dense O2O matching (faster training)", "COCO", "ONNX export in repo", "not run", "drop-in candidate for D-FINE-N; same COCO classes -> same static-obstacle gap"),
 "lyuwenyu/RT-DETR": ("real-time DETR", "COCO", "ONNX/TensorRT export", "not run", "older than D-FINE; no reason to switch"),
 "ultralytics/ultralytics": ("YOLO family toolkit", "COCO etc.", "TFLite/ONNX export", "not run", "AGPL-3.0: incompatible with a closed app; excluded"),
 "DepthAnything/Depth-Anything-V2": ("DINOv2 ViT + DPT depth", "synthetic + 62M pseudo-labelled images", "ONNX (Small, int8)", "not re-measured this round", "INTEGRATED Small (Apache-2.0); Base/Large are CC BY-NC"),
 "DepthAnything/Video-Depth-Anything": ("temporally consistent video depth", "video", "PyTorch, heavy", "not run", "temporal stability idea; too heavy for phone now"),
 "ifzhang/ByteTrack": ("tracking by associating every detection box", "MOT17/20", "ONNX/TensorRT/ncnn", "not run", "could replace the engine's constant-velocity tracker; current tracker adequate for 1-3 Hz detections"),
 "NirAharon/BoT-SORT": ("ByteTrack + camera-motion compensation + ReID", "MOT17/20", "PyTorch", "not run", "camera-motion compensation relevant for chest camera; candidate if people tracking flickers"),
 "princeton-vl/RAFT": ("recurrent all-pairs optical flow", "FlyingChairs/Things/Sintel", "heavy", "used OpenCV Farneback instead to measure clip yaw (turn direction ground truth)", "not for phone; classical flow enough for yaw"),
 "casiez/OneEuroFilter": ("adaptive low-pass filter", "-", "trivial to port (done in Kotlin)", "tuned vs EMA/Kalman/median on 5 real clips: One Euro = EMA (err 0.30), Kalman 0.35-0.40, median 0.44-0.47", "INTEGRATED (StableGuidance smoothing)"),
 "AtsushiSakai/PythonRobotics": ("reference implementations: pure pursuit, Stanley, MPC, DWA, potential field, A*", "-", "algorithms", "re-implemented in planner_sim.py with a human-runner cue model", "reference for the controller comparison"),
 "google-ai-edge/mediapipe": ("on-device ML tasks", "-", "Android native", "EfficientDet-Lite0 not re-measured (needs MediaPipe anchor decoder)", "INTEGRATED as fallback detector"),
 "microsoft/onnxruntime": ("inference runtime", "-", "Android AAR", "runs every model above", "INTEGRATED"),
 "open-mmlab/mmsegmentation": ("segmentation toolbox", "many", "export tools", "not run", "reference for training a licence-clean walkable model later"),
}
probe = {r["repo"]: r for r in csv.DictReader(open(R + "/github_repositories/survey_probe.csv"))}
keys = ["repo", "url", "category", "last_commit", "license", "license_ok_for_closed_app", "mentions_weights", "mentions_onnx", "mentions_tflite", "fps_claims_in_readme",
        "architecture", "training_data_viewpoint", "android_path", "measured_by_us", "decision"]
rows = []
for repo, (arch, data, android, meas, dec) in E.items():
    p = probe[repo]
    lic = p["license"]
    ok = "yes" if lic in ("Apache-2.0", "MIT", "BSD") else "no" if any(k in lic for k in ("AGPL", "NVIDIA", "non-commercial", "NC")) else "check"
    if repo == "DepthAnything/Depth-Anything-V2": ok = "Small only"
    rows.append(dict(repo=repo, url=p["url"], category=p["category"], last_commit=p["last_commit"], license=lic, license_ok_for_closed_app=ok,
                     mentions_weights=p["mentions_weights"], mentions_onnx=p["mentions_onnx"], mentions_tflite=p["mentions_tflite"], fps_claims_in_readme=p["fps_claims_in_readme"],
                     architecture=arch, training_data_viewpoint=data, android_path=android, measured_by_us=meas, decision=dec))
with open(R + "/research_notes/survey_evaluation.csv", "w", newline="") as f:
    w = csv.DictWriter(f, keys); w.writeheader(); w.writerows(rows)
print(len(rows), "rows;", sum(1 for r in rows if r["measured_by_us"] not in ("not run",) and not r["measured_by_us"].startswith("not ")), "measured by us")
