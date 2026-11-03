# التقرير النهائي للبحث والتطوير — Basir-Run

**التاريخ:** 2026-11-01.  **المشروع:** Basir-Run Detection Engine 0.12.2، والتعديلات في `project/` (git).
**القاعدة:** كل رقم في هذا التقرير مقاس، ومصدره ملف في `benchmarks/results/`. لا يوجد رقم منقول من بحث دون أن يُذكر أنه ادعاء أصحابه.

> **لم يُختبر شيء بعد على هاتف أو في الميدان.** النتائج هنا على فيديو مسجَّل يُعاد تشغيله عبر كود التطبيق نفسه. لا يُعتبر النظام جاهزًا للجري المستقل قبل اختبارات ميدانية مع مرافق في مسارات مضبوطة.

## 1. ملخص المحرك القديم ومشكلاته

التقرير الكامل في `reports/BasirRun_Current_Engine_Audit.md`، وكُتب قبل أي تعديل. أهم ما فيه:
- **الكود متقن:** 80 اختبارًا كلها ناجحة. يجمع مسارين: محرك مسار افتراضي بإحداثيات الأرض بالأمتار، وكواشف قديمة في فضاء الصورة.
- **لا يعتمد على الخطوط فقط:** يستطيع إيجاد ممر بلا خطوط عبر SegFormer وتخطيط على الأرض. لكن فهمه للأرض ثنائي ودلالي، و`TerrainZones` غير موصول.
- **ترخيص نموذج الأرض SegFormer غير تجاري.** هذا خطر إن كان التطبيق سيُوزَّع أو يُباع.
- **الرسم القديم لم يكن على الأرض:** `VirtualPath` يرسم في فضاء الصورة من الكواشف القديمة.
- **أوراق الأشجار تُعتبر خطرًا عبر عدة مسارات:**
  1. كاشف الأجسام يسمّي الأوراق «bird». هذا وُجد بالقياس، لا بقراءة الكود.
  2. كاشف تغيّر اللون في الممر يعتبرها جسمًا غريبًا.
  3. نموذج الأرض يسمّي بقع الأوراق «plant»، فيبدو الطريق منتهيًا عندها.
  4. قناة العمق تتفاعل مع تضاريس حقيقية بجوار الممر.
- **إطار مهزوز منفرد كان يوقف التوجيه نحو 0.7 ثانية.** في هرولة حقيقية بقي المحرك «غير متأكد» نصف الوقت.

## 2. نتائج البحث في GitHub

فحصت **43 مستودعًا**، وتأكدت من وجود كل واحد منها بالأمر `git ls-remote` (القائمة الكاملة مع الترخيص ووصف الكود والقرار في `manifests/github_repositories.csv`). المستودعات موزعة على المجالات الستة المطلوبة:

| المجال | المستودعات |
|---|---|
| A. فهم الأرضيات وقابلية العبور | wild_visual_navigation، self_supervised_segmentation، GANav-offroad، RELLIS-3D، FtFoot، Off-Road-Freespace-Detection، tartan_drive، clipseg، dinov2 |
| B. المنطقة القابلة للمشي | outdoor-blind-navigation، SegFormer، Mask2Former، PaddleSeg، TopFormer، DDRNet، BiSeNet، sam2، Grounded-Segment-Anything، YOLOP، YOLOPv2، HybridNets، sanpo_dataset، mmsegmentation |
| C. الخطوط والمسارات | project-guideline، Ultra-Fast-Lane-Detection-v2، CLRNet، YOLOP |
| D. العوائق والعمق والتتبع | D-FINE، Depth-Anything-V2، MiDaS، YOLOX، RT-DETR، ByteTrack، sort، BoT-SORT، ml-depth-pro |
| E. تخطيط المسار والملاحة المساعدة | project-guideline، outdoor-blind-navigation، wild_visual_navigation، sanpo_dataset، Ego4d |
| F. الفيديو والتتبع الزمني | RAFT، ORB_SLAM3، OneEuroFilter، ByteTrack، BoT-SORT، sam2 |

**نسخت 18 مستودعًا وقرأت كودها** (في `github_repositories/`). وهذه أهم ملاحظات القراءة:
- **لا يوجد مستودع مفتوح يحل مشكلة Basir-Run كاملة.** الأقرب Project Guideline من Google، لكنه يحتاج خطًا مرسومًا وARCore.
- **أغلب نماذج «المنطقة القابلة للسير» مدربة من منظور السيارة** (Cityscapes وBDD100K). اختبرت YOLOP مثالًا عليها: يعتبر الرصيف «غير قابل للقيادة»، فيفشل تمامًا على ممرات المشاة (انظر §9).
- **نماذج الأراضي الوعرة** (GA-Nav وRELLIS وORFD وFtFoot) مدربة من منظور روبوت أو مركبة، وبعضها يحتاج LiDAR. أفضل ما يُستفاد منها **فكرة مستويات القابلية للعبور**: في GA-Nav تقع «mulch» (فرشة الأوراق واللحاء) ضمن «وعر قابل للعبور» لا ضمن «عائق». هذا يؤيد مبدأ معاملة الأوراق كأرض.
- **التراخيص:** SORT وORB-SLAM3 بترخيص GPL، وSegFormer بترخيص غير تجاري، وRELLIS-3D بـ CC BY-NC-SA. كلها غير مناسبة لتطبيق يُوزَّع دون مراجعة قانونية.

## 3. أفضل 10 مستودعات فُحصت بالتفصيل

| # | المستودع | ما وجدته في الكود | الصلة بـ Basir-Run |
|---|---|---|---|
| 1 | [google-research/project-guideline](https://github.com/google-research/project-guideline) (Apache-2.0) | `vision/guideline_detector.cc` يشغّل `guideline.tflite` و`depth.tflite` عبر MediaPipe. في `environment/` يجمّع نقاط الخط في **فضاء العالم** باستخدام وضعية ARCore، ويطابق العمق بنقاط ARCore بخوارزمية RANSAC (`depth/depth_align_ransac.cc`)، ويحتفظ بـ OccupancyMap للعوائق. | الأقرب. يوضح ما ينقص Basir-Run: ذاكرة للمسار في فضاء العالم تعوّض حركة الكاميرا والتأخر |
| 2 | [leggedrobotics/wild_visual_navigation](https://github.com/leggedrobotics/wild_visual_navigation) (MIT) | `feature_extractor/dino_interface.py` يستخرج سمات DINO، و`supervision_generator/` يولّد وسوم «قابل للعبور» من المكان الذي مشى فيه الروبوت فعلًا، و`traversability_estimator/` شبكة MLP صغيرة تتعلم أثناء التشغيل. | فكرة التعلم الذاتي من خطى المستخدم نفسه (خارطة الطريق) |
| 3 | [rayguan97/GANav-offroad](https://github.com/rayguan97/GANav-offroad) (Apache-2.0) | `tools/convert_datasets/rugd_relabel6.py` يحوّل فئات RUGD الـ 24 إلى 6 مستويات للقابلية للعبور. | اعتمدت مبدأ «المستويات» في طبقة فهم الأرض |
| 4 | [google-research-datasets/sanpo_dataset](https://github.com/google-research-datasets/sanpo_dataset) (Apache-2.0 للكود، CC BY 4.0 للبيانات) | `lib/labelmap.json` فيه 31 فئة منها sidewalk وpaved trail وother walkable surface وterrain، و`lib/common.py` قارئ الجلسات. | **مصدر البيانات الأساسي** هنا: فيديوهات الاختبار والحقيقة الأرضية |
| 5 | [hustvl/YOLOP](https://github.com/hustvl/YOLOP) (MIT) | `weights/yolop-640-640.onnx` و`test_onnx.py`: منطقة قابلة للقيادة وخطوط حارات وأجسام في تمريرة واحدة. | اختُبر كبديل للمنطقة القابلة للمشي وفشل على الممرات (منظور السيارة) |
| 6 | [timojl/clipseg](https://github.com/timojl/clipseg) (MIT للكود) | تقسيم بوصف نصي باستخدام CLIP ViT-B/16. | اختُبر بأوصاف مثل «fallen leaves covering the ground» و«dirt or gravel trail» |
| 7 | [DepthAnything/Depth-Anything-V2](https://github.com/DepthAnything/Depth-Anything-V2) (Apache-2.0 للحجم Small) | `run_video.py`، و`metric_depth/` لعمق مترّي. | النموذج الحالي، وصار أيضًا **مصدر الدليل على أن الشيء مسطح على الأرض** |
| 8 | [ifzhang/ByteTrack](https://github.com/ifzhang/ByteTrack) (MIT) | `yolox/tracker/byte_tracker.py` مع `kalman_filter.py`: يربط الاكتشافات عالية الثقة ومنخفضتها معًا. | مقترح بديلًا لربط أقرب جار في `ObstacleTracker` (لم يُنفَّذ بعد) |
| 9 | [cfzd/Ultra-Fast-Lane-Detection-v2](https://github.com/cfzd/Ultra-Fast-Lane-Detection-v2) (MIT) | `deploy/pt2onnx.py` و`trt_infer.py`، وكشف الخطوط بمراسٍ على الصفوف والأعمدة. | مرشح لخطوط المضمار بعد إعادة تدريب (حارات سيارات فقط حاليًا) |
| 10 | [Peterande/D-FINE](https://github.com/Peterande/D-FINE) (Apache-2.0) | `tools/deployment` للتصدير إلى ONNX. | الكاشف الحالي. **وُجد أنه يسمّي الأوراق المتساقطة «bird»** (انظر §13) |

## 4. الأبحاث العلمية المهمة

سُجّل 40 بحثًا في `research_papers/papers.csv`. لكل بحث: السنة، والرابط، والكود، والنموذج، والبيانات، والنتيجة المنشورة كما يدّعيها أصحابها، وهل يعمل على هاتف، ومدى الملاءمة، والقيود. كل معرّفات arXiv تحقق منها سكربت عبر واجهة arXiv. الأهم لـ Basir-Run:

| البحث | السنة | لماذا يهم | يعمل على هاتف؟ |
|---|---|---|---|
| [SANPO: A Scene Understanding, Accessibility and Human Navigation Dataset](https://arxiv.org/abs/2309.12172) | 2023 | HIGH: same viewpoint (chest camera, walking/jogging); used here for videos and ground truth | n/a (data) |
| [GANav: Efficient Terrain Segmentation for Robot Navigation in Unstructured Outdoor Environments](https://arxiv.org/abs/2103.04233) | 2021 | HIGH (concept): navigability groups put mulch/grass/dirt in 'rough navigable', not obstacle | possibly (efficient backbone) but no mobile export |
| [SegFormer: Simple and Efficient Design for Semantic Segmentation with Transformers](https://arxiv.org/abs/2105.15203) | 2021 | CURRENT walkable model (B0 ADE20K) | yes (B0) |
| [Depth Anything V2](https://arxiv.org/abs/2406.09414) | 2024 | CURRENT depth model; used by the ground-cover check | yes (Small, 252 px) |
| [D-FINE: Redefine Regression Task in DETRs as Fine-grained Distribution Refinement](https://arxiv.org/abs/2410.13842) | 2024 | CURRENT detector | yes (N) |
| [AI Guide Dog: Egocentric Path Prediction on Smartphone](https://arxiv.org/abs/2501.07957) | 2025 | HIGH (task) but outputs directions, not a metric path | yes |
| [Safety-oriented sidewalk and road segmentation for smartphone-based assistive navigation](https://arxiv.org/abs/2607.21137) | 2026 | HIGH: chest-height viewpoint and a false-safe metric | yes (evaluated on Android) |
| [Learning Semantic Traversability with Egocentric Video and Automated Annotation Strategy](https://arxiv.org/abs/2406.02989) | 2024 | HIGH (method): label the runner's own videos automatically | unclear |
| [1€ Filter: A Simple Speed-based Low-pass Filter for Noisy Input in Interactive Systems (Casiez, Roussel, Vogel, CHI)](https://doi.org/10.1145/2207676.2208639) | 2012 | ALREADY USED in StableGuidance | yes |
| [Learn Where I Can Walk: Auto-labeling of Walked Areas Using Monocular Camera Trajectory (Engelhardt et al., ROBOVIS)](https://doi.org/10.1007/978-3-032-00986-9_23) | 2025 | HIGH: label Basir-Run's own runs from the runner's trajectory | offline |
| [Project Guideline: on-device line following for blind runners (Google Research, open source)](https://github.com/google-research/project-guideline) | 2023 | HIGH: closest system; world-frame line aggregation with ARCore, occupancy map | yes (Android) |

## 5. أفضل النماذج التي اختُبرت

### 5.1 الأرض القابلة للمشي مقابل الحقيقة الأرضية البشرية (SANPO، 600 إطارًا من 30 جلسة اختبار رسمية)

**تعريف «الأرض القابلة للمشي» في الحقيقة الأرضية:**
- **الواسع:** رصيف، ومعبر مشاة، وممر معبّد، و«سطح آخر قابل للمشي»، وطريق، وحافة رصيف، وتربة أو عشب.
- **الضيق:** رصيف، ومعبر، وممر معبّد، و«سطح آخر قابل للمشي» فقط.

قِسم الاختبار منفصل عن التدريب بحسب الجلسة، لا بحسب إطارات عشوائية.

| النموذج | IoU (واسع) | Dice/F1 | الدقة | الاستدعاء | Boundary F1 | أرض «آمنة» خاطئة | خطأ خط المنتصف | IoU ممر أمامي (ضيق) | زمن CPU ms |
|---|---|---|---|---|---|---|---|---|---|
| Mask2Former Swin-T + القاعدة نفسها | 0.745 | 0.824 | 0.900 | 0.785 | 0.676 | 0.066 | 0.040 | 0.895 | 653 |
| هندسة العمق فقط (مستوى الأرض) | 0.742 | 0.831 | 0.880 | 0.799 | 0.575 | 0.111 | 0.043 | 0.876 | 9 |
| SegFormer-B2 + القاعدة نفسها | 0.734 | 0.821 | 0.900 | 0.790 | 0.630 | 0.082 | 0.050 | 0.877 | 928 |
| الحالي + قبول الأوراق المسطحة | 0.661 | 0.755 | 0.892 | 0.707 | 0.535 | 0.083 | 0.074 | 0.783 | 31 |
| SegFormer-B0 + قاعدة المحرك (الحالي) | 0.659 | 0.753 | 0.892 | 0.705 | 0.533 | 0.083 | 0.074 | 0.782 | 31 |
| CLIPSeg (أوصاف نصية) | 0.577 | 0.677 | 0.823 | 0.652 | 0.472 | 0.164 | 0.117 | 0.667 | 2303 |
| YOLOP المنطقة القابلة للقيادة | 0.417 | 0.529 | 0.908 | 0.422 | 0.269 | 0.032 | 0.132 | 0.520 | 133 |

زمن CPU لكل إطار مقاس لكل نموذج منفردًا على معالج الحاوية (`results/candidate_timing.json`)، لا على هاتف. زمن «هندسة العمق» هو المعالجة اللاحقة فقط (نموذج العمق يعمل في التطبيق أصلًا، وزمنه 93 ms).

**على بيانات الطرق الوعرة (RUGD، 26 إطارًا، فئات GA-Nav القابلة للعبور ومنها mulch):**

| النموذج | IoU | الاستدعاء | أرض «آمنة» خاطئة |
|---|---|---|---|
| هندسة العمق فقط (مستوى الأرض) | 0.784 | 0.801 | 0.042 |
| Mask2Former Swin-T + القاعدة نفسها | 0.782 | 0.804 | 0.024 |
| الحالي + قبول الأوراق المسطحة | 0.761 | 0.780 | 0.023 |
| SegFormer-B0 + قاعدة المحرك (الحالي) | 0.745 | 0.762 | 0.022 |
| SegFormer-B2 + القاعدة نفسها | 0.721 | 0.742 | 0.048 |
| CLIPSeg (أوصاف نصية) | 0.272 | 0.302 | 0.207 |
| YOLOP المنطقة القابلة للقيادة | 0.069 | 0.069 | 0.000 |

**الخلاصة:**
- **الأعلى IoU على SANPO:** Mask2Former Swin-T + القاعدة نفسها (0.745).
- **النماذج الأكبر** (SegFormer-B2 وMask2Former) أبطأ بكثير، وترخيصها غير تجاري أو غير واضح.
- **YOLOP** مدرب من منظور السيارة، فيفشل على الأرصفة.
- **CLIPSeg** مفيد للتحليل خارج الهاتف فقط.

- **هندسة العمق وحدها** (مستوى الأرض من Depth Anything، دون أي نموذج تقسيم) قريبة من النماذج الثقيلة: IoU 0.742، والأعلى في الممر أمام العدّاء (0.917). تكلفتها 9 ms فقط، لكنها تقبل أرضًا غير آمنة أكثر (0.111 مقابل 0.083).
- **فحص سريع للدمج** (`results/gt_fusion_check.json`): نسخة «أرض العمق ناقص الأجسام» (التقسيم يستبعد الأشخاص والجدران والأعمدة) على SANPO: IoU 0.714 مقابل 0.659، مع أرض «آمنة» خاطئة أقل (0.079 مقابل 0.083). لكنها على RUGD أكثر (0.039 مقابل 0.022). الدليل غير حاسم، فلم تُفعَّل، وهي أقوى توصية للمرحلة القادمة.

لم يُستبدل النموذج الحالي، لأنه لا يوجد بديل مرخّص للاستخدام التجاري ومناسب للهاتف ومتفوق عليه في الحقيقة الأرضية. التوصية في §11.

### 5.2 على الفيديوهات نفسها (منطق التطبيق كاملًا)

| النسخة | توفر المسار | الاستمرارية | إنذار خاطئ على أوراق | إنذار خاطئ على أرض واضحة | توقف خاطئ | استدعاء العوائق |
|---|---|---|---|---|---|---|
| baseline | 0.595 | 0.469 | 0.453 | 0.357 | 0.141 | 0.990 |
| cand_yolop_da | 0.441 | 0.301 | 0.797 | 1.000 | 0.370 | 1.000 |
| cand_depth_floor | 0.619 | 0.480 | 0.453 | 0.357 | 0.141 | 0.990 |
| flat_detections | 0.596 | 0.471 | 0.422 | 0.321 | 0.141 | 0.990 |
| ground_cover | 0.599 | 0.471 | 0.453 | 0.357 | 0.120 | 0.990 |
| visibility_coast | 0.627 | 0.499 | 0.453 | 0.357 | 0.141 | 0.990 |
| proposed | 0.627 | 0.542 | 0.422 | 0.286 | 0.120 | 0.990 |

الثواني الموسومة يدويًا: 64 ثانية أوراق واضحة، و28 ثانية واضحة بلا أوراق، و101 ثانية عوائق حقيقية (`annotations/leaf_obstacle_labels.csv`).

## 6. مصادر مجموعات البيانات

| المجموعة | المصدر | الترخيص | المنظور | الفجوة مع كاميرا صدر العدّاء | الحالة |
|---|---|---|---|---|---|
| SANPO-Real | https://github.com/google-research-datasets/sanpo_dataset (gs://gresearch/sanpo_dataset/v0) | CC BY 4.0 | chest (ZED 2i) and head (ZED Mini), walking/jogging/running volunteers | low (US cities/parks, few runners, no explicit leaf class) | DOWNLOADED (subset) and USED: 25 benchmark clips; ground truth for walkable-area IoU (official test split vs train split) |
| SANPO-Synthetic | same bucket | CC BY 4.0 | chest and head | sim-to-real gap | documented; candidate for pre-training a terrain head |
| RUGD | http://rugd.vision/ | research use (citation requested on the site); no explicit open licence stated | small ground robot, low camera | medium (robot height, slow speed) | DOWNLOADED (official 19 MB sample, 26 labelled frames) and USED as an off-road ground-truth check |
| RELLIS-3D | https://github.com/unmannedlab/RELLIS-3D | CC BY-NC-SA 3.0 | off-road vehicle (Warthog) | high | documented; non-commercial licence |
| Freiburg Forest (DeepScene) | http://deepscene.cs.uni-freiburg.de/ | see site (research) | robot in forest | medium-high (robot, images only) | documented |
| GOOSE (German Outdoor and Offroad Dataset) | https://goose-dataset.de/ | CC BY-SA | off-road vehicle | vehicle viewpoint | documented; permissive licence makes it a training candidate (GANav has a GOOSE config) |
| TartanDrive | https://github.com/castacks/tartan_drive | see repository | ATV | high (vehicle dynamics focus) | documented |
| ORFD | https://github.com/chaytonmin/Off-Road-Freespace-Detection | see repository (code MIT) | vehicle | high | documented |
| Mapillary Vistas | https://www.mapillary.com/dataset/vistas | CC BY-NC-SA 4.0 (research edition) | mixed: car, bike, PEDESTRIAN phones | medium | documented; LWICW auto-labels walked areas on Mapillary sequences |
| BDD100K | https://bdd-data.berkeley.edu/ | BDD100K licence (non-commercial research) | car dashboard | high | documented; YOLOP (tested) was trained on it |
| Cityscapes | https://www.cityscapes-dataset.com/ | Cityscapes licence (non-commercial) | car | high | documented |
| ADE20K | https://groups.csail.mit.edu/vision/datasets/ADE20K/ | research licence (see site) | mixed, mostly hand-held scenes | medium-high | documented: training data of the current walkable model |
| Ego4D | https://ego4d-data.org/ | Ego4D licence agreement required | head-mounted | medium | documented; licence agreement not available to this session |
| PEDESTRIAN (arXiv 2512.19190) | https://arxiv.org/abs/2512.19190 | see paper | pedestrian egocentric | low | documented; candidate obstacle test set |
| SENSATION-DS (arXiv 2607.21137) | https://arxiv.org/abs/2607.21137 | see paper | chest height pedestrian | low (urban only) | documented; target for permissive fine-tuning |
| AI Guide Dog videos (arXiv 2501.07957) | https://arxiv.org/abs/2501.07957 | see paper | hand-held/chest smartphone | low | documented |
| mmWalk (arXiv 2510.11520) | https://arxiv.org/abs/2510.11520 | see paper | walker, guide dog, drone | simulation gap | documented |
| CurveLanes | https://github.com/SoulmateB/CurveLanes | see repository | car | high | documented |

## 7. الفيديوهات المحمّلة فعليًا

44 فيديو، كلها تُفتح بـ ffprobe، وكلها بكاميرا متحركة (فُحص التدفق البصري للكاميرا نفسها، واستُبعد مقطع بكاميرا ثابتة). القائمة الكاملة بكل الحقول في `manifests/video_dataset_manifest.csv`.

| المعرّف | الفئة | الحركة | زاوية الكاميرا | الأرضية | الإضاءة | المدة ث | الترخيص |
|---|---|---|---|---|---|---|---|
| sanpo_0X2rC_H2 | park_paths | jogging | chest | asphalt, leaves at edges | overcast | 12.0 | CC BY 4.0 |
| sanpo_60_ibYCD | difficult_lighting | walking | chest | pavers | artificial lights, glare | 12.0 | CC BY 4.0 |
| sanpo_66XHCmoc | gravel_paths | jogging | chest | dirt/gravel, grass edges | overcast | 12.0 | CC BY 4.0 |
| sanpo_6D6CrPEV | park_paths | walking | chest | leaf-covered grass, asphalt, stairs | sunny | 12.0 | CC BY 4.0 |
| sanpo_9MyRHsYK | gravel_paths | walking | chest | gravel and dirt, wet | overcast | 12.0 | CC BY 4.0 |
| sanpo_BMdZBHtj | forest_trails | jogging | chest | dirt, leaves, roots, branches | overcast | 12.0 | CC BY 4.0 |
| sanpo_GMBhdIW2 | wet_surfaces | walking | chest | wet asphalt, crosswalk | rain, cloudy | 12.0 | CC BY 4.0 |
| sanpo_J30YHSYh | difficult_lighting | walking | chest | asphalt | dusk, street lamps | 12.0 | CC BY 4.0 |
| sanpo_QEKI74U_ | wet_surfaces | walking | chest | wet concrete | rain | 12.0 | CC BY 4.0 |
| sanpo_QVzjzsSe | obstacles | walking | chest | pavers | sunny, glare | 12.0 | CC BY 4.0 |
| sanpo_US7wvt5o | obstacles | walking | chest | wet pavers | rain | 12.0 | CC BY 4.0 |
| sanpo_VWw2W_ob | rocky_surfaces | walking | chest | stone steps, rocks, leaves | sunny | 12.0 | CC BY 4.0 |
| sanpo_ZR3hpsQx | low_contrast | walking | chest | light gray concrete + gravel | sunny | 12.0 | CC BY 4.0 |
| sanpo_eDD4YHsx | wet_surfaces | walking | chest | wet concrete | rain | 12.0 | CC BY 4.0 |
| sanpo_eHxtA669 | park_paths | walking | chest | asphalt, fence, grass | overcast | 12.0 | CC BY 4.0 |
| sanpo_fCKRZ-3D | obstacles | walking | chest | brick, snow patches | sunny | 12.0 | CC BY 4.0 |
| sanpo_fyfUW3nG | running | jogging | chest | light sand/concrete | sunny | 12.0 | CC BY 4.0 |
| sanpo_h3TEaYCA | obstacles | walking | chest | hexagon pavers | artificial | 12.0 | CC BY 4.0 |
| sanpo_huRVT5Bl | running | running | chest, tilted steeply down | pavers | sunny | 12.0 | CC BY 4.0 |
| sanpo_i2jglnBf | walking | walking | chest | asphalt, curbs | sunny, strong shadows | 12.0 | CC BY 4.0 |
| sanpo_kg00o09y | wet_surfaces | walking | chest | brick with snow | sunny | 12.0 | CC BY 4.0 |
| sanpo_nLaqyAdE | walking | walking | chest | tiles | artificial | 12.0 | CC BY 4.0 |
| sanpo_rjYpw8aw | walking | walking | chest | brick, shadows | sunny | 12.0 | CC BY 4.0 |
| sanpo_tjIoesYz | difficult_lighting | jogging | chest | dark asphalt | low sun, long fence shadows | 12.0 | CC BY 4.0 |
| sanpo_utALcUDZ | walking | walking | chest | asphalt, curbs | overcast | 12.0 | CC BY 4.0 |
| mixkit_22477 | park_paths | walking | low tracking camera behind the walker's feet | dirt path, grass edges | sunny | 16.32 | Mixkit Stock Video Free License |
| mixkit_32688 | rocky_surfaces | walking | low forward-moving camera | rocky dirt road | sunny | 79.92 | Mixkit Stock Video Free License |
| mixkit_41574 | rocky_surfaces | walking | forward-moving camera, eye level | cobblestones | overcast | 15.02 | Mixkit Stock Video Free License |
| mixkit_50849 | forest_trails | walking | forward-moving camera | dirt trail, grass, needles | overcast | 16.77 | Mixkit Stock Video Free License |
| mixkit_50852 | forest_trails | walking | backward-moving camera (reverse shot) | dirt trail, grass, needles | overcast | 16.68 | Mixkit Stock Video Free License |
| mixkit_40656 | park_paths | walking | forward-moving camera, portrait | asphalt path, grass | overcast | 12.01 | Mixkit Stock Video Free License |
| mixkit_45620 | forest_trails | walking | forward-moving camera | path fully covered in orange leaves | sunny | 32.92 | Mixkit Stock Video Free License |
| mixkit_28827 | difficult_lighting | walking | forward-moving camera | path covered in leaves, hard shadows | low sun, strong shadows | 19.12 | Mixkit Stock Video Free License |
| mixkit_34631 | wet_surfaces | walking | forward-moving camera | snow-covered path | low sun, glare | 11.76 | Mixkit Stock Video Free License |
| mixkit_32810 | athletics_tracks | running | low forward-moving camera | blue tartan, white lane lines, numbers | sunny | 19.04 | Mixkit Stock Video Free License |
| mixkit_609 | athletics_tracks | running | low tracking camera behind a runner | red tartan, white lane lines | sunny | 18.93 | Mixkit Stock Video Free License |
| mixkit_32799 | athletics_tracks | running | camera moving back in front of a runner | blue tartan | sunny | 10.67 | Mixkit Stock Video Free License |
| mixkit_7599 | forest_trails | running | low tracking camera behind a runner | dirt trail | sunny | 19.24 | Mixkit Stock Video Free License |
| mixkit_7592 | obstacles | running | camera moving beside a runner | dirt path, plants | sunny | 21.56 | Mixkit Stock Video Free License |
| mixkit_48149 | obstacles | running | camera following right behind a runner | dirt trail | sunny | 5.21 | Mixkit Stock Video Free License |
| mixkit_103 | low_contrast | walking | ground-level tracking camera | dried leaves | sunny, backlight | 21.5 | Mixkit Stock Video Free License |
| mixkit_14774 | forest_trails | walking | low tracking camera behind feet | low plants, ground cover | overcast | 28.08 | Mixkit Stock Video Free License |
| mixkit_31942 | low_contrast | walking | low tracking camera behind feet | snow between rails | overcast | 17.95 | Mixkit Stock Video Free License |
| mixkit_27766 | difficult_lighting | walking | hand-held camera following a walker | stone pavement, bridge | night, street lights | 43.75 | Mixkit Stock Video Free License |

## 8. توزيع الفيديوهات حسب البيئات والأرضيات

| الفئة | العدد |
|---|---|
| forest_trails | 6 |
| obstacles | 6 |
| park_paths | 5 |
| difficult_lighting | 5 |
| wet_surfaces | 5 |
| walking | 4 |
| rocky_surfaces | 3 |
| low_contrast | 3 |
| athletics_tracks | 3 |
| gravel_paths | 2 |
| running | 2 |

**الحركة:** jogging 5، walking 32، running 7.  **المصدر والترخيص:** CC BY 4.0 25، Mixkit Stock Video Free License 19.
**المدة الكلية:** 726 ثانية. 25 مقطعًا من SANPO مصوَّرة بكاميرا على الصدر، وهي الأقرب لمنظور العدّاء. مقاطع Mixkit مصوَّرة بكاميرات محمولة باليد أو على جيمبال، وزاوية كل منها مسجلة في الملف.
**نقص معروف:** مقاطع المضامير 3 فقط، ولا يوجد خط متعرج، ولا ضباب، ولا كاميرا صدر على مضمار. البحث عنها جرى في Mixkit وSANPO، أما Pexels وPixabay وWikimedia فكانت محجوبة أو محدودة من بيئة العمل.

## 9. نتائج المقارنة بين النماذج

انظر §5.1 للحقيقة الأرضية و§5.2 للفيديو. وعلى الفيديو نفسه، أي بديل لنموذج الأرض داخل منطق التطبيق يغيّر سلوك التوجيه كله:
- **YOLOP:** توفر المسار 0.44 مقابل 0.60 للحالي، والتوقف الخاطئ 0.37.
- **هندسة العمق وحدها:** إنذار خاطئ على الأوراق 0.45، مقابل 0.45 للحالي.

## 10. النتائج قبل التطوير وبعده (المحرك كاملًا، الإطارات نفسها)

| المقياس | قبل (0.12.2) | بعد |
|---|---|---|
| نسبة إطارات الإنذار على أوراق واضحة | 0.248 | 0.209 |
| ثوانٍ أوراق واضحة فيها إنذار | 0.453 | 0.422 |
| نسبة إطارات الإنذار في ثواني العوائق الحقيقية | 0.923 | 0.910 |
| توقف خاطئ على ثوانٍ واضحة | 0.141 | 0.120 |
| إنذار خاطئ على ثوانٍ واضحة | 0.424 | 0.380 |
| استدعاء العوائق الحقيقية (ثوانٍ) | 0.990 | 0.990 |
| تذبذب المسار عند 2 م (وسيط) | 0.039 م | 0.024 م |
| متوسط إزاحة خط المنتصف | 0.124 م | 0.077 م |
| استمرارية المسار | 0.474 | 0.539 |
| فقدان التتبع | 0.405 | 0.373 |
| اهتزازات متعارضة/دقيقة | 5.81 | 2.74 |
| تأخر تتبع المنعطفات (إطارات) | 2.50 | 2.87 |
| زمن إنذار العائق | 0.114 ث | 0.148 ث |
| تشغيل النماذج الثقيلة/ث | 15.0 | 9.3 |
| زمن منطق التطبيق/إطار (حاوية x86) | 16.2 ms | 16.6 ms |

التفاصيل والحالات الست عشرة في `reports/BasirRun_Path_Stabilization_Report.md`. فيديوهات «قبل وبعد» لكل مقطع في `visual_comparisons/`: اليسار قبل، واليمين بعد، مع طبقات المطور.

## 11. المحرك النهائي الذي تم اختياره

**بنية هجينة، لا نموذجًا واحدًا:**
1. **قناة سلامة سريعة لا تُجدول:** D-FINE-N وDepth Anything V2 Small كما هما. أُضيف لها فلتر الأنماط المسطحة على الأرض (الأوراق التي يراها الكاشف «طيورًا»)، وهو لا يشمل الأشخاص أبدًا.
2. **قناة الأرض:** SegFormer-B0 الحالي مع قاعدة المحرك، يعمل في إطارات رئيسية تتكيف مع الحالة. يُضاف له قبول الأوراق المسطحة بدليل العمق، وهو مفعّل فقط مع المتتبع الزمني، لأنه وحده يرفع التذبذب.
3. **الخطوط:** نموذج Guideline، والكاشف اللوني.
4. **التخطيط:** `IntelligentPathPlanner` مع `TemporalPathTracker` وتعويض حركة الكاميرا (الجيروسكوب، أو الدوران البصري)، وخط منتصف الممر، واستمرار الرؤية عند الإطار المهزوز.
5. **الرسم والتوجيه:** `PathStabilityController` ثم `GroundRibbon` للرسم على الأرض، ثم `StableGuidanceController` و`GuidanceTemporalStabilizer`، ثم `HapticScheduler`.

**توصيتان للمرحلة القادمة (لم تُنفَّذا هنا):**
1. **دمج «أرض العمق» مع التقسيم داخل المحرك (§5.1).** هذا أرخص تحسن ممكن، لأن نموذج العمق يعمل أصلًا. يجب أن يُختبر على الفيديوهات نفسها وعلى الطرق الوعرة قبل اعتماده.
2. **استبدال SegFormer-B0 بنموذج مرخّص للاستخدام التجاري ومناسب للهاتف، مثل TopFormer أو PP-LiteSeg، مُدرَّب على بيانات منظور الصدر (SANPO بترخيص CC BY 4.0، وGOOSE بترخيص CC BY-SA، وتسجيلات المستخدم مع وسم آلي على طريقة LWICW).** يجب ألا يُعتمد إلا إذا تفوق على مجموعة الاختبار نفسها.

## 12. التعديلات التي أُدخلت على المشروع

كل التعديلات في git (`project/`)، والنسخة الأصلية محفوظة في أول commit وفي `BasirRun_Detection_Engine_0.12.2_ORIGINAL_BACKUP.zip`. الحجم الكلي للتغيير: 958 files changed, 7633 insertions(+), 19 deletions(-).

```
7940140 Original BasirRun Detection Engine 0.12.2 (unmodified baseline)
03eb564 Add root Gradle settings so the engine module builds standalone (JDK 17 toolchain, Maven mirror)
4c04213 Engine: opt-in GroundCoverResolver, FlatSurfaceCheck and GroundRibbon with tests; JVM replay harness
13b1c17 Ground evidence for flat things on the ground (leaves), wired into the app; ground ribbon on LineResult
9cda07c Path stabilization: temporal tracker, stability controller, adaptive keyframes, guidance stabilizer, dev overlay
d9fd53d Visual yaw estimator for ego-motion; warnings no longer held by the guidance stabilizer (measured)
```

**ملفات جديدة في المحرك:**
- `GroundCover.kt` (`GroundCoverResolver` و`FlatSurfaceCheck`).
- `GroundRibbon.kt`.
- `TemporalPathTracker.kt`.
- `PathStability.kt` (`PathStabilityController` و`AdaptiveFrameController` و`EgoHistory`).
- `VisualYawEstimator.kt`.
- `GuidanceTemporalStabilizer.kt`.
- تعديلات في `IntelligentPathPlanner` (المتتبع الزمني، واستمرار الرؤية، والقياس الخام) و`Obstacles.kt` (`flatGroundPattern`) و`Geometry.kt` (`yawRateRight`).

**في التطبيق:**
- `GroundEvidence.kt` و`DeveloperOverlay.kt`.
- `LineEngine` (حركة الكاميرا، وتعويض عمر القناع، وخط المنتصف، والتثبيت، و`ribbon`).
- `GuidancePipeline` (أدلة العمق، وجدولة الإطارات، و`yawRate`).
- `SurfaceChannel` و`SurfaceModels` (شبكة الفئات).
- `HapticBridge` (`StableHapticBridge`).

**أدوات:** `tools/replay` (إعادة تشغيل كود التطبيق على JVM، مع اختبارات الإحداثيات)، و`tools/appcheck` (فحص ترجمة كود أندرويد مقابل واجهات بديلة).

**مطلوب في كود التطبيق خارج الأرشيف** (الواجهة والخدمة غير موجودتين فيه):
- استدعاء `StableHapticBridge.command(decision, nowMs)` بدل `HapticBridge.command`.
- تمرير `yawRate` من الجيروسكوب إلى `GuidancePipeline`.
- رسم `line.ribbon` بدل `virtualPath`.
- عرض `DeveloperOverlay` في وضع المطور فقط.

## 13. المشاكل التي تم إصلاحها (مقاسة)

- **الأوراق كعائق:** نسبة إطارات الإنذار على الأرض المغطاة بالأوراق والخالية من العوائق انخفضت من 0.248 إلى 0.209. الثواني التي فيها إنذار انخفضت من 0.453 إلى 0.422، وثواني العوائق الحقيقية المكتشفة بقيت 0.990. سبب «الطيور» في العشب المغطى بالأوراق زال تمامًا (sanpo_6D6CrPEV).
- **الإنذارات والتوقف الخاطئ على كل الثواني الواضحة:** الإنذار الخاطئ من 0.424 إلى 0.380، والتوقف الخاطئ من 0.141 إلى 0.120.
- **التوقف بسبب إطار مهزوز أثناء الجري:** فقدان التتبع من 0.405 إلى 0.373.
- **تذبذب المسار:** الوسيط من 0.039 إلى 0.024 م، والإزاحة من 0.124 إلى 0.077 م.
- **الاهتزازات المتعارضة:** من 5.81 إلى 2.74 في الدقيقة.
- **المسار المرسوم:** صار على مستوى الأرض، ويختفي حين لا يوجد دليل.
- **عمر قناع التقسيم:** صار يُعوَّض بحركة العدّاء.
- **تكلفة النماذج الثقيلة:** من 15 إلى 9.3 تشغيل في الثانية.

## 14. المشكلات التي لا تزال قائمة

- **معظم الإنذارات المتبقية على مقاطع الأوراق مصدرها قناة العمق:** عوائق وانحدارات حقيقية بجوار الممر (ضفة بحيرة، جذوع)، مع افتراضات تقريبية لارتفاع الكاميرا في فيديوهات Mixkit. ليست أوراقًا لكنها ليست كلها مخاطر في الممر.
- **دليل العوائق متقطع من إطار لآخر.** الإمساك جُرّب ورُفض، وتتبع العوائق (ByteTrack) هو الحل المقترح.
- **الخطوط الوهمية من الكاشف اللوني ما زالت أكبر مصدر لتذبذب القياس الخام.**
- **زمن إنذار العائق زاد أقل من إطار:** 0.114 → 0.148 ث.
- **SegFormer ترخيصه غير تجاري، ولم يُستبدل.**
- **الخط المتعرج والضباب والمضامير بكاميرا صدر غير ممثلة في الفيديوهات.**

## 15. القيود التقنية

- **لا هاتف ولا كاميرا حية ولا جيروسكوب حقيقي ولا قياس طاقة.** الزمن مقاس على معالج x86 في الحاوية.
- **معايرة كاميرا تقريبية:** فيديوهات Mixkit بلا معايرة. الميل قُدّر من العمق بدالة التطبيق نفسها، والارتفاع ومجال الرؤية مفترضان. SANPO بمعايرة حقيقية لمجال الرؤية، والارتفاع مفترض 1.3 م.
- **نماذج القنوات تُعتبر حديثة في كل إطار:** في إعادة التشغيل، عدا الإطارات المجدولة. على الهاتف تأتي متأخرة.
- **وسوم «واضح» و«عائق» وضعها وكيل البحث بالنظر:** إطار واحد في كل ثانية، ولم يراجعها إنسان ثانٍ.
- **بيانات الوضع في SANPO كانت إشارة دورانها معكوسة،** واستُبدلت بالدوران البصري.

## 16. تعليمات إعادة تشغيل التجارب

```bash
cd /home/user/BasirRun/project && gradle :engine:test :replay:installDist :replay:test :appcheck:compileKotlin
cd /home/user/BasirRun/BasirRun_Research/tools && python3 -I sanpo_fetch.py ../datasets/egocentric/sanpo/selected_sessions.txt ../datasets/egocentric/sanpo 180
python3 -I sanpo_gt_fetch.py ../datasets/egocentric/sanpo/test_with_masks.txt ../datasets/walkable_area/sanpo_gt/test 20
cd ../manifests && python3 -I build_video_manifest.py && python3 -I build_repo_manifest.py && python3 -I build_datasets.py
cd ../benchmarks && python3 -I run_all.py stage1 --candidates light && python3 -I run_all.py stage2
python3 -I eval_gt.py prepare && python3 -I eval_gt.py score
python3 -I bench_metrics.py && python3 -I stab_metrics.py && python3 -I jitter_causes.py && python3 -I timing.py && python3 -I validate_visual_yaw.py
python3 -I ../reports/build_stab_report.py && python3 -I ../reports/build_final_report.py
cd /home/user/BasirRun && for g in GATES.md GATES_STAB.md; do node <unlazy>/scripts/gate-check.mjs $g; done
```

## 17. المراجع والروابط والتراخيص

- **المستودعات (43):** `manifests/github_repositories.csv`، مع الترخيص لكل واحد.
- **الأبحاث (40):** `research_papers/papers.csv`.
- **مجموعات البيانات (18):** `manifests/datasets.csv`.
- **SANPO:** Waghmare et al., WACV 2025، ترخيص CC BY 4.0. استُخدمت 25 جلسة فيديو وجلسات الحقيقة الأرضية.
- **RUGD:** Wigness et al., IROS 2019. استُخدمت العينة الرسمية للبحث.
- **Mixkit:** Mixkit Stock Video Free License. استخدام مجاني دون إعادة توزيع المقاطع كمخزون.
- **النماذج:** SegFormer (رخصة NVIDIA غير تجارية)، وD-FINE (Apache-2.0)، وDepth Anything V2 Small (Apache-2.0)، وProject Guideline (Apache-2.0). وللمرشحين: YOLOP (MIT)، وCLIPSeg (Apache-2.0 على Hugging Face)، وSegFormer-B2 (رخصة NVIDIA)، وMask2Former (رخصة «other» على Hugging Face).

## أرقام مفتاحية (للتحقق الآلي)

```json
{
 "gt_test_frames": 600,
 "gt_test_sessions": 30,
 "current_iou_broad": 0.6589120858219402,
 "repos": 43,
 "papers": 40,
 "datasets": 18,
 "videos": 44,
 "jitter_m": [
  0.038603464329242915,
  0.024295697202598363
 ],
 "false_alarm_rate": [
  0.42391304347826086,
  0.3804347826086957
 ],
 "obstacle_recall": [
  0.9900990099009901,
  0.9900990099009901
 ],
 "alarm_frames_leaf": [
  0.24791666666666665,
  0.209375
 ]
}
```
