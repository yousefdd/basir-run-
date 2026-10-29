package com.basir.run.engine

/**
 * Every fixed sentence the app speaks. The id is also the file name of a recorded voice:
 * a file app/src/main/assets/voice/<id>.mp3 (or .ogg, .wav, .m4a) is played instead of the
 * phone's text to speech. Other languages can be added as further text columns.
 */
enum class Phrase(val id: String, val ar: String) {
    // Guidance
    Centered("centered", "أنت تسير في منتصف المسار"),
    CorrectRight("correct_right", "انحرف قليلاً إلى اليمين"),
    CorrectLeft("correct_left", "انحرف قليلاً إلى اليسار"),
    NearEdgeRight("near_edge_right", "اقتربت من حدود المسار، اتجه إلى اليمين"),
    NearEdgeLeft("near_edge_left", "اقتربت من حدود المسار، اتجه إلى اليسار"),
    AtEdgeStop("at_edge_stop", "أنت على حد المسار. توقف وتحقق"),
    CurveRight("curve_right", "المسار ينحني تدريجياً إلى اليمين"),
    CurveLeft("curve_left", "المسار ينحني تدريجياً إلى اليسار"),

    // Warnings and stops
    Obstacle("obstacle", "يوجد عائق محتمل أمامك. خفف سرعتك وتوقف للتحقق"),
    ObstacleNear("obstacle_near", "انتبه، يوجد جسم قريب من مسارك. خفف سرعتك"),
    PathUnclear("path_unclear", "المسار غير واضح. خفف سرعتك"),
    EdgeOnly("edge_only", "أرى حداً واحداً فقط للمسار. خفف سرعتك"),
    EdgeNearStop("edge_near_stop", "اقتربت من حد المسار ولا أرى الجهة الأخرى. توقف وتحقق"),
    CannotVerifyStop("cannot_verify_stop", "توقف، لم أتمكن من التحقق من المسار"),
    AngleStop("angle_stop", "تغيرت زاوية الهاتف. توقف وأعد المعايرة"),
    LatencySlow("latency_slow", "النظام بطيء الآن. خفف سرعتك"),
    LatencyStop("latency_stop", "النظام بطيء جداً. توقف في مكانك"),

    // Session
    CalibrationStart("calibration_start", "قف في منتصف المسار، ووجّه الكاميرا إلى الأمام"),
    CalibrationDone("calibration_done", "اكتملت المعايرة. النظام جاهز"),
    NotReady("not_ready", "النظام غير جاهز للجري"),
    UnknownPathRefused("unknown_path_refused", "لم أتمكن من التحقق من وضوح المسار. لا تبدأ الجري بهذا الوضع"),
    SessionStart("session_start", "بدأ التوجيه. ابدأ بخطوات بطيئة"),
    SessionPaused("session_paused", "تم إيقاف التوجيه مؤقتاً. توقف في مكانك"),
    SessionResumed("session_resumed", "استؤنف التوجيه"),
    SessionEnded("session_ended", "انتهت الجلسة"),
    HoldToStop("hold_to_stop", "اضغط مطولاً لإنهاء الجلسة"),
    HoldForSos("hold_for_sos", "اضغط مطولاً لتفعيل الطوارئ"),
    HapticsUnavailable("haptics_unavailable", "الاهتزاز غير متاح في هذا الهاتف. سأعتمد على الصوت"),
    TrainingIntro("training_intro", "تعرّف على أنماط الاهتزاز قبل الجري. سأشغّل كل نمط وأشرح معناه"),
    PatternCorrection("pattern_correction", "نبضة قصيرة واحدة: تصحيح بسيط للاتجاه"),
    PatternAttention("pattern_attention", "نبضتان قصيرتان: انتبه، منحنى أو اقتراب من حد المسار"),
    PatternHazard("pattern_hazard", "ثلاث نبضات سريعة: خطر محتمل أمامك"),
    PatternStop("pattern_stop", "اهتزاز طويل: توقف فوراً"),

    // Emergency
    SosActivated("sos_activated", "تم تفعيل الطوارئ"),
    SosTest("sos_test", "هذا اختبار للطوارئ. لن يُرسل أي شيء"),
    SosMessageOpened("sos_message_opened", "فُتح تطبيق الرسائل. اضغط إرسال لتأكيد الرسالة"),
    SosDialOpened("sos_dial_opened", "فُتحت شاشة الاتصال. اضغط زر الاتصال"),
    SosNoLocation("sos_no_location", "لم أتمكن من تحديد موقعك. ستُرسل الرسالة بدون موقع"),
    SosNoContact("sos_no_contact", "لا توجد جهة اتصال للطوارئ. أضفها من شاشة الطوارئ"),
    ;

    companion object {
        fun forCue(d: Decision): Phrase? = when (d.cue) {
            Cue.Centered -> Centered
            Cue.Correct -> if (d.moveTo == com.basir.run.vision.Side.Right) CorrectRight else CorrectLeft
            Cue.NearEdge -> if (d.moveTo == com.basir.run.vision.Side.Right) NearEdgeRight else NearEdgeLeft
            Cue.AtEdgeStop -> AtEdgeStop
            Cue.CurveAhead -> curve(d.curve)
            Cue.Obstacle -> Obstacle
            Cue.ObstacleNear -> ObstacleNear
            Cue.PathUnclear -> PathUnclear
            Cue.EdgeOnlyCaution -> EdgeOnly
            Cue.EdgeNearStop -> EdgeNearStop
            // The same plain sentence whenever the path cannot be verified, whatever the cause.
            Cue.LostStop, Cue.CameraStop, Cue.VisibilityStop -> CannotVerifyStop
            Cue.AngleStop -> AngleStop
            Cue.LatencySlow -> LatencySlow
            Cue.LatencyStop -> LatencyStop
            Cue.None, Cue.Paused -> null
        }

        fun curve(side: com.basir.run.vision.Side?): Phrase? = when (side) {
            com.basir.run.vision.Side.Right -> CurveRight
            com.basir.run.vision.Side.Left -> CurveLeft
            null -> null
        }
    }
}
