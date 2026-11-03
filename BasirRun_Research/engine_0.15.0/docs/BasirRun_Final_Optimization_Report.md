# BasirRun_Final_Optimization_Report — التقرير النهائي للجولة 7 (المحرك 0.14.0)

> **لم يُختبر أي شيء على هاتف أو في الميدان.** كل الأرقام من إعادة تشغيل كود التطبيق نفسه على 24 مقطعًا مسجَّلًا، مرة بنتائج نماذج فورية ومرة بمحاكاة تأخر الهاتف. لا يُستعمل للجري المستقل قبل اختبار ميداني مع مرافق.

## 1. حالة المشروع قبل التطوير

النسخة 0.13.0:
- **الاختبارات:** 123 اختبارًا للمحرك ناجحة.
- **أدوات القياس:** غير موجودة في الأرشيف، فأُعيد بناؤها.
- **التقرير الكامل:** `BasirRun_Engine_Audit_0.13.0.md`.
- **خط الأساس المقاس:** `BasirRun_Baseline_Benchmark.md`.

## 2. مكونات المحرك الحالي

- **الكاميرا:** نسخ YUV ← RGB بعرض 240 بكسل.
- **قناة الأرض والخط:** SegFormer-B0 وGuideline، في إطارات رئيسية تكيفية.
- **قناة السلامة:** D-FINE-N وDepth Anything V2، مع مستوى الأرض بـ RANSAC.
- **كشف الخطوط:** باللون والتباين.
- **التخطيط:** `IntelligentPathPlanner` مع `TemporalPathTracker` (Kalman وحركة العدّاء)، ثم `PathStabilityController`، ثم `GroundRibbon` للرسم على الأرض.
- **التوجيه:** `StableGuidance`، ثم `DecisionEngine`، ثم `GuidanceTemporalStabilizer`، ثم `HapticScheduler`.

التفاصيل والقوة والضعف لكل مكوّن في جدول تقرير الفحص (§2).

## 3. المكونات التي ثبتت قوتها واحتُفظ بها

- **النماذج الأربعة كما هي:** لا بديل مرخّصًا ومتفوقًا مثبتًا (§5).
- **`GroundRibbon`:** الرسم على الأرض هندسي وصحيح بالاختبارات، ولا إحداثيات شاشة ثابتة.
- **`TemporalPathTracker` و`PathStabilityController`:** محتفظ بهما. الانتقال السلس جُرّب ورُفض.
- **قواعد التوقف للحالات القصوى، و`AdaptiveFrameController`، و`VisualYawEstimator`.**
- **مسافات الخطر الحالية:** نسخة «بحسب السرعة» رُفضت.

## 4. الأخطاء التي أُصلحت

1. **نموذج Guideline لا يُفتح مع ONNX Runtime الحديث** بـ ALL_OPT. أُضيف إعادة الفتح بإعدادات بديلة، والمخرج مطابق.
2. **العوائق كانت تُفحص على ممر لا علاقة له بالمسار المرسوم.** صار الفحص على المسار المتَّبع، مع ضمانات الممر المستقيم القريب، وحافة الصورة، والكاميرا الأفقية.
3. **قلب اليمين ↔ اليسار عبر أمر «على المسار».**
4. **حد «القناع القديم»** كان يُسقط أقنعة صالحة مع تأخر الهاتف، فينهار المسار.
5. **أدوات القياس:** صارت تحاكي تأخر القنوات (`--latency`). بلا هذه المحاكاة لا يظهر الخطأ رقم 4 أصلًا.

## 5. التقنيات التي اختُبرت

## جدول البدائل والمقارنة

المصدر مذكور مع كل رقم:
- **«منشور»:** ادعاء أصحاب المشروع، لم يُقس هنا.
- **«ج6»:** مقاس في الجولة السابقة، والتقرير في `docs/BasirRun_Deep_Research_Final_Report.md` (ملفات نتائجه غير موجودة في الأرشيف).
- **«ج7»:** مقاس في هذه الجولة على المقاطع والإطارات نفسها.

لم يُبحث عن بديل لكل شيء. القائمة مرتبطة بالمشكلات التي وُجدت في التقرير `BasirRun_Engine_Audit_0.13.0.md`.

| التقنية أو النموذج | المشروع والرابط | الوظيفة | الدقة المنشورة | نتيجة الاختبار الفعلي | زمن المعالجة | الحجم | الذاكرة | الهاتف | الترخيص | المميزات والقيود | مقابل الحالي |
|---|---|---|---|---|---|---|---|---|---|---|---|
| ممر عوائق يتبع المسار المنحني (العمق) + تقييم الأجسام بالأمتار على المسار المتَّبع | داخلي (هذه الجولة) | ربط العائق بالمسار المتوقع | — | ج7: انظر جدول القرار (الإنذار الخاطئ والاستدعاء) | أقل من 1 ms (نقاط موجودة أصلًا) | 0 | مهملة | نعم | المشروع | يستخدم المسار الذي يُرسم فعلًا. يعتمد على صحة المسار نفسه | **مدمج** (§قرارات) |
| الممر المستقيم بزاوية ثابتة (الحالي) | `DepthHazards` 0.13.0 | — | — | ج7: الأساس | — | — | — | نعم | — | يلتقط ما بجانب المنعطفات | محتفظ به لمستوى الرأس والهبوط |
| انتقال سلس للمسار المرسوم عند مسار جديد | داخلي | تقليل القفزات | — | ج7: جدول القرار | أقل من 0.1 ms | 0 | 0 | نعم | المشروع | 1–3 إطارات حتى الوصول. لا يمس فحص العوائق | **مدمج** |
| ذاكرة الجهة في مثبّت الأوامر | داخلي (إصلاح) | منع قلب يمين ↔ يسار | — | ج7: اختبار وحدة + جدول القرار | 0 | 0 | 0 | نعم | المشروع | لا يؤخر التوقف ولا العائق ولا التصحيح القوي | **مدمج** |
| الجري بجانب خط قوي حتى 2 م، مع ربط بالخط المقفول عليه | داخلي | خطوط الملاعب والمضامير | — | ج7: 5 اختبارات وحدة + مقاطع المضمار | 0 | 0 | 0 | نعم | المشروع | **لا توجد مقاطع حقيقية لمحيط ملعب كرة قدم**. الزوايا (90°) غير مدعومة | **مدمج** مع مفتاح إيقاف |
| مسافات خطر بحسب السرعة | داخلي | تقليل إنذارات المشي | — | ج7: جدول القرار | 0 | 0 | 0 | نعم | المشروع | يغيّر وضع المشي فقط | بحسب القياس (§قرارات) |
| فتح النموذج الكمّي بخيار `session.disable_quant_qdq` | ONNX Runtime ([microsoft/onnxruntime](https://github.com/microsoft/onnxruntime)) | تحميل نموذج Guideline | — | ج7: المخرج مطابق تمامًا (فرق 0.0)، و28 ms مقابل 39 ms بالتحسين الأساسي (x86، خيطان) | 28 ms | 0.58 MB | — | نعم (Java API `addConfigEntry`) | MIT | يحل خطأ «two nodes with same node name» | **مدمج** (إصلاح تنفيذ) |
| ByteTrack | [ifzhang/ByteTrack](https://github.com/ifzhang/ByteTrack) | ربط الاكتشافات عبر الزمن (Kalman + IoU، يستخدم الاكتشافات منخفضة الثقة) | منشور: MOTA 80.3 على MOT17 | **لم يُختبر** في هذه الجولة | منشور: خفيف (بلا شبكة) | — | — | نعم (منطق فقط) | MIT | الحل المقترح لتقطع دليل العوائق | مؤجَّل: يحتاج وسومًا لهوية الأجسام |
| RAFT (تدفق بصري) | [princeton-vl/RAFT](https://github.com/princeton-vl/RAFT) | حركة الكاميرا | منشور: Sintel clean EPE 1.43 | لم يُختبر: ثقيل على الهاتف | منشور: عشرات ms على GPU | 5.3M معامل | — | ضعيف | BSD-3 | دقيق جدًا، والكلفة عالية | مرفوض (E) للهاتف |
| Farneback (OpenCV) | [opencv/opencv](https://github.com/opencv/opencv) | تدفق كثيف مرجعي | — | ج6: ارتباط وسيطه 0.78 مع `VisualYawEstimator` | — | — | — | نعم | Apache-2.0 | مرجع للتحقق فقط | الحالي (`VisualYawEstimator`) محتفظ به |
| أرض العمق (مستوى الأرض من Depth Anything) | داخلي على [Depth-Anything-V2](https://github.com/DepthAnything/Depth-Anything-V2) | منطقة قابلة للمشي | — | ج6: IoU 0.742 مقابل 0.659 للحالي، وأرض «آمنة» خاطئة 0.111 مقابل 0.083 | ج6: 9 ms معالجة لاحقة | 0 إضافي | — | نعم | Apache-2.0 (Small) | أرخص تحسن، لكنه يقبل أرضًا غير آمنة أكثر | لم يُدمج: الدليل غير حاسم في ج6 ولم يُعَد اختباره |
| Mask2Former Swin-T | [facebookresearch/Mask2Former](https://github.com/facebookresearch/Mask2Former) | تقسيم دلالي | منشور: ADE20K mIoU 47.7 | ج6: IoU 0.745 | ج6: 653 ms CPU | 47M | — | ثقيل | الكود MIT، والأوزان «other» على HF | أدق قليلًا وأبطأ بعشرين مرة | مرفوض (D/E) |
| SegFormer-B2 | [NVlabs/SegFormer](https://github.com/NVlabs/SegFormer) | تقسيم دلالي | منشور: ADE20K mIoU 46.5 | ج6: IoU 0.734 | ج6: 928 ms | 27M | — | ثقيل | NVIDIA غير تجاري | — | مرفوض |
| SegFormer-B0 (الحالي) | NVlabs/SegFormer | الأرض القابلة للمشي | منشور: ADE20K mIoU 37.4 | ج6: IoU 0.659. ج7: الأساس | ج7: 67 ms (x86، 4 خيوط) | 15 MB | — | نعم | **NVIDIA غير تجاري** | خطر ترخيص قائم | محتفظ به (لا بديل مثبت التفوق ومرخّص) |
| YOLOP | [hustvl/YOLOP](https://github.com/hustvl/YOLOP) | منطقة قيادة + حارات | منشور: BDD100K mIoU 91.5 للمنطقة القابلة للقيادة | ج6: IoU 0.417 (يفشل على الأرصفة) | ج6: 133 ms | 32 MB | — | نعم | MIT | منظور سيارة | مرفوض (B) |
| CLIPSeg | [timojl/clipseg](https://github.com/timojl/clipseg) | تقسيم بوصف نصي | — | ج6: IoU 0.577 | ج6: 2303 ms | 150M | — | لا | MIT (كود) | للتحليل خارج الهاتف | مرفوض (E) |
| Ultra-Fast-Lane-Detection-v2 | [cfzd/Ultra-Fast-Lane-Detection-v2](https://github.com/cfzd/Ultra-Fast-Lane-Detection-v2) | خطوط حارات | منشور: CULane F1 ~81 | **لم يُختبر**: مدرب على حارات سيارات | منشور: سريع جدًا | — | — | نعم | MIT | يحتاج إعادة تدريب على المضامير والملاعب | مؤجَّل |
| TopFormer / PP-LiteSeg | [hustvl/TopFormer](https://github.com/hustvl/TopFormer)، [PaddleSeg](https://github.com/PaddlePaddle/PaddleSeg) | تقسيم خفيف بترخيص مفتوح | منشور: ADE20K mIoU حوالي 37–38 (TopFormer-B) | **لم يُختبر**: يحتاج تدريبًا على بيانات منظور الصدر | منشور: خفيف | ~5 MB | — | نعم | Apache-2.0 | البديل المرخّص المحتمل لـ SegFormer-B0 | مؤجَّل (خارطة الطريق) |


## 6. التقنيات التي دُمجت وأسبابها

- **ربط العوائق بالمسار (A).**
- **ذاكرة الجهة (F).**
- **حد القناع القديم (F).**
- **فتح نموذج Guideline (F).**
- **الجري بجانب خط ملعب قوي (C):** افتراضي بطلب المالك لأن التطبيق موجّه للرياضيين.

الأرقام في `BasirRun_Upgrade_Decision_Log.md`.

## 7. التقنيات التي رُفضت وأسبابها

- **الانتقال السلس للمسار (D):** يزيد التأخر.
- **تعويض عمر العمق (D):** بلا أثر.
- **المسافات بحسب السرعة (D):** فائدة صغيرة، وتزيل توقفات غير مثبتة الخطأ.
- **مسافة إنذار 6 م للأجسام:** ترفع الإنذارات الكاذبة.
- **فحص مستوى الرأس على الممر المنحني:** يضيف توقفات بجانب الأعمدة.
- **مرفوضة من بحث الجولة 6 (مذكورة في §5):** YOLOP، وCLIPSeg، وMask2Former، وSegFormer-B2.

## 8. نتائج Benchmark قبل وبعد

كل الأعمدة على المقاطع والإطارات ونتائج النماذج نفسها:
- **0.13.0:** النسخة الأصلية.
- **0.14.0:** النسخة الجديدة بقيمها الافتراضية.
- **+ latency:** مع محاكاة تأخر الهاتف (300 ms لقناة الأرض، و450 ms لقناة العمق والأجسام).

**Group: all** (24 clips)

| Metric | 0.13.0 | 0.14.0 | 0.13.0 + latency | 0.14.0 + latency | better |
|---|---|---|---|---|---|
| Path jitter at 3 m, median (m) | 0.033 | 0.033 | 0.008 | 0.012 | low |
| Path jitter at 3 m, p90 (m) | 0.152 | 0.152 | 0.147 | 0.192 | low |
| Frame-to-frame path variation, std (m) | 0.205 | 0.216 | 0.180 | 0.161 | low |
| Drawn path vs evidence path at 3 m (lag, m) | 0.076 | 0.075 | 0.064 | 0.113 | low |
| Path continuity (pairs < 0.15 m) | 0.703 | 0.704 | 0.458 | 0.595 | high |
| Path availability (frames with a drawn path) | 0.781 | 0.781 | 0.413 | 0.714 | high |
| Tracking failure rate | 0.220 | 0.221 | 0.587 | 0.289 | low |
| Path drops per minute | 6.75 | 6.96 | 14.14 | 7.30 | low |
| Error vs walked path, 1-4 m, median (m) [SANPO] | 0.188 | 0.188 | 0.305 | 0.260 | low |
| Vibrations played per minute | 28.2 | 30.7 | 22.3 | 24.9 | low |
| Left<->right flips within 2 s per minute | 0.00 | 0.00 | 0.00 | 0.00 | low |
| Conflicting vibration pairs per minute | 0.62 | 1.04 | 0.42 | 1.21 | low |
| Decision cue changes per minute | 60.3 | 70.9 | 45.1 | 32.6 | low |
| False alarm rate (GT-clear frames with warning/stop) [SANPO] | 0.584 | 0.462 | 0.671 | 0.457 | low |
| False stop rate (GT-clear frames with stop) [SANPO] | 0.002 | 0.003 | 0.006 | 0.000 | low |
| Obstacle recall (closing events alarmed) [SANPO] | 0.964 | 0.964 | 0.964 | 0.929 | high |
| Closing obstacle events (GT) | 28 | 28 | 28 | 28 |  |
| Alarm latency from event start (s) | 0.000 | 0.000 | 0.017 | 0.018 | low |
| App logic per frame, median (ms, JVM x86) | 53.5 | 49.5 | 39.1 | 45.1 | low |
| App logic per frame, p90 (ms, JVM x86) | 77.2 | 70.0 | 67.3 | 64.6 | low |
| Heavy surface model runs per second | 8.19 | 8.21 | 10.65 | 9.23 | low |

**Group: sanpo** (18 clips)

| Metric | 0.13.0 | 0.14.0 | 0.13.0 + latency | 0.14.0 + latency | better |
|---|---|---|---|---|---|
| Path jitter at 3 m, median (m) | 0.039 | 0.038 | 0.008 | 0.010 | low |
| Path jitter at 3 m, p90 (m) | 0.152 | 0.152 | 0.154 | 0.192 | low |
| Frame-to-frame path variation, std (m) | 0.173 | 0.188 | 0.190 | 0.178 | low |
| Drawn path vs evidence path at 3 m (lag, m) | 0.085 | 0.084 | 0.063 | 0.111 | low |
| Path continuity (pairs < 0.15 m) | 0.718 | 0.719 | 0.433 | 0.592 | high |
| Path availability (frames with a drawn path) | 0.799 | 0.798 | 0.400 | 0.782 | high |
| Tracking failure rate | 0.202 | 0.203 | 0.600 | 0.222 | low |
| Path drops per minute | 6.11 | 6.39 | 16.11 | 7.78 | low |
| Error vs walked path, 1-4 m, median (m) [SANPO] | 0.188 | 0.188 | 0.305 | 0.260 | low |
| Vibrations played per minute | 31.4 | 34.7 | 23.3 | 26.4 | low |
| Left<->right flips within 2 s per minute | 0.00 | 0.00 | 0.00 | 0.00 | low |
| Conflicting vibration pairs per minute | 0.83 | 1.39 | 0.56 | 1.11 | low |
| Decision cue changes per minute | 70.8 | 85.8 | 53.9 | 38.9 | low |
| False alarm rate (GT-clear frames with warning/stop) [SANPO] | 0.584 | 0.462 | 0.671 | 0.457 | low |
| False stop rate (GT-clear frames with stop) [SANPO] | 0.002 | 0.003 | 0.006 | 0.000 | low |
| Obstacle recall (closing events alarmed) [SANPO] | 0.964 | 0.964 | 0.964 | 0.929 | high |
| Closing obstacle events (GT) | 28 | 28 | 28 | 28 |  |
| Alarm latency from event start (s) | 0.000 | 0.000 | 0.017 | 0.018 | low |
| App logic per frame, median (ms, JVM x86) | 54.1 | 49.7 | 39.7 | 45.1 | low |
| App logic per frame, p90 (ms, JVM x86) | 75.4 | 69.6 | 66.0 | 64.7 | low |
| Heavy surface model runs per second | 8.19 | 8.23 | 11.05 | 9.41 | low |

**Group: sanpo_hq** (7 clips)

| Metric | 0.13.0 | 0.14.0 | 0.13.0 + latency | 0.14.0 + latency | better |
|---|---|---|---|---|---|
| Path jitter at 3 m, median (m) | 0.035 | 0.035 | 0.007 | 0.014 | low |
| Path jitter at 3 m, p90 (m) | 0.135 | 0.135 | 0.146 | 0.220 | low |
| Frame-to-frame path variation, std (m) | 0.177 | 0.177 | 0.153 | 0.151 | low |
| Drawn path vs evidence path at 3 m (lag, m) | 0.057 | 0.057 | 0.034 | 0.140 | low |
| Path continuity (pairs < 0.15 m) | 0.732 | 0.735 | 0.389 | 0.505 | high |
| Path availability (frames with a drawn path) | 0.894 | 0.891 | 0.543 | 0.860 | high |
| Tracking failure rate | 0.106 | 0.110 | 0.457 | 0.145 | low |
| Path drops per minute | 5.00 | 5.00 | 15.71 | 7.14 | low |
| Error vs walked path, 1-4 m, median (m) [SANPO] | 0.169 | 0.169 | 0.175 | 0.188 | low |
| Vibrations played per minute | 26.4 | 31.4 | 22.1 | 24.3 | low |
| Left<->right flips within 2 s per minute | 0.00 | 0.00 | 0.00 | 0.00 | low |
| Conflicting vibration pairs per minute | 0.00 | 0.71 | 0.71 | 0.71 | low |
| Decision cue changes per minute | 80.7 | 104.3 | 75.0 | 45.7 | low |
| False alarm rate (GT-clear frames with warning/stop) [SANPO] | 0.509 | 0.396 | 0.507 | 0.450 | low |
| False stop rate (GT-clear frames with stop) [SANPO] | 0.000 | 0.002 | 0.000 | 0.000 | low |
| Obstacle recall (closing events alarmed) [SANPO] | 0.500 | 0.500 | 1.000 | 1.000 | high |
| Closing obstacle events (GT) | 2 | 2 | 2 | 2 |  |
| Alarm latency from event start (s) | 0.000 | 0.000 | 0.000 | 0.000 | low |
| App logic per frame, median (ms, JVM x86) | 54.2 | 48.9 | 38.1 | 45.0 | low |
| App logic per frame, p90 (ms, JVM x86) | 74.6 | 70.3 | 62.0 | 66.5 | low |
| Heavy surface model runs per second | 7.19 | 7.21 | 10.26 | 8.85 | low |

**Group: track** (5 clips)

| Metric | 0.13.0 | 0.14.0 | 0.13.0 + latency | 0.14.0 + latency | better |
|---|---|---|---|---|---|
| Path jitter at 3 m, median (m) | 0.007 | 0.007 | 0.077 | 0.042 | low |
| Path jitter at 3 m, p90 (m) | 0.184 | 0.184 | 0.148 | 0.123 | low |
| Frame-to-frame path variation, std (m) | 0.336 | 0.334 | 0.169 | 0.094 | low |
| Drawn path vs evidence path at 3 m (lag, m) | 0.045 | 0.045 | 0.071 | 0.122 | low |
| Path continuity (pairs < 0.15 m) | 0.602 | 0.602 | 0.459 | 0.563 | high |
| Path availability (frames with a drawn path) | 0.692 | 0.692 | 0.372 | 0.430 | high |
| Tracking failure rate | 0.309 | 0.309 | 0.628 | 0.570 | low |
| Path drops per minute | 9.50 | 9.50 | 8.04 | 6.14 | low |
| Vibrations played per minute | 14.0 | 14.0 | 15.7 | 15.7 | low |
| Left<->right flips within 2 s per minute | 0.00 | 0.00 | 0.00 | 0.00 | low |
| Conflicting vibration pairs per minute | 0.00 | 0.00 | 0.00 | 0.00 | low |
| Decision cue changes per minute | 6.9 | 6.9 | 9.1 | 6.6 | low |
| App logic per frame, median (ms, JVM x86) | 53.6 | 50.3 | 39.0 | 47.0 | low |
| App logic per frame, p90 (ms, JVM x86) | 70.4 | 67.2 | 75.4 | 62.2 | low |
| Heavy surface model runs per second | 8.69 | 8.69 | 9.76 | 9.08 | low |

**Group: owner** (1 clips)

| Metric | 0.13.0 | 0.14.0 | 0.13.0 + latency | 0.14.0 + latency | better |
|---|---|---|---|---|---|
| Path jitter at 3 m, median (m) | 0.009 | 0.009 | 0.006 | 0.022 | low |
| Path jitter at 3 m, p90 (m) | 0.083 | 0.083 | 0.105 | 0.247 | low |
| Frame-to-frame path variation, std (m) | 0.127 | 0.127 | 0.071 | 0.184 | low |
| Drawn path vs evidence path at 3 m (lag, m) | 0.057 | 0.057 | 0.051 | 0.107 | low |
| Path continuity (pairs < 0.15 m) | 0.944 | 0.944 | 0.890 | 0.819 | high |
| Path availability (frames with a drawn path) | 0.904 | 0.904 | 0.864 | 0.914 | high |
| Tracking failure rate | 0.096 | 0.096 | 0.136 | 0.086 | low |
| Path drops per minute | 4.55 | 4.55 | 9.09 | 4.55 | low |
| Vibrations played per minute | 40.9 | 40.9 | 36.4 | 45.5 | low |
| Left<->right flips within 2 s per minute | 0.00 | 0.00 | 0.00 | 0.00 | low |
| Conflicting vibration pairs per minute | 0.00 | 0.00 | 0.00 | 9.09 | low |
| Decision cue changes per minute | 136.4 | 122.7 | 68.2 | 50.0 | low |
| App logic per frame, median (ms, JVM x86) | 42.2 | 41.0 | 30.3 | 35.6 | low |
| App logic per frame, p90 (ms, JVM x86) | 143.5 | 91.0 | 49.4 | 76.5 | low |
| Heavy surface model runs per second | 5.61 | 5.61 | 7.88 | 6.74 | low |


## 9. قياسات تذبذب المسار

**بنتائج فورية:** المسار نفسه تقريبًا في النسختين.
- التذبذب p90 عند 3 م: 0.152 ← 0.152 م.
- الاستمرارية: 0.703 ← 0.704.

**مع تأخر الهاتف:**
- التوفر: 0.413 ← 0.714.
- الاستمرارية: 0.458 ← 0.595.
- التغيّر بين الإطارات: 0.180 ← 0.161 م.
- الانقطاعات في الدقيقة: 14.14 ← 7.30.
- **لكن التذبذب p90 ارتفع:** 0.147 ← 0.192 م، والتأخر عن المسار المقاس من 0.064 إلى 0.113 م. السبب أن المسار صار موجودًا في إطارات صعبة كان غائبًا فيها.

## 10. قياسات استقرار التوجيه

- **قلب يمين ↔ يسار خلال ثانيتين:** 0.00 ← 0.00 في الدقيقة.
- **الاهتزازات في الدقيقة:** 28.2 ← 30.7. الزيادة سببها أن نبضات «على المسار» صارت تظهر بعدما قلت الإنذارات الكاذبة.
- **الأزواج المتعارضة في الدقيقة:** 0.62 ← 1.04. معظمها تحذير يتبعه «على المسار» بعد انتهاء نافذة الهدوء (1.5 ث).

## 11. قياسات زمن الاستجابة

- **منطق التطبيق لكل إطار (JVM على x86، دون النماذج):** وسيط 53.5 ← 49.5 ms.
- **زمن الإنذار من بداية الحدث:** 0.000 ← 0.000 ث فوريًا، و0.017 ← 0.018 ث مع التأخر.
- **أزمنة النماذج على x86:** في `BasirRun_Baseline_Benchmark.md` §4.
- **غير مقاس:** زمن الهاتف الحقيقي والطاقة، لعدم وجود جهاز.

## 12. نتائج اكتشاف العوائق

| | 0.13.0 | 0.14.0 | 0.13.0 + latency | 0.14.0 + latency |
|---|---|---|---|---|
| الإنذار الكاذب على الإطارات الخالية (SANPO) | 0.584 | 0.462 | 0.671 | 0.457 |
| التوقف الخاطئ | 0.002 | 0.003 | 0.006 | 0.000 |
| استدعاء الأحداث المقتربة | 0.964 | 0.964 | 0.964 | 0.929 |

**الحدث الذي فُقد مع التأخر:** على مقطع الدرج الحجري VWw2W_ob، والسبب في §13.

## 13. المشكلات المتبقية

- **مقطع الدرج الحجري:** مع تأخر الهاتف يُفوَّت حدث مقترب واحد بعد إصلاح القناع القديم (الاستدعاء 0.964 ← 0.929). إنذار الأصل هناك جاء من توقف سببه سقوط القناع، لا من اكتشاف الدرج. **الدرج الصاعد لا تكتشفه أي من النسختين.** هذا قرار للمالك: إصلاح القناع يضاعف توفر المسار تقريبًا ويصفّر التوقف الخاطئ، ويفقد هذا الإنذار العرضي.
- **زوايا ملعب كرة القدم (90°) غير مدعومة:** الخط العرضي مرفوض بالتصميم. المطلوب كاشف خطوط عرضية وفيديو حقيقي لمحيط ملعب بكاميرا على الصدر، **ولم يتوفر هذا الفيديو**.
- **ترخيص SegFormer-B0 غير تجاري.**
- **الخطوط الوهمية من الكاشف اللوني،** وتقطع دليل العوائق (يحتاج تتبعًا مثل ByteTrack).
- **أخطاء تسمية الكاشف** (قطعة قماش تُسمّى «bear»).
- **الواجهة غير موجودة في الأرشيف:** يجب رسم `line.ribbon` بدل `virtualPath`، وإلا قد يبدو الخط «عائمًا».
- **لا اختبار على هاتف:** أزمنة التأخر 300/450 ms افتراض.

## 14. تعليمات تشغيل الاختبارات وإعادة إنتاج النتائج

```bash
# 1. stage 1: the app's four ONNX models on every clip (SANPO frames are fetched with gsutil)
bash scripts/build_all.sh            # = scripts/build_one.sh per SANPO session + the Mixkit and owner clips
# 2. ground truth from SANPO metric depth + camera poses
python3 -I scripts/gt_sanpo.py <session> bench/clips/<clip> data/gtwork/<clip>
# 3. replays (baseline build from the tag baseline-0.13.0, upgraded build from the branch)
git -C project worktree add ../builds/baseline_src baseline-0.13.0 && (cd builds/baseline_src && gradle :replay:installDist)
(cd project && gradle :engine:test :replay:test :replay:installDist :appcheck:compileKotlin)
bash scripts/run_matrix.sh && bash scripts/run_matrix2.sh && bash scripts/run_matrix3.sh
# 4. metrics, tables, reports, gates
python3 -I scripts/bench_metrics.py bench/runs/<run> ...
python3 -I scripts/build_docs.py
node <unlazy>/scripts/gate-check.mjs --timeout 1800 GATES.md
```

- **سجل البوابات:** `GATES.md`، وكل بوابة لها سكربت تحقق في `scripts/gates/`.
- **النسخة الأصلية:** الوسم `baseline-0.13.0`. **النسخة الجديدة:** الفرع `upgrade-0.14`.
- **الرجوع لسلوك 0.13.0 تعديلًا تعديلًا:**
  - `PathLinkedHazards.enabled`
  - `PlannerConfig.followDistantLines`
  - `GuidanceStabilizerConfig.sideMemoryS = 0`
  - `GuidancePipeline.STALE_FOLLOWS_KEYFRAMES`
