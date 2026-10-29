# مقاطع اختبار من تسجيلات حقيقية

ضع كل مقطع في مجلد مستقل هنا، فيه صور PPM بالترتيب الزمني وملف `expected.properties`.
التفاصيل في `app/src/test/java/com/basir/run/vision/RecordedFramesTest.kt`.

مثال `expected.properties` لمقطع مضمار مستقيم والعدّاء في المنتصف:

```
mode=track
status=Tracked
side=centre
curve=straight
```

استخراج الصور من فيديو:

```bash
ffmpeg -i clip.mp4 -vf "fps=5,scale=480:-1" track_straight/%04d.ppm
```

صوّر والهاتف مثبت على الحامل كما في الجري، والشاشة عمودية.
