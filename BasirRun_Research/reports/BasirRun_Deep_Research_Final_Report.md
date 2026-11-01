# Basir-Run — Deep Research Final Report (round 4)

Local commit at build time: `e7321fb`. All timings are desktop CPU (4 cores); **nothing was measured on a phone**. The system is **not** declared ready for independent running: no field test with a companion on a controlled track has been done (gate G14).

## 1. The old engine and its problems

Full audit, written before any change: `reports/BasirRun_Current_Engine_Audit.md`. In short: the 0.12.0 engine already understands walkable ground (SegFormer-B0 ADE20K + under-the-feet rule + free-space planner), so it is not lines-only, but it labels ground with ADE *object* classes, has no notion of doubt (walkable yes/no), fails on forest dirt trails (the trail under the feet came out as 'tree' 93 %), and took implausible detector classes ('bear' on a trail) as obstacles.

## 2. GitHub research

57 repositories, each verified by a real shallow clone (last commit and licence read from the repo; README confirms the role). Coverage by area (A terrain, B walkable/segmentation, C lanes/paths, D obstacles/depth/tracking, E navigation, F video/tracking/odometry): {'A': 14, 'B': 20, 'C': 8, 'D': 15, 'E': 6, 'F': 11, 'runtime': 1}. Gate check: `tools/gates/r4_check_survey.py`.

| Repository | Area | Licence (from the repo) | Last commit | Decision for Basir-Run |
|---|---|---|---|---|
| hustvl/YOLOP | B,C | MIT | 2023-10-20 | not integrated: car viewpoint, low recall on sidewalks/trails; AND-fusion cuts obstacle crossings 16->4 but no-path frames 43->113 |
| CAIC-AD/YOLOPv2 | B,C | MIT | 2023-03-24 | not tried: same car-view limits as YOLOP, YOLOv7 lineage |
| datvuthanh/HybridNets | B,C | MIT | 2023-12-01 | not tried: heavier than YOLOP, same viewpoint gap |
| cfzd/Ultra-Fast-Lane-Detection-v2 | C | MIT | 2023-04-19 | not suitable: assumes 2-4 lanes ahead in car perspective, no walkable area |
| Turoad/CLRNet | C | Apache-2.0 | 2022-07-03 | not suitable: car lanes; heavier |
| google-research/project-guideline | C | Apache-2.0 | 2026-10-15 | INTEGRATED (round 1) for purple lines; other lines stay on the colour detector |
| NVlabs/SegFormer | A,B | NVIDIA SCL (non-commercial) | 2023-06-13 | INTEGRATED (round 1); licence research/evaluation only -> must be replaced before any commercial use |
| XuJiacong/PIDNet | B | MIT | 2025-12-18 | candidate replacement for SegFormer licence (MIT) but trained on car scenes; would need fine-tuning on SANPO/RUGD |
| CoinCheung/BiSeNet | B | MIT | 2026-10-08 | best licence-clean (MIT) candidate with ADE20K weights: next to benchmark against SegFormer |
| PaddlePaddle/PaddleSeg | B | Apache-2.0 | 2025-11-21 | PP-MobileSeg ADE20K is a strong Apache-2.0 option; blocked by weight download |
| mit-han-lab/efficientvit | B | Apache-2.0 | 2025-09-05 | Apache-2.0 candidate with ADE20K weights; next to benchmark |
| hustvl/TopFormer | B | Apache-2.0 | 2022-04-24 | Apache-2.0 mobile candidate; repo inactive since 2022 |
| facebookresearch/Mask2Former | B | MIT | 2022-05-20 | accuracy reference only |
| rayguan97/GANav-offroad | A | Apache-2.0 | 2025-01-21 | relevant for rough terrain; weights + mmseg 0.x stack heavy; candidate for trail fine-tuning ideas |
| leggedrobotics/wild_visual_navigation | A,E | MIT | 2026-05-27 | idea (learn traversability online) not portable to phone now |
| nianticlabs/footprints | A,B | Niantic non-commercial | 2021-06-14 | non-commercial licence; useful idea for occluded ground |
| google-research-datasets/sanpo_dataset | A,B,D | Apache-2.0 | 2026-03-10 | main benchmark source (CC BY 4.0 data) |
| unmannedlab/RELLIS-3D | A | no license file | 2022-11-23 | possible extra trail data; licence CC BY-NC-SA |
| Peterande/D-FINE | D | Apache-2.0 | 2026-08-19 | INTEGRATED (earlier); static obstacles need depth/segmentation, not this detector |
| ShihuaHuang95/DEIM | D | Apache-2.0 | 2026-03-24 | drop-in candidate for D-FINE-N; same COCO classes -> same static-obstacle gap |
| lyuwenyu/RT-DETR | D | Apache-2.0 | 2026-10-09 | older than D-FINE; no reason to switch |
| ultralytics/ultralytics | D | AGPL-3.0 | 2026-10-30 | AGPL-3.0: incompatible with a closed app; excluded |
| DepthAnything/Depth-Anything-V2 | D | Apache-2.0 | 2026-03-24 | INTEGRATED Small (Apache-2.0); Base/Large are CC BY-NC |
| DepthAnything/Video-Depth-Anything | D,F | Apache-2.0 | 2025-10-07 | temporal stability idea; too heavy for phone now |
| ifzhang/ByteTrack | D,F | MIT | 2022-12-11 | could replace the engine's constant-velocity tracker; current tracker adequate for 1-3 Hz detections |
| NirAharon/BoT-SORT | D,F | MIT | 2022-10-30 | camera-motion compensation relevant for chest camera; candidate if people tracking flickers |
| princeton-vl/RAFT | F | BSD | 2025-08-24 | not for phone; classical flow enough for yaw |
| casiez/OneEuroFilter | F | no license file | 2026-08-05 | INTEGRATED (StableGuidance smoothing) |
| AtsushiSakai/PythonRobotics | E | MIT | 2026-10-24 | reference for the controller comparison |
| google-ai-edge/mediapipe | D | Apache-2.0 | 2026-10-30 | INTEGRATED as fallback detector |
| microsoft/onnxruntime | runtime | MIT | 2026-10-31 | INTEGRATED |
| open-mmlab/mmsegmentation | B | Apache-2.0 | 2024-03-22 | reference for training a licence-clean walkable model later |
| leggedrobotics/traversability_estimation | A | Copyright (c) 2015, Peter Fankhauser All rights reserved. Re | 2023-06-22 | not adopted: needs a metric elevation map; a phone has no lidar |
| ctu-vras/traversability_estimation | A | BSD | 2024-10-11 | reference: binary traversability idea used in the terrain zones (walkable vs not) |
| RPL-CS-UCL/STEPP-Code | A,E | no license file | 2026-03-11 | reference only: self-supervised from the robot's own footsteps; no licence file |
| VIDA-NYU/city-surfaces | A,B | Copyright 2020 Nvidia Corporation Redistribution and use in  | 2024-01-26 | reference for surface material classes; licence text is NVIDIA's (non-commercial) |
| charbel-a-hC/SidewalkDetection | B | MIT | 2024-07-20 | not adopted: narrow training data, no published accuracy on walking video |
| mknz/dsr-road-roughness-prediction | A | MIT | 2019-07-12 | reference: surface category idea (asphalt / paving / cobble); data is Berlin only |
| vivek3141/NavigationForBlind | B,E | no license file | 2018-03-23 | reference only (2018 demo, no licence file) |
| navblind/Sidewalk-semantic-seg | B | no license file | 2019-02-19 | reference only (2019, no licence file) |
| SoulmateB/CurveLanes | C | no license file | 2020-07-14 | not adopted: car viewpoint; athletics bends tested on the owner's clip instead |
| mo-vic/BezierLaneNet | C | MIT | 2021-02-21 | idea reference: a curve as a few control points (the engine's PathCurve already fits a polynomial) |
| fabiotosi92/ZipDepth | D | MIT | 2026-08-22 | candidate for a later depth swap (MIT); Depth Anything V2 Small stays until measured on a phone |
| FoundationVision/ByteTrack | D,F | MIT | 2022-12-11 | idea used: the engine's obstacle tracker associates by box overlap with runner advance |
| roboflow/trackers | D,F | Apache-2.0 | 2026-10-30 | reference for tracker choice |
| tryolabs/norfair | D,F | BSD | 2025-04-30 | reference; the engine keeps its own Kotlin tracker (no Python on Android) |
| avisingh599/mono-vo | F | MIT | 2017-04-18 | not adopted: the gyroscope gives heading more cheaply on a phone |
| irfanICMLL/ETC-Real-time-Per-frame-Semantic-video-segmentation | F | no license file | 2021-01-02 | idea used: temporal memory of under-feet classes instead of retraining |
| csiro-robotics/WildScenes | A | Apache-2.0 | 2025-06-03 | candidate test data for forest trails; download is large (CSIRO DAP) and not done this round |
| chaytonmin/Off-Road-Freespace-Detection | A,B | MIT | 2026-01-13 | reference: free space on unpaved roads; vehicle viewpoint |
| castacks/tartan_drive | A | MIT | 2026-02-10 | not adopted: vehicle viewpoint, very large bags |
| robodhruv/visualnav-transformer | E | MIT | 2024-09-14 | not adopted: needs goal images and a robot controller |
| ydhongHIT/DDRNet | B | MIT | 2023-06-19 | candidate for the phone: same off-road weights exist for DDRNet-23 (Apache); not exported this round |
| facebookresearch/sam2 | F | Apache-2.0 | 2024-12-15 | reference for temporal propagation; not for on-device use |
| microsoft/soundscape | E | MIT | 2023-02-16 | reference for assistive UX (no camera guidance) |
| NVIDIA/semantic-segmentation | B | Copyright 2020 Nvidia Corporation Redistribution and use in  | 2021-03-04 | accuracy reference only |
| isl-org/MiDaS | D | MIT | 2024-08-23 | superseded by Depth Anything V2 (shipped) |

## 3. Ten repositories examined in detail

| Repository | What we did with it / what it gave |
|---|---|
| NVlabs/SegFormer | Shipped walkable channel (B0 ADE20K 256). Measured again here as the baseline on SANPO dev/test and on all 45 clips. |
| google-research-datasets/sanpo_dataset | Same chest-camera viewpoint as Basir-Run. 280 human-labelled frames = ground truth; 37 continuous chest clips taken this round. |
| XuJiacong/PIDNet | Fast Cityscapes model (MIT). LiteRT version measured: walkable IoU 0.664 (below shipped), see street_models_sanpo.txt. |
| ydhongHIT/DDRNet | Real-time CNN; off-road DDRNet-23 weights (Apache-2.0 stated) exist from roboticslaburjc-org. Best candidate for a phone-speed terrain model; not exported this round. |
| rayguan97/GANav-offroad | Navigability groups for terrain (RUGD/RELLIS). The idea behind the CAUTION zone; not run (mmseg stack). |
| hustvl/YOLOP | Car-view drivable area; measured in round 2, weak on sidewalks/trails (IoU 0.53). |
| Peterande/D-FINE | Shipped detector (N). Its COCO classes produced the false 'bear'/'tie' fixed this round. |
| DepthAnything/Depth-Anything-V2 | Shipped depth (Small int8). Fixed-obstacle recall alone 0.26-0.35 on SANPO dev/test (terrain_obstacles.csv). |
| google-research/project-guideline | Shipped guide-line model; runner viewpoint. |
| open-mmlab/mmsegmentation | Source format of the off-road checkpoints; we read them with tools/safe_ckpt.py without running mmseg code. |

Models found on Hugging Face and handled this round: `nvidia/segformer-b0-finetuned-cityscapes-1024-1024` (tested), `roboticslaburjc-org/cross-dataset-visual-offroad-segmentation` (SegFormer-B2 RELLIS+GOOSE, Apache-2.0 stated; tested; its 8 classes are undocumented, so their meaning was inferred from co-occurrence with SANPO/RUGD human labels: 0 dynamic, 1 water, 2 obstacle/structure, 3 tall vegetation, 4 low vegetation, 5 rough/unpaved ground, 6 smooth ground, 7 sky), `JasonTStanley/RUGD-Segformer` (B5, no licence: reference only, not run), PIDNet-S LiteRT (tested).

## 4. Scientific papers

27 papers; every arXiv id was checked against the arXiv API (title and year must match), gate `tools/gates/r4_check_papers.py`. Published results are described by type only; we did not copy numbers we did not reproduce.

| Paper | Year | Link | Code | Phone | Fit | Limits |
|---|---|---|---|---|---|---|
| SANPO: A Scene Understanding, Accessibility and Human Navigation Dataset | 2023 | https://arxiv.org/abs/2309.12172 | https://github.com/google-research-datasets/sanpo_dataset | n/a (data) | high: same viewpoint as Basir-Run (chest camera, walking); our main ground truth | walking only, few running sessions (33 jogging, 4 running) |
| SegFormer: Simple and Efficient Design for Semantic Segmentation with Transformers | 2021 | https://arxiv.org/abs/2105.15203 | https://github.com/NVlabs/SegFormer | yes for B0 (shipped, 256 px) | high: shipped walkable channel | NVIDIA licence (research/evaluation only) |
| RELLIS-3D Dataset: Data, Benchmarks and Analysis | 2020 | https://arxiv.org/abs/2011.12954 | https://github.com/unmannedlab/RELLIS-3D | n/a | medium: off-road terrain classes; robot height ~1 m | vehicle/robot viewpoint, CC BY-NC-SA 3.0 |
| The GOOSE Dataset for Perception in Unstructured Environments | 2023 | https://arxiv.org/abs/2310.16788 | none listed | n/a | medium-high: fine terrain classes (the off-road model we tested is trained on it) | vehicle viewpoint |
| GANav: Efficient Terrain Segmentation for Robot Navigation in Unstructured Outdoor Environments | 2021 | https://arxiv.org/abs/2103.04233 | https://github.com/rayguan97/GANav-offroad | not exported by us | medium: navigability groups = the zone idea | robot viewpoint; mmseg stack |
| ORFD: A Dataset and Benchmark for Off-Road Freespace Detection | 2022 | https://arxiv.org/abs/2206.09907 | https://github.com/chaytonmin/Off-Road-Freespace-Detection | no (lidar input) | low-medium: free space on dirt roads | vehicle, uses lidar |
| Footprints and Free Space from a Single Color Image | 2020 | https://arxiv.org/abs/2004.06376 | https://github.com/nianticlabs/footprints | possible (small) | high conceptually: walkable for people incl. hidden | Niantic non-commercial licence |
| Wild Visual Navigation: Fast Traversability Learning via Pre-Trained Models and Online Self-Supervision | 2024 | https://arxiv.org/abs/2404.07110 | https://github.com/leggedrobotics/wild_visual_navigation | no (ViT backbone + online training) | medium: self-supervision from where the robot walked | heavy; robot-specific |
| Fast Traversability Estimation for Wild Visual Navigation | 2023 | https://arxiv.org/abs/2305.08510 | https://github.com/leggedrobotics/wild_visual_navigation | no | medium | as above |
| Watch Your STEPP: Semantic Traversability Estimation using Pose Projected Features | 2025 | https://arxiv.org/abs/2501.17594 | https://github.com/RPL-CS-UCL/STEPP-Code | no | medium: learns from where people walked (like SANPO walking paths) | no licence file; heavy |
| WildScenes: A Benchmark for 2D and 3D Semantic Segmentation in Large-scale Natural Environments | 2023 | https://arxiv.org/abs/2312.15364 | https://github.com/csiro-robotics/WildScenes | n/a | high: hand-carried in forests = trail runner viewpoint | large download; not taken this round |
| Knowledge Distillation for Semantic Segmentation: A Label Space Unification Approach | 2025 | https://arxiv.org/abs/2502.19177 | none listed | depends on student | medium: unified terrain ontology | no code listed |
| CitySurfaces: City-Scale Semantic Segmentation of Sidewalk Materials | 2022 | https://arxiv.org/abs/2201.02260 | https://github.com/VIDA-NYU/city-surfaces | no (heavy) | medium: surface material (asphalt, concrete, brick) | NVIDIA-derived licence; street-level not chest |
| Can we cover navigational perception needs of the visually impaired by panoptic segmentation? | 2020 | https://arxiv.org/abs/2007.10202 | none listed | depends | high: the same question Basir-Run asks | walking, not running |
| Trans4Trans: Efficient Transformer for Transparent Object Segmentation to Help Visually Impaired People Navigate in the Real World | 2021 | https://arxiv.org/abs/2107.03172 | none listed | yes (wearable demo) | medium: glass doors/walls (indoor) | indoor focus |
| Indoor Navigation Assistance for Visually Impaired People via Dynamic SLAM and Panoptic Segmentation with an RGB-D Sensor | 2022 | https://arxiv.org/abs/2204.01154 | none listed | RGB-D sensor | low-medium (indoor, depth sensor) | needs depth sensor |
| The Cityscapes Dataset for Semantic Urban Scene Understanding | 2016 | https://arxiv.org/abs/1604.01685 | https://github.com/mcordts/cityscapesScripts | n/a | medium: SegFormer-B0 Cityscapes tested here (knows pole/sign/sidewalk) | car viewpoint |
| BDD100K: A Diverse Driving Dataset for Heterogeneous Multitask Learning | 2018 | https://arxiv.org/abs/1805.04687 | https://github.com/bdd100k/bdd100k | n/a | low: car viewpoint (YOLOP trained on it) | domain gap to chest camera |
| YOLOP: You Only Look Once for Panoptic Driving Perception | 2021 | https://arxiv.org/abs/2108.11250 | https://github.com/hustvl/YOLOP | yes (ONNX) | low: measured weak on sidewalks (round 2) | car viewpoint |
| PIDNet: A Real-time Semantic Segmentation Network Inspired by PID Controllers | 2022 | https://arxiv.org/abs/2206.02066 | https://github.com/XuJiacong/PIDNet | yes (LiteRT export exists) | measured: walkable IoU 0.664 (worse than shipped) | Cityscapes viewpoint |
| Deep Dual-resolution Networks for Real-time and Accurate Semantic Segmentation of Road Scenes | 2021 | https://arxiv.org/abs/2101.06085 | https://github.com/ydhongHIT/DDRNet | yes (fast CNN) | high as phone backbone: off-road DDRNet weights exist (Apache) | not exported this round |
| Masked-attention Mask Transformer for Universal Image Segmentation | 2021 | https://arxiv.org/abs/2112.01527 | https://github.com/facebookresearch/Mask2Former | no (too heavy) | reference only | heavy |
| Depth Anything V2 | 2024 | https://arxiv.org/abs/2406.09414 | https://github.com/DepthAnything/Depth-Anything-V2 | yes (Small, shipped int8) | high: fixed-obstacle channel | relative depth; small objects missed |
| D-FINE: Redefine Regression Task in DETRs as Fine-grained Distribution Refinement | 2024 | https://arxiv.org/abs/2410.13842 | https://github.com/Peterande/D-FINE | yes (N, shipped) | high: people/objects | COCO classes include implausible ones (bear, tie) seen as false alarms |
| ByteTrack: Multi-Object Tracking by Associating Every Detection Box | 2021 | https://arxiv.org/abs/2110.06864 | https://github.com/FoundationVision/ByteTrack | yes (association only) | idea used in obstacle tracking | needs a detector |
| RAFT: Recurrent All-Pairs Field Transforms for Optical Flow | 2020 | https://arxiv.org/abs/2003.12039 | https://github.com/princeton-vl/RAFT | heavy for phone | used offline to measure clip yaw | heavy |
| ViNT: A Foundation Model for Visual Navigation | 2023 | https://arxiv.org/abs/2306.14846 | https://github.com/robodhruv/visualnav-transformer | no | low: needs goal images | robot-specific |

## 5. Best models tested

Same 280 SANPO chest frames and the same ground truth for every channel (session split, see section 9). Desktop times per frame: shipped ADE B0 256 ≈ 45-93 ms in the clip runs (video_benchmark.csv, ms_walkable); Cityscapes B0 512 165 ms; off-road B2 512 296 ms (more than 4x the shipped model, so by the pre-registered rule it could only ship behind an off-by-default flag).

```
reference (shipped): depth only 16/54 fixed obstacles, false 4/145; SegFormer ADE20K walkable IoU 0.739, false-walkable 0.366
pidnet_s_cityscapes_1024: walkable corridor IoU 0.664, false-walkable 0.379
   fixed obstacles pole/light/sign: alone 4/54 (4+0), false alarms 2/145; OR depth 17/54, false 6/145
   fixed obstacles +fence: alone 7/54 (5+2), false alarms 6/145; OR depth 18/54, false 10/145
segformer_b0_cityscapes_768: walkable corridor IoU 0.755, false-walkable 0.405
   fixed obstacles pole/light/sign: alone 8/54 (2+6), false alarms 3/145; OR depth 21/54, false 7/145
   fixed obstacles +fence: alone 20/54 (7+13), false alarms 5/145; OR depth 29/54, false 9/145
segformer_b0_cityscapes_512: walkable corridor IoU 0.756, false-walkable 0.422, 165 ms/frame desktop CPU
   fixed obstacles pole/light/sign: alone 10/54 (5+5), false alarms 6/145; OR depth 21/54, false 10/145
   fixed obstacles +fence: alone 23/54 (10+13), false alarms 7/145; OR depth 29/54, false 11/145
segformer_b0_cityscapes_384: walkable corridor IoU 0.759, false-walkable 0.446, 78 ms/frame desktop CPU
   fixed obstacles pole/light/sign: alone 8/54 (4+4), false alarms 7/145; OR depth 20/54, false 11/145
   fixed obstacles +fence: alone 20/54 (9+11), false alarms 8/145; OR depth 26/54, false 12/145
```

## 6. Datasets

| Dataset | Source | Viewpoint | Seq/still | Licence | Domain gap | Use |
|---|---|---|---|---|---|---|
| SANPO-Real | https://github.com/google-research-datasets/sanpo_dataset | chest + head stereo cameras on a walking person (~1.3 m) | video sequences (15 fps) | CC BY 4.0 (dataset README) | none: same viewpoint as Basir-Run; walking dominates (664 of 701 sessions) | used: 280 labelled frames (ground truth), 34+ clips |
| RUGD | http://rugd.vision/ | small ground robot, ~0.5-1 m | video sequences + labelled frames | research use per site (site unreachable from here; not redistributed) | medium: low camera, off-road trails, vehicle motion | used: 184 test frames (group labels) for off-road check |
| RELLIS-3D | https://github.com/unmannedlab/RELLIS-3D | Warthog off-road robot (~1 m) | sequences | CC BY-NC-SA 3.0 (README) | medium: robot viewpoint and speed | not downloaded; the tested off-road model is trained on it |
| GOOSE | https://goose-dataset.de/ | vehicle in unstructured terrain | image sequences | see website (not verified from here: page needs JavaScript) | medium-high: vehicle height, but fine terrain classes | not downloaded; the tested off-road model is trained on it |
| Freiburg Forest | http://deepscene.cs.uni-freiburg.de/ | robot in forest trails | stills | research (site unreachable from here) | medium: forest trail, robot | not downloaded (site unreachable from this container) |
| WildScenes | https://github.com/csiro-robotics/WildScenes | hand-carried sensor walking through forests | sequences | per paper/website (README has no licence line) | low: carried by a walking person in natural terrain | not downloaded this round (size); top candidate for forest-trail ground truth |
| TartanDrive | https://github.com/castacks/tartan_drive | ATV off-road | ROS bags | MIT (repo code); data terms on AirLab site | high: vehicle speed and height | not downloaded |
| ORFD | https://github.com/chaytonmin/Off-Road-Freespace-Detection | off-road vehicle camera + lidar | sequences | Apache 2.0 (README: code and dataset) | high: vehicle viewpoint | not downloaded |
| Mapillary Vistas | https://www.mapillary.com/dataset/vistas | crowd-sourced street-level (phones, cars, some pedestrians) | stills | research licence (CC BY-NC-SA per site terms; needs sign-up) | medium: mixed viewpoints incl. pedestrian | not downloaded (sign-up); Mask2Former-Mapillary listed as accuracy reference |
| Cityscapes | https://www.cityscapes-dataset.com/ | car dash camera (Germany) | stills (+ sequences) | non-commercial (official licence page) | high: car viewpoint; but sidewalk/pole/terrain classes are useful | not downloaded; SegFormer-B0 Cityscapes model tested |
| BDD100K | https://github.com/bdd100k/bdd100k | car dash camera (US) | video clips | BDD100K licence (site) | high: car viewpoint | not downloaded; YOLOP (trained on it) tested in round 2 |
| ADE20K | https://groups.csail.mit.edu/vision/datasets/ADE20K/ | mixed scenes | stills | research terms (site) | medium: generic scenes; few trails | not downloaded; shipped SegFormer is trained on it |
| CurveLanes | https://github.com/SoulmateB/CurveLanes | car dash camera | stills | see repo (no licence file) | high: car viewpoint | not downloaded |
| Project Guideline test data | https://github.com/google-research/project-guideline | chest phone on a runner following a painted line | video | Apache 2.0 (repo) | none: runner viewpoint | used in round 1 (guideline model) |

## 7. Videos actually downloaded

45 clips in `videos/<category>/`, each read by ffprobe (≥ 10 s, ≥ 360 px) and inspected on 4 frames; manifest `manifests/video_dataset_manifest.csv` (36 more rows record rejected or reference-only items with the reason). Sources: SANPO-Real chest camera (CC BY 4.0), archive.org items whose own metadata gives a Creative Commons or public-domain licence, and the owner's own videos. YouTube (including the CC-BY Walking Tours list) refuses downloads from this server, Wikimedia Commons rate-limited the API, Pexels/Pixabay answered 403: those stay references. Three clips downloaded before the licence check (Whittier 5K, Pitt Street Bridge, a Tokyo YouTube mirror) had no reuse licence and were deleted; the audit's first observations on them are kept as text only.

| Clip | Category | Motion | Ground | Lighting | Curves | People | Licence | s | Resolution |
|---|---|---|---|---|---|---|---|---|---|
| owner_athletics_track | athletics_tracks | running | red tartan track | daylight | yes: track bend | none | owner's own video | 24.0 | 640x360 |
| owner_night_field | difficult_lighting | walking | dirt / grass | night, phone light | no | none | owner's own video | 13.2 | 1920x1080 |
| sanpo_cloudy_lowvis_60_ibYCD | difficult_lighting | walking | pavement | dusk / low light | no | many, close | CC BY 4.0 (SANPO) | 19.3 | 960x540 |
| sanpo_night_or_cloudy_zKsJQMv6 | difficult_lighting | walking | concrete sidewalk beside road | overcast | slight | none | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| ia_cc0_forest_path_a | forest_trails | walking / hiking | dirt trail, asphalt path, sand | mixed: deep shade, low sun, fog | yes | none | https://creativecommons.org/publicdomain/zero/1.0/ | 120.1 | 1280x720 |
| ia_cc0_forest_path_b | forest_trails | walking / hiking | dirt / leaf trail | shade, sun glare | yes | none | https://creativecommons.org/publicdomain/zero/1.0/ | 60.0 | 960x540 |
| sanpo_rural_terrain_IxFctRJL | forest_trails | walking | dirt / grass | shade | slight | none | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| sanpo_rural_terrain_kXJGW5ax | forest_trails | walking | path with roots, grass | sun flare | no | few | CC BY 4.0 (SANPO) | 19.5 | 960x540 |
| sanpo_rural_terrain_uWmvsRfe | forest_trails | jogging | forest dirt trail | heavy motion blur | yes | none | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| sanpo_gravel_GCV2vQ7- | gravel_paths | walking | gravel track with grass edges | overcast | slight | none | CC BY 4.0 (SANPO) | 19.7 | 960x540 |
| sanpo_gravel_nXpof7fY | gravel_paths | walking | gravel track with grass edges | overcast | slight | one runner ahead | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| sanpo_gravel_zsUwfXG4 | gravel_paths | walking | gravel / dirt | low sun, long shadows | no | none | CC BY 4.0 (SANPO) | 19.5 | 960x540 |
| ia_flickr_table_mountain_dam_wall | low_contrast | walking / hiking | concrete dam-wall walkway (low contrast) | bright sun | no | none | https://creativecommons.org/licenses/by-nc-sa/2.0/ | 15.6 | 960x540 |
| sanpo_dark_ground_8xZbveqy | low_contrast | walking | dark gravel lane | sun | no | none | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| sanpo_crowd_eEHioWre | obstacles | walking | park asphalt path | daylight | slight | several ahead | CC BY 4.0 (SANPO) | 19.7 | 960x540 |
| sanpo_many_obstacles_dB-D3W80 | obstacles | walking | pavers, snow patches | sun / shade | no | few, close | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| sanpo_open_terrain_BY-Y6r_c | park_paths | walking | park asphalt | overcast | yes | several, one crossing close | CC BY 4.0 (SANPO) | 19.7 | 960x540 |
| sanpo_open_terrain_N-jJAWL6 | park_paths | walking | park asphalt with leaves | overcast | slight | pair walking close | CC BY 4.0 (SANPO) | 19.4 | 960x540 |
| sanpo_open_terrain_qLpkgfo5 | park_paths | walking | forest dirt trail | motion blur | yes | none | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| sanpo_terrain_ground_pQcPNeko | park_paths | walking | forest dirt / leaves with rocks | shade | yes | none | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| ia_hiking_long_trail_mt_abe | rocky_surfaces | walking / hiking | rocky mountain trail, bare rock | forest shade / open rock | yes | one hiker | http://creativecommons.org/licenses/by-nc-nd/4.0/ | 60.1 | 854x480 |
| ia_boring_dallas_jogging | running | jogging | narrow asphalt road with grass edges | overcast | yes: road bends | none | http://creativecommons.org/publicdomain/mark/1.0/ | 60.0 | 960x540 |
| sanpo_jogging_66XHCmoc | running | jogging | dirt / grass track | overcast | no | none | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| sanpo_jogging_NFJk5-KA | running | jogging | concrete path next to sports field | sun | no | group ahead | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| sanpo_jogging_NiTQ4jek | running | jogging | sidewalk | sun | no | none | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| sanpo_jogging_XGAIMFoh | running | jogging | sidewalk | sun | no | none | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| sanpo_jogging_fyfUW3nG | running | jogging | plaza concrete | sun | no | child with ball, stroller | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| sanpo_jogging_tjIoesYz | running | jogging | asphalt beside fence | low sun, lens flare | no | none | CC BY 4.0 (SANPO) | 18.3 | 960x540 |
| sanpo_jogging_uy5vEGS6 | running | jogging | sidewalk / crossing | overcast | no | few | CC BY 4.0 (SANPO) | 17.3 | 960x540 |
| sanpo_running_OLLkkVZ1 | running | running | dirt trail | heavy motion blur | yes | none | CC BY 4.0 (SANPO) | 19.4 | 960x540 |
| sanpo_running_oGmaqcOI | running | running | sidewalk | sun | no | one ahead | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| ia_flickr_cliff_street_walk | walking | walking / hiking | coastal path / road edge | bright sun | yes | few | https://creativecommons.org/licenses/by-nc-sa/2.0/ | 29.5 | 640x360 |
| sanpo_colour_ground_8WF0e5NO | walking | walking | coloured floor, crossing | indoor/outdoor mix | no | few | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| sanpo_colour_ground_SWELFgFF | walking | walking | wet pavement, coloured lane | rain, wet | no | few | CC BY 4.0 (SANPO) | 19.7 | 960x540 |
| sanpo_downhill_k1ElOc9v | walking | walking | plaza, stairs down | sun / shade | no | few | CC BY 4.0 (SANPO) | 19.1 | 960x540 |
| sanpo_motion_blur_nLaqyAdE | walking | walking | station platform | indoor | no | several close | CC BY 4.0 (SANPO) | 19.3 | 960x540 |
| sanpo_rural_1LbUU9SO | walking | walking | worn asphalt / gravel | sun | no | none | CC BY 4.0 (SANPO) | 18.5 | 960x540 |
| sanpo_stairs_ddEl0Jtx | walking | walking | sidewalk | sun | no | few | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| sanpo_suburban_9_t59BK_ | walking | walking | sloped asphalt path | low sun | yes | none | CC BY 4.0 (SANPO) | 18.8 | 960x540 |
| sanpo_uphill_OauQwDOm | walking | walking | concrete, stairs up | shade | no | none | CC BY 4.0 (SANPO) | 18.9 | 960x540 |
| sanpo_rain_wet_6uEJtqNV | wet_surfaces | walking | wet sidewalk | rain, overcast | no | several | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| sanpo_rain_wet_AMWsWL1l | wet_surfaces | walking | wet sidewalk | rain, glare | no | few | CC BY 4.0 (SANPO) | 20.0 | 960x540 |
| sanpo_rain_wet_jaFq58LA | wet_surfaces | walking | wet pavement, crossing | rain | no | many close | CC BY 4.0 (SANPO) | 19.9 | 960x540 |
| sanpo_snow_bSo4owzN | wet_surfaces | walking | asphalt with snow | low sun, long shadows | no | none | CC BY 4.0 (SANPO) | 17.1 | 960x540 |
| sanpo_snow_hITui4Ne | wet_surfaces | walking | pavers with snow patches | sun / shade | no | few | CC BY 4.0 (SANPO) | 18.6 | 960x540 |

## 8. Distribution of the library

- Category: 10 running, 9 walking, 5 wet_surfaces, 5 forest_trails, 4 park_paths, 3 gravel_paths, 3 difficult_lighting, 2 obstacles, 2 low_contrast, 1 rocky_surfaces, 1 athletics_tracks
- Motion: 28 walking, 9 jogging, 5 walking / hiking, 3 running
- Licence: 37 CC BY 4.0 (SANPO), 2 owner's own video, 2 https://creativecommons.org/publicdomain/zero/1.0/, 2 https://creativecommons.org/licenses/by-nc-sa/2.0/, 1 http://creativecommons.org/publicdomain/mark/1.0/, 1 http://creativecommons.org/licenses/by-nc-nd/4.0/
- Total length: 18.4 min. Athletics track: only the owner's clip (no licensed track POV found); running: 10 clips (SANPO jogging/running + a public-domain road jog).

## 9. Model comparison (labelled)

Split by SANPO **session** (md5 even = dev for tuning, odd = test for reporting). Corridor = bottom 45 %, middle 40 % of the frame. Boundary F1 at 2 px (160x90). Criteria were written before tuning: `benchmarks/decision_criteria_round4.md`.

| method | split | IoU | Dice | precision | recall | F1 | false_walkable | boundary_F1 |
|---|---|---|---|---|---|---|---|---|
| cityscapes512 | dev | 0.731 | 0.844 | 0.877 | 0.814 | 0.844 | 0.475 | 0.618 |
| cityscapes512 | test | 0.785 | 0.879 | 0.975 | 0.8 | 0.879 | 0.261 | 0.631 |
| offroad512 | dev | 0.729 | 0.844 | 0.871 | 0.818 | 0.844 | 0.5 | 0.583 |
| offroad512 | test | 0.797 | 0.887 | 0.977 | 0.812 | 0.887 | 0.248 | 0.609 |
| shipped_ade | dev | 0.694 | 0.82 | 0.893 | 0.757 | 0.82 | 0.377 | 0.573 |
| shipped_ade | test | 0.787 | 0.881 | 0.969 | 0.807 | 0.881 | 0.334 | 0.596 |
| terrain_full | dev | 0.708 | 0.829 | 0.877 | 0.786 | 0.829 | 0.458 | 0.571 |
| terrain_full | test | 0.778 | 0.875 | 0.975 | 0.794 | 0.875 | 0.261 | 0.588 |
| terrain_selected | dev | 0.702 | 0.825 | 0.895 | 0.765 | 0.825 | 0.372 | 0.552 |
| terrain_selected | test | 0.748 | 0.856 | 0.973 | 0.764 | 0.856 | 0.274 | 0.564 |
| terrain_veto | dev | 0.662 | 0.796 | 0.896 | 0.717 | 0.796 | 0.346 | 0.53 |
| terrain_veto | test | 0.718 | 0.836 | 0.979 | 0.729 | 0.836 | 0.202 | 0.533 |
| terrain_veto_plus_smooth | dev | 0.712 | 0.832 | 0.892 | 0.779 | 0.832 | 0.39 | 0.568 |
| terrain_veto_plus_smooth | test | 0.762 | 0.865 | 0.975 | 0.777 | 0.865 | 0.259 | 0.578 |

Fixed obstacles within 4 m (frames; same ground truth as the depth benchmark):

| method | split | obstacle_frames | recall | false_negative_rate | clear_frames | false_positive_rate |
|---|---|---|---|---|---|---|
| depth only (shipped) | dev | 23 | 0.348 | 0.652 | 77 | 0.026 |
| offroad obstacle class | dev | 23 | 0.696 | 0.304 | 77 | 0.299 |
| offroad obstacle class OR depth | dev | 23 | 0.739 | 0.261 | 77 | 0.325 |
| depth only (shipped) | test | 31 | 0.258 | 0.742 | 68 | 0.029 |
| offroad obstacle class | test | 31 | 0.839 | 0.161 | 68 | 0.206 |
| offroad obstacle class OR depth | test | 31 | 0.871 | 0.129 | 68 | 0.221 |

Reading: the off-road model finds far more fixed obstacles than depth alone, but with 21-30 % false alarms on clear frames; no dev setting brought that under the 8 % limit. The off-road walkable channel is better on the test half and worse on the dev half; the dev-selected fusion lowered false-walkable on test but lost more than 0.01 IoU. Halves this different mean the labelled set (14 sessions per half) is too small to decide a model swap; forest-trail ground truth (WildScenes) is the next step.

## 10. Before and after (same clips, same inputs)

```
Round-4 video benchmark: 45 clips, 5 fps, same frames/masks/detections for both engines (desktop CPU).
0.12.0 engine: 18.3 min, felt stops 73 (3.98/min), felt obstacle warnings 53 (2.89/min), direction cues 2.39/min, side flips 0.44/min
round-4 engine: 18.3 min, felt stops 74 (4.04/min), felt obstacle warnings 52 (2.84/min), direction cues 2.39/min, side flips 0.49/min
clips whose felt stops changed (round4 - 0.12.0): sanpo_open_terrain_qLpkgfo5 +1
rough-surface cues: 4 in 18.3 min (0.22/min); on urban clips: 2; clips with a cue: ia_cc0_forest_path_a (1), sanpo_jogging_66XHCmoc (1), sanpo_jogging_uy5vEGS6 (1), ia_flickr_cliff_street_walk (1)

trail-like clips, walkable channel shipped ADE vs off-road B2 (round-4 engine): blocked-corridor share | felt stops | stop share
ia_cc0_forest_path_a: ADE 0.483 | 4 | 0.107   off-road 0.228 | 4 | 0.088   (walkable ms 57.4 vs 281.1)
ia_cc0_forest_path_b: ADE 1.0 | 4 | 0.147   off-road 0.973 | 3 | 0.207   (walkable ms 56.2 vs 274.4)
sanpo_rural_terrain_IxFctRJL: ADE 0.71 | 1 | 0.31   off-road 0.97 | 2 | 0.19   (walkable ms 59.8 vs 278.9)
sanpo_rural_terrain_kXJGW5ax: ADE 0.276 | 1 | 0.061   off-road 0.571 | 1 | 0.255   (walkable ms 59.2 vs 270.1)
sanpo_rural_terrain_uWmvsRfe: ADE 0.96 | 2 | 0.04   off-road 0.92 | 2 | 0.08   (walkable ms 56.7 vs 264.8)
sanpo_gravel_GCV2vQ7-: ADE 0.061 | 1 | 0.02   off-road 0.082 | 1 | 0.02   (walkable ms 56.5 vs 275.6)
sanpo_gravel_nXpof7fY: ADE 0.17 | 1 | 0.0   off-road 0.12 | 0 | 0.0   (walkable ms 54.5 vs 264.6)
sanpo_gravel_zsUwfXG4: ADE 0.021 | 0 | 0.0   off-road 0.021 | 0 | 0.0   (walkable ms 55.7 vs 273.2)
sanpo_open_terrain_BY-Y6r_c: ADE 0.041 | 2 | 0.061   off-road 0.439 | 1 | 0.071   (walkable ms 56.8 vs 263.6)
sanpo_open_terrain_N-jJAWL6: ADE 0.093 | 1 | 0.124   off-road 0.546 | 2 | 0.165   (walkable ms 54.8 vs 265.3)
sanpo_open_terrain_qLpkgfo5: ADE 0.55 | 3 | 0.05   off-road 0.97 | 1 | 0.15   (walkable ms 56.4 vs 273.2)
sanpo_terrain_ground_pQcPNeko: ADE 0.23 | 0 | 0.0   off-road 0.64 | 2 | 0.04   (walkable ms 55.7 vs 268.2)
ia_hiking_long_trail_mt_abe: ADE 0.89 | 4 | 0.513   off-road 0.967 | 3 | 0.533   (walkable ms 56.5 vs 267.2)

per clip (round-4 engine, shipped walkable channel): clip | category | min | stops/min | obstacle/min | direction/min | flips/min | stop share | uncertain share | blocked corridor | ms sum
owner_athletics_track | athletics_tracks | 0.4 | 0.0 | 0.0 | 5.0 | 0.0 | 0.0 | 0.0 | 0.175 | 160.7
owner_night_field | difficult_lighting | 0.22 | 4.55 | 4.55 | 0.0 | 0.0 | 0.076 | 0.258 | 0.091 | 165.9
sanpo_cloudy_lowvis_60_ibYCD | difficult_lighting | 0.32 | 6.19 | 0.0 | 0.0 | 0.0 | 0.299 | 0.701 | 0.711 | 166.2
sanpo_night_or_cloudy_zKsJQMv6 | difficult_lighting | 0.33 | 0.0 | 6.0 | 6.0 | 3.0 | 0.0 | 0.0 | 0.1 | 169.5
ia_cc0_forest_path_a | forest_trails | 2.0 | 2.0 | 2.0 | 4.0 | 1.0 | 0.107 | 0.263 | 0.483 | 160.7
ia_cc0_forest_path_b | forest_trails | 1.0 | 4.0 | 4.0 | 4.0 | 2.0 | 0.147 | 0.247 | 1.0 | 153.5
sanpo_rural_terrain_IxFctRJL | forest_trails | 0.33 | 3.0 | 3.0 | 3.0 | 0.0 | 0.31 | 0.54 | 0.71 | 168.9
sanpo_rural_terrain_kXJGW5ax | forest_trails | 0.33 | 3.06 | 3.06 | 0.0 | 0.0 | 0.061 | 0.643 | 0.276 | 166.3
sanpo_rural_terrain_uWmvsRfe | forest_trails | 0.33 | 6.0 | 3.0 | 0.0 | 0.0 | 0.04 | 0.87 | 0.96 | 153.3
sanpo_gravel_GCV2vQ7- | gravel_paths | 0.33 | 3.06 | 0.0 | 6.12 | 0.0 | 0.02 | 0.02 | 0.061 | 156.9
sanpo_gravel_nXpof7fY | gravel_paths | 0.33 | 3.0 | 6.0 | 0.0 | 0.0 | 0.0 | 0.25 | 0.17 | 156.6
sanpo_gravel_zsUwfXG4 | gravel_paths | 0.32 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.021 | 155.2
ia_flickr_table_mountain_dam_wall | low_contrast | 0.26 | 7.69 | 0.0 | 0.0 | 0.0 | 0.526 | 0.359 | 0.872 | 162.9
sanpo_dark_ground_8xZbveqy | low_contrast | 0.33 | 0.0 | 6.0 | 3.0 | 0.0 | 0.0 | 0.0 | 0.0 | 157.7
sanpo_crowd_eEHioWre | obstacles | 0.33 | 6.06 | 3.03 | 0.0 | 0.0 | 0.253 | 0.667 | 0.081 | 163.1
sanpo_many_obstacles_dB-D3W80 | obstacles | 0.33 | 6.0 | 6.0 | 3.0 | 0.0 | 0.06 | 0.4 | 0.08 | 158.9
sanpo_open_terrain_BY-Y6r_c | park_paths | 0.33 | 6.12 | 6.12 | 6.12 | 3.06 | 0.061 | 0.449 | 0.041 | 158.5
sanpo_open_terrain_N-jJAWL6 | park_paths | 0.32 | 3.09 | 6.19 | 6.19 | 3.09 | 0.124 | 0.299 | 0.093 | 153.0
sanpo_open_terrain_qLpkgfo5 | park_paths | 0.33 | 9.0 | 3.0 | 0.0 | 0.0 | 0.05 | 0.75 | 0.55 | 154.6
sanpo_terrain_ground_pQcPNeko | park_paths | 0.33 | 0.0 | 3.0 | 21.0 | 0.0 | 0.0 | 0.0 | 0.23 | 154.6
ia_hiking_long_trail_mt_abe | rocky_surfaces | 1.0 | 4.0 | 2.0 | 3.0 | 1.0 | 0.513 | 0.297 | 0.89 | 153.8
ia_boring_dallas_jogging | running | 1.0 | 1.0 | 6.0 | 0.0 | 0.0 | 0.063 | 0.047 | 0.1 | 154.5
sanpo_jogging_66XHCmoc | running | 0.33 | 6.0 | 0.0 | 0.0 | 0.0 | 1.0 | 0.0 | 0.19 | 158.6
sanpo_jogging_NFJk5-KA | running | 0.33 | 6.0 | 0.0 | 0.0 | 0.0 | 0.47 | 0.51 | 0.02 | 158.5
sanpo_jogging_NiTQ4jek | running | 0.33 | 6.0 | 0.0 | 0.0 | 0.0 | 0.87 | 0.11 | 0.04 | 156.5
sanpo_jogging_XGAIMFoh | running | 0.33 | 6.0 | 0.0 | 0.0 | 0.0 | 0.77 | 0.22 | 0.0 | 155.8
sanpo_jogging_fyfUW3nG | running | 0.33 | 6.0 | 0.0 | 0.0 | 0.0 | 0.82 | 0.15 | 0.14 | 156.9
sanpo_jogging_tjIoesYz | running | 0.3 | 6.59 | 0.0 | 0.0 | 0.0 | 0.802 | 0.187 | 0.055 | 155.6
sanpo_jogging_uy5vEGS6 | running | 0.29 | 6.9 | 0.0 | 0.0 | 0.0 | 0.529 | 0.448 | 0.057 | 156.2
sanpo_running_OLLkkVZ1 | running | 0.32 | 6.19 | 0.0 | 0.0 | 0.0 | 0.567 | 0.392 | 0.691 | 154.6
sanpo_running_oGmaqcOI | running | 0.33 | 6.0 | 0.0 | 0.0 | 0.0 | 0.59 | 0.36 | 0.16 | 156.2
ia_flickr_cliff_street_walk | walking | 0.49 | 8.11 | 6.08 | 2.03 | 0.0 | 0.108 | 0.331 | 0.453 | 154.8
sanpo_colour_ground_8WF0e5NO | walking | 0.33 | 3.0 | 9.0 | 9.0 | 0.0 | 0.01 | 0.11 | 0.04 | 157.3
sanpo_colour_ground_SWELFgFF | walking | 0.33 | 3.06 | 3.06 | 0.0 | 0.0 | 0.122 | 0.418 | 0.0 | 152.2
sanpo_downhill_k1ElOc9v | walking | 0.32 | 6.25 | 6.25 | 6.25 | 0.0 | 0.042 | 0.25 | 0.083 | 157.7
sanpo_motion_blur_nLaqyAdE | walking | 0.32 | 6.19 | 3.09 | 0.0 | 0.0 | 0.258 | 0.66 | 0.361 | 157.8
sanpo_rural_1LbUU9SO | walking | 0.31 | 0.0 | 3.23 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 157.1
sanpo_stairs_ddEl0Jtx | walking | 0.33 | 3.0 | 9.0 | 0.0 | 0.0 | 0.01 | 0.09 | 0.0 | 157.0
sanpo_suburban_9_t59BK_ | walking | 0.31 | 0.0 | 0.0 | 9.57 | 3.19 | 0.0 | 0.0 | 0.032 | 160.3
sanpo_uphill_OauQwDOm | walking | 0.31 | 6.38 | 0.0 | 0.0 | 0.0 | 0.021 | 0.574 | 0.511 | 153.7
sanpo_rain_wet_6uEJtqNV | wet_surfaces | 0.33 | 6.0 | 0.0 | 0.0 | 0.0 | 0.39 | 0.61 | 0.82 | 159.1
sanpo_rain_wet_AMWsWL1l | wet_surfaces | 0.33 | 6.0 | 3.0 | 0.0 | 0.0 | 0.29 | 0.69 | 0.37 | 155.7
sanpo_rain_wet_jaFq58LA | wet_surfaces | 0.33 | 6.06 | 3.03 | 0.0 | 0.0 | 0.293 | 0.687 | 0.323 | 153.4
sanpo_snow_bSo4owzN | wet_surfaces | 0.29 | 6.98 | 3.49 | 0.0 | 0.0 | 0.07 | 0.605 | 0.256 | 152.8
sanpo_snow_hITui4Ne | wet_surfaces | 0.31 | 3.23 | 0.0 | 0.0 | 0.0 | 0.108 | 0.645 | 0.409 | 156.7
```

## 11. The engine chosen

Decision, re-computed from the result files by `tools/make_decision.py` (gate G10):

```
walkable_fusion: NOT ADOPTED — test IoU 0.748 vs shipped 0.787 (need >= -0.01: False); false-walkable 0.274 vs 0.334 (need <=: True)
offroad_obstacle_class: NOT ADOPTED — no dev parameter set reached false-positive rate <= 0.08 with depth (untuned dev FPR 0.325, recall 0.739)
detection_filter_v2: NOT ADOPTED — 45 clips: felt stops 73 -> 74, felt obstacle warnings 53 -> 52, worst clip change +1
terrain_surface_cue: NOT ADOPTED — 4 cues in 18.3 min (0.22/min), urban 2, forest_path_a 1
```

Result: **the app's guidance engine stays as in 0.12.0**. Nothing tested this round met its pre-registered criterion (`benchmarks/decision_criteria_round4.md`, written before the video results), so nothing new is wired into the app:

- Walkable fusion with the off-road model: lost 0.039 IoU on the test sessions (limit 0.01).
- Off-road obstacle class: no setting reached <= 8 % false alarms on clear frames.
- DetectionFilter v2 (wild animals need score >= 0.8, 'tie' dropped): removed the false 'bear' obstacle on the forest trail, but those frames then became path-end stops and one clip (sanpo_open_terrain_qLpkgfo5) gained a felt stop; total felt stops 73 -> 74 against a limit of 'no more than 0.12.0'. Reverted.
- Terrain zones + 'rough surface' caution: fired once correctly (ia_cc0_forest_path_a, asphalt -> dirt trail) but also on two urban clips (limit one): sanpo_jogging_uy5vEGS6, where the camera turned toward a grass corner while the runner stayed on the pavement, and ia_flickr_cliff_street_walk, where a grey lane was read as natural ground (frames inspected). Reverted from the app.

The Terrain Understanding layer (engine `TerrainZones`: WALKABLE / CAUTION / OBSTACLE / UNCLEAR / UNVERIFIED from class, softmax top-2 margin, the walkable rule and connectivity to the feet, plus `SurfaceMonitor`) is kept in the engine module with its tests, **not called by the app**, for the research harness and the next round. Transparency note: SurfaceMonitor was rewritten twice after it missed the transition on ia_cc0_forest_path_a, which is also the clip named in the criterion; that part of the criterion was therefore met with tuning on the same clip. The urban limit, which was not tuned, is what failed.

## 12. Changes to the project

- `engine/.../TerrainZones.kt` (new, not used by the app): zones, summary, SurfaceMonitor. Tests: `engine/src/test/.../TerrainZonesTest.kt` (8 tests).
- App and the rest of the engine: no change kept (DetectionFilter v2 and the rough-surface cue were written, tested, then reverted by the decision above).
- App version 0.12.1 (code 36): carries only the athletics-track fixes made before this round (commit 18569b2: ground-class memory in `UnderfootClasses`, the planner keeps coasting a tracked lane for a moment, small sports items and birds need score >= 0.6). These are already in the '0.12.0 engine' column of section 10, because that jar was built from the pre-round-4 source.
- Research tools: `safe_ckpt.py` (reads whole-model pickles without running their code), `offroad_model.py`, `bench_terrain.py`, `bench_videos.py`, `bench_videos_summary.py`, `build_video_manifest.py`, `ia_fetch.py`, `make_decision.py`, `visual_test.py` (several engine jars on the same inputs, portrait/rotated video, off-road variant, terrain dump), `kt/TerrainHarness.kt`.

## 13. Problems fixed

- The research harness now runs several engine builds on the same frames, masks and detections, so before/after numbers compare engines and nothing else.
- The visual test squashed portrait and rotated phone videos (it assumed 16:9); fixed.
- onnxruntime on this desktop had moved to 1.29 and refused the guideline model; pinned to 1.20.1 like the app.
- Licence hygiene: three unlicensed clips removed; every library clip's licence is recorded from its source metadata.

## 14. Still open

- Forest dirt trails: the shipped ADE model still often fails to see them as walkable (the off-road model sees them, but is too slow and not proven on urban test frames).
- Fixed-obstacle recall from depth alone stays low (0.26 test); the off-road obstacle class finds more but with too many false alarms.
- Curves are still followed mostly as corrections.
- No phone timing, battery or heat numbers (G14); no field test (G10 of round 2, G14 here).
- False 'bear'/'tie' detections still reach the engine on rare frames; a filter that does not turn them into path-end stops is still needed.
- A surface cue needs a foot-area model that tells pavement from a grass verge (both false cues were at the pavement edge).

## 15. Technical limits

- A camera cannot know friction, wetness, firmness or hidden holes; CAUTION means 'natural or loose surface seen', never 'safe'.
- SANPO labels cover walking in cities and parks; trails and running are thin in the labelled set.
- Licences: SegFormer weights (shipped ADE, Cityscapes, and the off-road B2 fine-tuned from them) trace back to NVIDIA's research-only licence; D-FINE and Depth Anything V2 Small are Apache-2.0.
- Internet clips have unknown camera height and tilt; the benchmark assumes 1.3 m and 10° (17° for SANPO), so their stop/turn counts are behaviour comparisons between engines, not ground truth.

## 16. How to re-run

```
cd BasirRun_Research
python3 tools/build_video_manifest.py            # manifest from the library
python3 tools/bench_videos.py                    # same clips through 0.12.0 and round-4 engines (+ off-road variant)
python3 tools/bench_videos_summary.py            # behaviour metrics
/tmp/tfl/bin/python tools/bench_terrain.py       # labelled SANPO dev/test metrics (needs torch for the off-road cache)
python3 tools/make_decision.py                   # applies the pre-registered criteria
python3 tools/make_report.py                     # this report
node .claude/skills/unlazy/scripts/gate-check.mjs BasirRun_Research/GATES_round4.md   # all round-4 gates
```

## 17. References and licences

Repositories: section 2 (each URL is `https://github.com/<repo>`). Papers: section 4. Datasets: section 6. Video sources and licences: `manifests/video_dataset_manifest.csv`. Hugging Face models: https://huggingface.co/nvidia/segformer-b0-finetuned-cityscapes-1024-1024 , https://huggingface.co/roboticslaburjc-org/cross-dataset-visual-offroad-segmentation , https://huggingface.co/JasonTStanley/RUGD-Segformer , https://huggingface.co/datasets/shawshankvkt/Walking_Tours .

