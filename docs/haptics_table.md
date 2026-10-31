# Basir-Run vibration table

Generated from `engine/.../Haptics.kt` by `BasirRun_Research/tools/kt/HapticTable.kt`. Priority: higher wins; a lower one never cuts a higher one. Strength 0-255 (phones without strength control play every pulse at full strength and scale its length instead).

| Priority | Pattern | Meaning (Arabic) | Meaning | Pulses | Repeat |
|---|---|---|---|---|---|
| 5 | `stop` | توقّف فوراً | Stop now | 700 ms @255 · pause 200 · 200 ms @255 · pause 150 · 200 ms @255 | once, then at most 2 reminders 5.0 s apart |
| 5 | `stop_turn_left` | توقّف ثم انعطف يساراً | Stop, then turn left | 700 ms @255 · pause 200 · 200 ms @255 · pause 150 · 200 ms @255 · pause 600 · 400 ms @160 | once, then at most 2 reminders 5.0 s apart |
| 5 | `stop_turn_right` | توقّف ثم انعطف يميناً | Stop, then turn right | 700 ms @255 · pause 200 · 200 ms @255 · pause 150 · 200 ms @255 · pause 600 · 400 ms @160 · pause 220 · 400 ms @160 | once, then at most 2 reminders 5.0 s apart |
| 4 | `obstacle` | عائق محتمل أمامك | Possible obstacle ahead | 120 ms @255 · pause 80 · 120 ms @255 · pause 80 · 120 ms @255 | once, then at most 2 reminders 2.5 s apart |
| 4 | `obstacle_left` | عائق أمامك، تجاوزه من اليسار | Obstacle ahead, pass on the left | 120 ms @255 · pause 80 · 120 ms @255 · pause 80 · 120 ms @255 · pause 400 · 150 ms @170 | once, then at most 2 reminders 2.5 s apart |
| 4 | `obstacle_right` | عائق أمامك، تجاوزه من اليمين | Obstacle ahead, pass on the right | 120 ms @255 · pause 80 · 120 ms @255 · pause 80 · 120 ms @255 · pause 400 · 150 ms @170 · pause 150 · 150 ms @170 | once, then at most 2 reminders 2.5 s apart |
| 3 | `correct_left_low` | تصحيح بسيط إلى اليسار | Small correction left | 120 ms @90 | every 1.1 s while needed |
| 3 | `correct_right_low` | تصحيح بسيط إلى اليمين | Small correction right | 120 ms @90 · pause 150 · 120 ms @90 | every 1.1 s while needed |
| 3 | `correct_left_medium` | تصحيح متوسط إلى اليسار | Medium correction left | 170 ms @170 | every 0.8 s while needed |
| 3 | `correct_right_medium` | تصحيح متوسط إلى اليمين | Medium correction right | 170 ms @170 · pause 150 · 170 ms @170 | every 0.8 s while needed |
| 3 | `correct_left_high` | تصحيح قوي إلى اليسار | Strong correction left | 240 ms @255 | every 0.65 s while needed |
| 3 | `correct_right_high` | تصحيح قوي إلى اليمين | Strong correction right | 240 ms @255 · pause 150 · 240 ms @255 | every 0.65 s while needed |
| 3 | `caution` | الطريق غير واضح لحظيًا: خفّف السرعة وانتبه | Path briefly unclear: slow down | 300 ms @80 · pause 300 · 300 ms @80 | every 2.5 s while the path is unclear |
| 2 | `curve_left_low` | انحناء تدريجي إلى اليسار قادم | Gentle bend left ahead | 400 ms @70 | on entering the bend, every 1.6 s, quiet after 2 s inside |
| 2 | `curve_right_high` | منعطف حاد إلى اليمين قادم | Sharp bend right ahead | 400 ms @160 · pause 220 · 400 ms @160 | on entering the bend, every 1.6 s, quiet after 2 s inside |
| 1 | `back_on_path` | عدت إلى المسار | Back on the path | 40 ms @80 · pause 60 · 40 ms @80 · pause 60 · 40 ms @80 | once after a correction |
