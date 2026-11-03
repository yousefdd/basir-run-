# BasirRun_Baseline_Benchmark — نتائج المحرك الأصلي 0.13.0 قبل أي تعديل

**النسخة:** 0.13.0 كما هي في `BasirRun_Detection_Engine_0.13.0.zip` (SHA-256 `f24bc3e1…e7a7`)، الوسم `baseline-0.13.0` في المستودع، ونسخة احتياطية مطابقة بايتًا ببايت (`BasirRun_Detection_Engine_0.13.0_ORIGINAL_BACKUP.zip`).
**الأدوات:** JDK 21 (toolchain 17)، وGradle 8.14.3، وKotlin 2.0.21، وONNX Runtime 1.29.0 (Python)، وffmpeg. المعالج x86 بـ 4 أنوية في الحاوية، **لا هاتف**.

## 1. ما الذي حُفظ

- **الكود والنماذج والإعدادات:** في الوسم `baseline-0.13.0`. البوابة G1 تتحقق آليًا من تطابق 98 ملفًا مع الأرشيف الأصلي.
- **اختبارات الأصل:** 123 اختبارًا للمحرك و5 لأداة الإعادة، كلها ناجحة. فحص ترجمة كود أندرويد ناجح.
- **نتائج النماذج لكل إطار:** محفوظة لكل مقطع في `bench/clips/<clip>/`، فكل نسخة تُقارن على **المدخلات نفسها بالضبط**.

## 2. فيديوهات الاختبار (24 مقطعًا، 4713 إطارًا بـ 15 إطارًا/ث، حوالي 5.2 دقائق)

| المقطع | المصدر والترخيص | الحركة | البيئة | الأرضية | الطقس/الإضاءة | الإطارات | الحقيقة الأرضية |
|---|---|---|---|---|---|---|---|
| mixkit_32804 | Mixkit (32804, Mixkit Free License) | جري | مضمار ألعاب قوى | تارتان | مشمس | 281 | لا |
| mixkit_32813 | Mixkit (32813, Mixkit Free License) | جري | مضمار ألعاب قوى | تارتان | مشمس | 215 | لا |
| sanpo_0X2rC_H2 | SANPO-Real `0X2rC_H23KWjTW1I7w8lbnmd0oA761Za` (CC BY 4.0) | jogging | park | light_gray | sunny, visibility high | 180 | نعم |
| sanpo_60_ibYCD | SANPO-Real `60_ibYCD9plAr45fi9Hk96_agYJhZeTR` (CC BY 4.0) | walking | urban | light_gray,pavers | cloudy, visibility medium | 180 | نعم |
| sanpo_66XHCmoc | SANPO-Real `66XHCmocuru678D4ehkbnBD9yjBpbOir` (CC BY 4.0) | jogging | rural,open_terrain | terrain | sunny, visibility high | 180 | نعم |
| sanpo_6D6CrPEV | SANPO-Real `6D6CrPEVDCCu0sAWU67oTzZY36bADan9` (CC BY 4.0) | walking | urban,park | terrain | sunny, visibility high | 180 | نعم |
| sanpo_9MyRHsYK | SANPO-Real `9MyRHsYKJyjYek6CF6moj8Vq3nek4u5-` (CC BY 4.0) | walking | rural,open_terrain | light_gray,gravel | sunny, visibility high | 180 | نعم |
| sanpo_BMdZBHtj | SANPO-Real `BMdZBHtjHF3PfJlRhm6jLJo7KeQE8p24` (CC BY 4.0) | jogging | urban,open_terrain | terrain | sunny, visibility high | 180 | نعم |
| sanpo_GMBhdIW2 | SANPO-Real `GMBhdIW2wgMNZlkOL9nrWZklQz2fxKXa` (CC BY 4.0) | walking | urban,road_junction | dark_gray | cloudy,rainy, visibility medium | 180 | نعم |
| sanpo_J30YHSYh | SANPO-Real `J30YHSYhXb5mimpOYoTi4WcJV0uEjBi5` (CC BY 4.0) | walking | urban,open_terrain | light_gray | sunny, visibility medium | 180 | نعم |
| sanpo_QVzjzsSe | SANPO-Real `QVzjzsSertgX6WAHcL5wP6pnnEieXu8f` (CC BY 4.0) | walking | urban,park | dark_gray,pavers | sunny, visibility high | 180 | نعم |
| sanpo_US7wvt5o | SANPO-Real `US7wvt5osva1gBg_TBJCKRwjXVFpM4dp` (CC BY 4.0) | walking | urban | dark_gray | rainy, visibility high | 180 | نعم |
| sanpo_VWw2W_ob | SANPO-Real `VWw2W_obR6zEafYeF_snujhcGroiwb0T` (CC BY 4.0) | walking | urban,park | light_gray | sunny, visibility high | 180 | نعم |
| sanpo_WwSUNtr6 | SANPO-Real `WwSUNtr6DxVDwu_EfUUGM-3GCaXbQ73a` (CC BY 4.0) | walking | open_space | color | sunny, visibility high | 180 | نعم |
| sanpo_ZR3hpsQx | SANPO-Real `ZR3hpsQxnbI8C4aSV2UMIKf15QvS6qvQ` (CC BY 4.0) | walking | urban,open_terrain | light_gray,pavers,gravel | sunny, visibility high | 180 | نعم |
| sanpo_fyfUW3nG | SANPO-Real `fyfUW3nG_8VEvEjlqFejpJzOrEVmzKYM` (CC BY 4.0) | jogging | urban | light_gray | sunny, visibility high | 180 | نعم |
| sanpo_huRVT5Bl | SANPO-Real `huRVT5BlKVdTYbUhQWrr2feodI612PZH` (CC BY 4.0) | running | urban,road_junction | light_gray,pavers | sunny, visibility high | 180 | نعم |
| sanpo_i2jglnBf | SANPO-Real `i2jglnBfoIqIIA7ojQGe-4vK07hUm4T3` (CC BY 4.0) | walking | suburban,road_junction,open_terrain | light_gray,pavers | sunny, visibility high | 180 | نعم |
| sanpo_oGmaqcOI | SANPO-Real `oGmaqcOIA2NkQySXCaQe-8b3moo5KiTC` (CC BY 4.0) | running | urban,road_junction | light_gray | sunny, visibility high | 180 | نعم |
| sanpo_tjIoesYz | SANPO-Real `tjIoesYzHoMBe28hDLk7d460KpMKcvJl` (CC BY 4.0) | jogging | urban | dark_gray | sunny, visibility high | 180 | نعم |
| user_garden_night | فيديو المالك (هاتف Samsung، أندرويد 16) | مشي | حديقة ليلًا | تراب، عشب، بلاط حجري | ليل، إضاءة صناعية | 198 | لا |


- **مقاطع SANPO:** الإطارات 0–179 من كاميرا الصدر اليسرى.
- **مقاطع Mixkit:** حتى 19 ث. ارتفاع الكاميرا ومجال الرؤية فيها مفترضان.
- **المقطع 32803:** عُكس زمنيًا، لأن الكاميرا الأصلية تتحرك إلى الخلف.
- **فيديو المالك:** مجال الرؤية فيه مفترض 42°، والميل مُقدَّر من العمق.
- **ما لا يوجد:** مقطع حقيقي لمحيط ملعب كرة قدم. مصادر الفيديو المتاحة لم تعطِ مقطعًا مناسبًا (مقاطع Mixkit للملاعب رسوم ثلاثية الأبعاد فاستُبعدت، وWikimedia حدّت الطلبات).

## 3. الحقيقة الأرضية (SANPO فقط، دون وسوم يدوية)

`scripts/gt_sanpo.py` يستخرجها من بيانات SANPO نفسها:
- **مستوى الأرض:** RANSAC على خرائط العمق المترية.
- **المسار المقطوع فعلًا:** مواضع الكاميرا في الثواني التالية من `fixed_camera_poses.csv`.
- **العوائق:** نقاط ارتفاعها بين 0.15 و2.0 م داخل ممر ±0.5 م حول المسار المقطوع.

**تعريفات:**
- **الإطار «الخالي»:** لا شيء مرتفع في الممر حتى الأفق (4 م مشيًا، و6 م جريًا).
- **الحدث «المقترب»:** عائق في الممر ضمن 3 م (4.5 م جريًا) يقترب 0.3 م أو أكثر خلال ثانية، أو يقع ضمن 1.5 م.

**جودة الحقيقة الأرضية:** متفاوتة. المجموعة `sanpo_hq` مقاطع حقيقتها عالية الثقة (توافق المستوى 0.4 أو أكثر، وانجراف رأسي 0.3 م أو أقل، وحركة أمامية 50 إطارًا أو أكثر): sanpo_0X2rC_H2, sanpo_60_ibYCD, sanpo_66XHCmoc, sanpo_9MyRHsYK, sanpo_BMdZBHtj, sanpo_ZR3hpsQx, sanpo_tjIoesYz. فيها حدثان مقتربان فقط، **وهذا لا يكفي للحكم على الاستدعاء فيها**.

## 4. ظروف القياس

- **«0.13.0»:** نتائج النماذج تُعامل كأنها جاهزة في الإطار نفسه، وهي طريقة أدوات الجولة 6.
- **«0.13.0 + latency»:** محاكاة قناتي الهاتف غير المتزامنتين (`--latency 300,450`). نماذج الأرض والخط تعطي نتيجتها بعد 300 ms، والعمق والكاشف بعد 450 ms، والإطارات التي تصل أثناء انشغال العامل لا تُحلَّل. **الزمنان افتراض قريب من هاتف متوسط، لا قياس على هاتف.**
- **أزمنة النماذج المقاسة على x86** (وسيط وسطاء المقاطع، مع تشغيل عمليتين متوازيتين، فهي أعلى من التشغيل المنفرد):
  - Depth Anything V2 Small: 221 ms (المدى 141–465).
  - D-FINE-N: 124 ms.
  - SegFormer-B0: 74 ms.
  - Guideline: 60 ms.

## 5. النتائج

تعريف كل مقياس في رأس `scripts/bench_metrics.py`. «better» يعني الاتجاه الأفضل.

**Group: all** (24 clips)

| Metric | 0.13.0 | 0.13.0 + latency | better |
|---|---|---|---|
| Path jitter at 3 m, median (m) | 0.033 | 0.008 | low |
| Path jitter at 3 m, p90 (m) | 0.152 | 0.147 | low |
| Frame-to-frame path variation, std (m) | 0.205 | 0.180 | low |
| Drawn path vs evidence path at 3 m (lag, m) | 0.076 | 0.064 | low |
| Path continuity (pairs < 0.15 m) | 0.703 | 0.458 | high |
| Path availability (frames with a drawn path) | 0.781 | 0.413 | high |
| Tracking failure rate | 0.220 | 0.587 | low |
| Path drops per minute | 6.75 | 14.14 | low |
| Error vs walked path, 1-4 m, median (m) [SANPO] | 0.188 | 0.305 | low |
| Vibrations played per minute | 28.2 | 22.3 | low |
| Left<->right flips within 2 s per minute | 0.00 | 0.00 | low |
| Conflicting vibration pairs per minute | 0.62 | 0.42 | low |
| Decision cue changes per minute | 60.3 | 45.1 | low |
| False alarm rate (GT-clear frames with warning/stop) [SANPO] | 0.584 | 0.671 | low |
| False stop rate (GT-clear frames with stop) [SANPO] | 0.002 | 0.006 | low |
| Obstacle recall (closing events alarmed) [SANPO] | 0.964 | 0.964 | high |
| Closing obstacle events (GT) | 28 | 28 |  |
| Alarm latency from event start (s) | 0.000 | 0.017 | low |
| App logic per frame, median (ms, JVM x86) | 53.5 | 39.1 | low |
| App logic per frame, p90 (ms, JVM x86) | 77.2 | 67.3 | low |
| Heavy surface model runs per second | 8.19 | 10.65 | low |

**Group: sanpo** (18 clips)

| Metric | 0.13.0 | 0.13.0 + latency | better |
|---|---|---|---|
| Path jitter at 3 m, median (m) | 0.039 | 0.008 | low |
| Path jitter at 3 m, p90 (m) | 0.152 | 0.154 | low |
| Frame-to-frame path variation, std (m) | 0.173 | 0.190 | low |
| Drawn path vs evidence path at 3 m (lag, m) | 0.085 | 0.063 | low |
| Path continuity (pairs < 0.15 m) | 0.718 | 0.433 | high |
| Path availability (frames with a drawn path) | 0.799 | 0.400 | high |
| Tracking failure rate | 0.202 | 0.600 | low |
| Path drops per minute | 6.11 | 16.11 | low |
| Error vs walked path, 1-4 m, median (m) [SANPO] | 0.188 | 0.305 | low |
| Vibrations played per minute | 31.4 | 23.3 | low |
| Left<->right flips within 2 s per minute | 0.00 | 0.00 | low |
| Conflicting vibration pairs per minute | 0.83 | 0.56 | low |
| Decision cue changes per minute | 70.8 | 53.9 | low |
| False alarm rate (GT-clear frames with warning/stop) [SANPO] | 0.584 | 0.671 | low |
| False stop rate (GT-clear frames with stop) [SANPO] | 0.002 | 0.006 | low |
| Obstacle recall (closing events alarmed) [SANPO] | 0.964 | 0.964 | high |
| Closing obstacle events (GT) | 28 | 28 |  |
| Alarm latency from event start (s) | 0.000 | 0.017 | low |
| App logic per frame, median (ms, JVM x86) | 54.1 | 39.7 | low |
| App logic per frame, p90 (ms, JVM x86) | 75.4 | 66.0 | low |
| Heavy surface model runs per second | 8.19 | 11.05 | low |

**Group: sanpo_hq** (7 clips)

| Metric | 0.13.0 | 0.13.0 + latency | better |
|---|---|---|---|
| Path jitter at 3 m, median (m) | 0.035 | 0.007 | low |
| Path jitter at 3 m, p90 (m) | 0.135 | 0.146 | low |
| Frame-to-frame path variation, std (m) | 0.177 | 0.153 | low |
| Drawn path vs evidence path at 3 m (lag, m) | 0.057 | 0.034 | low |
| Path continuity (pairs < 0.15 m) | 0.732 | 0.389 | high |
| Path availability (frames with a drawn path) | 0.894 | 0.543 | high |
| Tracking failure rate | 0.106 | 0.457 | low |
| Path drops per minute | 5.00 | 15.71 | low |
| Error vs walked path, 1-4 m, median (m) [SANPO] | 0.169 | 0.175 | low |
| Vibrations played per minute | 26.4 | 22.1 | low |
| Left<->right flips within 2 s per minute | 0.00 | 0.00 | low |
| Conflicting vibration pairs per minute | 0.00 | 0.71 | low |
| Decision cue changes per minute | 80.7 | 75.0 | low |
| False alarm rate (GT-clear frames with warning/stop) [SANPO] | 0.509 | 0.507 | low |
| False stop rate (GT-clear frames with stop) [SANPO] | 0.000 | 0.000 | low |
| Obstacle recall (closing events alarmed) [SANPO] | 0.500 | 1.000 | high |
| Closing obstacle events (GT) | 2 | 2 |  |
| Alarm latency from event start (s) | 0.000 | 0.000 | low |
| App logic per frame, median (ms, JVM x86) | 54.2 | 38.1 | low |
| App logic per frame, p90 (ms, JVM x86) | 74.6 | 62.0 | low |
| Heavy surface model runs per second | 7.19 | 10.26 | low |

**Group: track** (5 clips)

| Metric | 0.13.0 | 0.13.0 + latency | better |
|---|---|---|---|
| Path jitter at 3 m, median (m) | 0.007 | 0.077 | low |
| Path jitter at 3 m, p90 (m) | 0.184 | 0.148 | low |
| Frame-to-frame path variation, std (m) | 0.336 | 0.169 | low |
| Drawn path vs evidence path at 3 m (lag, m) | 0.045 | 0.071 | low |
| Path continuity (pairs < 0.15 m) | 0.602 | 0.459 | high |
| Path availability (frames with a drawn path) | 0.692 | 0.372 | high |
| Tracking failure rate | 0.309 | 0.628 | low |
| Path drops per minute | 9.50 | 8.04 | low |
| Vibrations played per minute | 14.0 | 15.7 | low |
| Left<->right flips within 2 s per minute | 0.00 | 0.00 | low |
| Conflicting vibration pairs per minute | 0.00 | 0.00 | low |
| Decision cue changes per minute | 6.9 | 9.1 | low |
| App logic per frame, median (ms, JVM x86) | 53.6 | 39.0 | low |
| App logic per frame, p90 (ms, JVM x86) | 70.4 | 75.4 | low |
| Heavy surface model runs per second | 8.69 | 9.76 | low |

**Group: owner** (1 clips)

| Metric | 0.13.0 | 0.13.0 + latency | better |
|---|---|---|---|
| Path jitter at 3 m, median (m) | 0.009 | 0.006 | low |
| Path jitter at 3 m, p90 (m) | 0.083 | 0.105 | low |
| Frame-to-frame path variation, std (m) | 0.127 | 0.071 | low |
| Drawn path vs evidence path at 3 m (lag, m) | 0.057 | 0.051 | low |
| Path continuity (pairs < 0.15 m) | 0.944 | 0.890 | high |
| Path availability (frames with a drawn path) | 0.904 | 0.864 | high |
| Tracking failure rate | 0.096 | 0.136 | low |
| Path drops per minute | 4.55 | 9.09 | low |
| Vibrations played per minute | 40.9 | 36.4 | low |
| Left<->right flips within 2 s per minute | 0.00 | 0.00 | low |
| Conflicting vibration pairs per minute | 0.00 | 0.00 | low |
| Decision cue changes per minute | 136.4 | 68.2 | low |
| App logic per frame, median (ms, JVM x86) | 42.2 | 30.3 | low |
| App logic per frame, p90 (ms, JVM x86) | 143.5 | 49.4 | low |
| Heavy surface model runs per second | 5.61 | 7.88 | low |


## 6. أهم ما يظهر في خط الأساس

- **الإنذارات الكاذبة:** الإنذار أو التوقف موجود في 0.584 من الإطارات الخالية في SANPO. السبب أن العوائق تُفحص في ممر غير المسار المرسوم.
- **الاستدعاء:** الأحداث المقتربة المنبَّه إليها 0.964 (27/28).
- **أثر تأخر الهاتف على المسار:** توفر المسار ينهار من 0.781 إلى 0.413، والخطأ مقابل المسار المقطوع يرتفع من 0.188 إلى 0.305 م.

## 7. إعادة الإنتاج

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
