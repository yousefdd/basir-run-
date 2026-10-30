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
    HeadLevel("head_level", "انتبه، عائق على مستوى الرأس أمامك. توقف وتحقق"),
    DropAhead("drop_ahead", "انتبه، قد يوجد درج نازل أو حفرة أمامك. توقف وتحقق"),
    PathUnclear("path_unclear", "المسار غير واضح. خفف سرعتك"),
    EdgeOnly("edge_only", "أرى حداً واحداً فقط للمسار. خفف سرعتك"),
    EdgeNearStop("edge_near_stop", "اقتربت من حد المسار ولا أرى الجهة الأخرى. توقف وتحقق"),
    CannotVerifyStop("cannot_verify_stop", "توقف، لم أتمكن من التحقق من المسار"),
    AngleStop("angle_stop", "تغيرت زاوية الهاتف. توقف وأعد المعايرة"),
    LatencySlow("latency_slow", "النظام بطيء الآن. خفف سرعتك"),
    LatencyStop("latency_stop", "النظام بطيء جداً. توقف في مكانك"),

    // Session
    CheckStart("check_start", "قف في منتصف المسار، والكاميرا إلى الأمام. أفحص المسار الآن"),
    GuidanceStarted("guidance_started", "المسار واضح. بدأ التوجيه"),
    NotReady("not_ready", "لم أتمكن من التحقق من المسار. لا تبدأ الجري"),
    SessionPaused("session_paused", "تم إيقاف التوجيه مؤقتاً. توقف في مكانك"),
    SessionResumed("session_resumed", "استؤنف التوجيه"),
    SessionEnded("session_ended", "انتهت الجلسة"),
    HoldToActivate("hold_to_activate", "اضغط مطولاً للتشغيل"),
    HapticsUnavailable("haptics_unavailable", "الاهتزاز غير متاح في هذا الهاتف. سأعتمد على الصوت"),

    // First use
    OnboardingIntro("onboarding_intro", "مرحباً بك في بصير ران. قبل أول جري، تأكد من أربعة أمور"),
    OnboardingLight("onboarding_light", "أولاً: إضاءة كافية لرؤية المسار والعوائق"),
    OnboardingMount("onboarding_mount", "ثانياً: ثبّت الهاتف بإحكام على صدرك، والكاميرا الخلفية إلى الأمام"),
    OnboardingSafe("onboarding_safe", "ثالثاً: أجرِ التجارب الأولى في مكان آمن تعرفه، ومع مرافق مؤهل"),
    OnboardingCamera("onboarding_camera", "رابعاً: سيتحقق التطبيق قبل كل جري أن الكاميرا ترى المسار وأن التحليل سريع بما يكفي"),
    OnboardingStop("onboarding_stop", "إذا طلب منك التوقف، فتوقف فوراً"),
    TrainingIntro("training_intro", "تعرّف على أنماط الاهتزاز قبل الجري. اليسار نبضة واحدة، واليمين نبضتان. سأشرح كل نمط ثم أشغّله"),
    PatternLeft("pattern_left", "نبضة قصيرة واحدة: صحّح قليلاً إلى اليسار"),
    PatternRight("pattern_right", "نبضتان قصيرتان: صحّح قليلاً إلى اليمين"),
    PatternStrength("pattern_strength", "كلما زاد ابتعادك عن المسار صارت النبضات أقوى. هذه خفيفة، ثم متوسطة، ثم قوية"),
    PatternBendLeft("pattern_bend_left", "نبضة طويلة هادئة: منعطف إلى اليسار قادم"),
    PatternBendRight("pattern_bend_right", "نبضتان طويلتان هادئتان: منعطف إلى اليمين قادم"),
    PatternAttention("pattern_attention", "ثلاث نبضات سريعة قوية: عائق أو أمر يحتاج انتباهك فوراً"),
    PatternStop("pattern_stop", "اهتزاز طويل تتبعه نبضتان: توقف عن الجري فوراً"),
    PatternConfirm("pattern_confirm", "نبضة تأكيد خفيفة: اكتمل الفحص أو بدأت الجلسة"),
    PatternOnPath("pattern_on_path", "نقرة خفيفة جداً كل بضع ثوانٍ: أنت على المسار"),
    QuizIntro("quiz_intro", "اختبار قصير: سأشغّل نمطاً، ثم اختر معناه بالضغط المطوّل"),
    QuizCorrect("quiz_correct", "صحيح"),
    QuizWrong("quiz_wrong", "غير صحيح"),
    QuizPassed("quiz_passed", "أحسنت، اجتزت اختبار الاهتزاز"),
    QuizFailed("quiz_failed", "لم تجتز الاختبار بعد. تدرّب على الأنماط ثم أعد المحاولة"),

    // Emergency
    SosStarted("sos_started", "بدأ إجراء الطوارئ"),
    SosTest("sos_test", "وضع الاختبار. لن يُرسل أي شيء ولن يُجرى اتصال"),
    SosLocationReady("sos_location_ready", "تم تحديد موقعك"),
    SosNoLocation("sos_no_location", "لم أتمكن من تحديد موقعك. ستُرسل الرسالة بدون موقع"),
    SosNoNetwork("sos_no_network", "لا يوجد اتصال بالإنترنت. الرسالة النصية والاتصال يعتمدان على شبكة الجوال"),
    SosMessageOpened("sos_message_opened", "فُتح تطبيق الرسائل. اضغط إرسال لتأكيد الرسالة"),
    SosDialOpened("sos_dial_opened", "فُتحت شاشة الاتصال. اضغط زر الاتصال"),
    SosShareOpened("sos_share_opened", "فُتحت مشاركة الموقع. اختر التطبيق ثم أرسل"),
    SosNoContact("sos_no_contact", "لا توجد جهة اتصال للطوارئ. أضفها من الإعدادات"),
    SosNoApp("sos_no_app", "لا يوجد تطبيق مناسب على هذا الهاتف لتنفيذ هذا الإجراء"),
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
            Cue.HeadLevelStop -> HeadLevel
            Cue.DropStop -> DropAhead
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
