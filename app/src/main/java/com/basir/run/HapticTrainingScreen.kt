package com.basir.run

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.basir.run.engine.Phrase
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import run.basir.engine.HapticLanguage
import run.basir.engine.HapticPattern
import run.basir.engine.Level
import run.basir.engine.Side

/** One meaning of the vibration language: its title, spoken explanation and pattern(s). */
private class Lesson(val title: String, val answer: String, val phrase: Phrase, val patterns: (HapticLanguage) -> List<HapticPattern>)

private val LESSONS = listOf(
    Lesson("نبضة قصيرة: صحّح يساراً", Phrase.AnswerLeft.ar, Phrase.PatternLeft) { listOf(it.correct(Side.LEFT, Level.LOW)) },
    Lesson("نبضتان قصيرتان: صحّح يميناً", Phrase.AnswerRight.ar, Phrase.PatternRight) { listOf(it.correct(Side.RIGHT, Level.LOW)) },
    Lesson("الشدة تزيد مع الابتعاد", "", Phrase.PatternStrength) { l -> Level.values().map { l.correct(Side.LEFT, it) } },
    Lesson("نبضة طويلة هادئة: منعطف يسار", Phrase.AnswerBendLeft.ar, Phrase.PatternBendLeft) { listOf(it.curve(Side.LEFT, Level.MEDIUM)) },
    Lesson("نبضتان طويلتان هادئتان: منعطف يمين", Phrase.AnswerBendRight.ar, Phrase.PatternBendRight) { listOf(it.curve(Side.RIGHT, Level.MEDIUM)) },
    Lesson("ثلاث نبضات سريعة: عائق أو انتباه", Phrase.AnswerAttention.ar, Phrase.PatternAttention) { listOf(it.obstacle()) },
    Lesson("طويلة ثم نبضتان: توقف فوراً", Phrase.AnswerStop.ar, Phrase.PatternStop) { listOf(it.stop()) },
    Lesson("نبضة التأكيد", "", Phrase.PatternConfirm) { listOf(HapticEngine.CONFIRM) },
    Lesson("نقرة خفيفة: أنت على المسار", "", Phrase.PatternOnPath) { listOf(it.onPath()) },
)

/** The meanings the quiz asks about: every pattern that guides or stops the runner. */
/** The pulse length and strength a runner chooses by ear and feel, in steps with spoken names. */
private class Step(val value: Float, val name: String, val phrase: Phrase)

private val DURATIONS = listOf(
    Step(0.7f, "قصيرة", Phrase.DurationShort),
    Step(1f, "متوسطة", Phrase.DurationMedium),
    Step(1.4f, "طويلة", Phrase.DurationLong),
    Step(2f, "طويلة جداً", Phrase.DurationVeryLong),
)
private val STRENGTHS = listOf(
    Step(0.45f, "خفيف", Phrase.StrengthLight),
    Step(0.7f, "متوسط", Phrase.StrengthMedium),
    Step(1f, "قوي", Phrase.StrengthStrong),
)

private fun List<Step>.nearest(value: Float) = indices.minBy { abs(this[it].value - value) }

private val QUIZ = LESSONS.filter { it.answer.isNotEmpty() }
private const val QUESTIONS = 6
private const val PASS = 5

/**
 * Teaches the vibration language of the Virtual Path Engine, lets the user tune strength and
 * length, then checks with a short recognition quiz that the patterns can be told apart without
 * looking. Running is allowed only after the quiz has been passed once (or on a phone that
 * cannot vibrate, where the app relies on voice). Every control: tap to hear it, hold to use it.
 */
@Composable
fun HapticTrainingScreen(prefs: Prefs, voice: Voice, haptics: HapticEngine, doneLabel: String, onDone: () -> Unit, onBack: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    var strength by remember { mutableIntStateOf(STRENGTHS.nearest(prefs.hapticStrength)) }
    var duration by remember { mutableIntStateOf(DURATIONS.nearest(prefs.hapticDuration)) }
    val playing = remember { mutableStateOf<Job?>(null) }
    var question by remember { mutableIntStateOf(-1) } // -1: not started
    var correct by remember { mutableIntStateOf(0) }
    var asked by remember { mutableStateOf<Lesson?>(null) }
    var options by remember { mutableStateOf<List<Lesson>>(emptyList()) }
    var result by remember { mutableStateOf<String?>(null) }

    fun play(patterns: List<HapticPattern>) = scope.launch {
        for (p in patterns) {
            if (!haptics.play(p)) {
                voice.say(Phrase.HapticsUnavailable, interrupt = true)
                return@launch
            }
            delay(p.durationMs + 700)
        }
    }

    fun teach(lessons: List<Lesson>) {
        playing.value?.cancel()
        playing.value = scope.launch {
            for (l in lessons) {
                voice.say(l.phrase, interrupt = true)
                delay(if (l.phrase == Phrase.PatternStrength) 5000 else 3200)
                play(l.patterns(haptics.language)).join()
                delay(600)
            }
        }
    }

    // One step longer/shorter or stronger/weaker: say where it is now, then one pulse and two
    // pulses, the pair that has to be easy to tell apart.
    fun tune(durationStep: Int = 0, strengthStep: Int = 0) {
        duration = (duration + durationStep).coerceIn(DURATIONS.indices)
        strength = (strength + strengthStep).coerceIn(STRENGTHS.indices)
        prefs.hapticDuration = DURATIONS[duration].value
        prefs.hapticStrength = STRENGTHS[strength].value
        playing.value?.cancel()
        playing.value = scope.launch {
            voice.say(if (durationStep != 0) DURATIONS[duration].phrase else STRENGTHS[strength].phrase, interrupt = true)
            delay(2000)
            play(listOf(haptics.language.correct(Side.LEFT, Level.MEDIUM), haptics.language.correct(Side.RIGHT, Level.MEDIUM))).join()
        }
    }

    fun ask(n: Int) {
        val l = QUIZ.random()
        asked = l
        options = (QUIZ.filter { it !== l }.shuffled().take(3) + l).shuffled()
        question = n
        playing.value?.cancel()
        playing.value = play(l.patterns(haptics.language))
    }

    fun answer(choice: Lesson) {
        val right = choice === asked
        if (right) correct++
        voice.say(if (right) Phrase.QuizCorrect else Phrase.QuizWrong, interrupt = true)
        if (!right) asked?.let { voice.say(it.phrase) }
        if (question + 1 < QUESTIONS) {
            scope.launch {
                delay(if (right) 1200 else 4500)
                ask(question + 1)
            }
        } else {
            val passed = correct >= PASS
            if (passed) prefs.hapticQuizPassed = true
            result = String.format(Locale.US, "%d من %d صحيحة. %s", correct, QUESTIONS, if (passed) "اجتزت الاختبار" else "لم تجتز الاختبار بعد")
            voice.say(if (passed) Phrase.QuizPassed else Phrase.QuizFailed, interrupt = false)
            // Missed patterns are most often pulses too short to tell apart: lengthen by one step.
            if (!passed && duration < DURATIONS.lastIndex) {
                duration++
                prefs.hapticDuration = DURATIONS[duration].value
                voice.say(Phrase.QuizMadeLonger)
            }
            question = -1
            asked = null
        }
    }

    LaunchedEffect(Unit) {
        voice.say(Phrase.TrainingIntro, interrupt = true)
        voice.say(Phrase.TuneHint)
    }

    BasirBackground {
        Column(
            modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader("لغة الاهتزاز", onBack)
            Text(
                "اليسار نبضة واحدة واليمين نبضتان في كل الأنماط. القصيرة تعني صحّح الآن، والطويلة الهادئة تعني استعد لمنعطف. التوقف يسبق أي نمط آخر.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkMuted,
            )
            if (!haptics.available) {
                Text("هذا الهاتف لا يدعم الاهتزاز. سيعتمد التطبيق على الصوت.", style = MaterialTheme.typography.titleMedium, color = Danger)
            }

            Card {
                Text("مدة النبضة: ${DURATIONS[duration].name}", style = MaterialTheme.typography.titleMedium, color = Ink)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SpeakButton("نبضة أقصر", { tune(durationStep = -1) }, spoken = Phrase.ButtonShorter.ar, hint = null, sayHowToUse = false, modifier = Modifier.weight(1f))
                    SpeakButton("نبضة أطول", { tune(durationStep = 1) }, spoken = Phrase.ButtonLonger.ar, hint = null, sayHowToUse = false, modifier = Modifier.weight(1f))
                }
                Text("قوة الاهتزاز: ${STRENGTHS[strength].name}", style = MaterialTheme.typography.titleMedium, color = Ink)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SpeakButton("اهتزاز أضعف", { tune(strengthStep = -1) }, spoken = Phrase.ButtonWeaker.ar, hint = null, sayHowToUse = false, modifier = Modifier.weight(1f))
                    SpeakButton("اهتزاز أقوى", { tune(strengthStep = 1) }, spoken = Phrase.ButtonStronger.ar, hint = null, sayHowToUse = false, modifier = Modifier.weight(1f))
                }
            }
            if (question < 0) {
                LESSONS.forEach { l ->
                    SpeakButton(l.title, { teach(listOf(l)) }, spoken = l.phrase.ar, hint = "اضغط مطولاً للتجربة", sayHowToUse = false, modifier = Modifier.fillMaxWidth())
                }
                SpeakButton("كل الأنماط بالترتيب", { teach(LESSONS) }, spoken = Phrase.ButtonAllPatterns.ar, tone = ButtonTone.Accent, sayHowToUse = false, modifier = Modifier.fillMaxWidth())


                result?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = Tactile, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (haptics.available) {
                    SpeakButton(
                        if (prefs.hapticQuizPassed) "أعد اختبار التعرّف" else "اختبار التعرّف على الأنماط",
                        {
                            playing.value?.cancel()
                            correct = 0
                            result = null
                            voice.say(Phrase.QuizIntro, interrupt = true)
                            scope.launch { delay(3500); ask(0) }
                        },
                        spoken = Phrase.ButtonQuiz.ar,
                        sayHowToUse = false,
                        tone = ButtonTone.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SpeakButton(
                    doneLabel,
                    {
                        playing.value?.cancel()
                        prefs.hapticTrained = true
                        onDone()
                    },
                    tone = if (prefs.hapticQuizPassed || !haptics.available) ButtonTone.Primary else ButtonTone.Neutral,
                    sayHowToUse = false,
                    height = 76.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!prefs.hapticQuizPassed && haptics.available) {
                    Text("لن يبدأ الجري قبل اجتياز اختبار التعرّف.", style = MaterialTheme.typography.labelMedium, color = InkMuted)
                }
            } else {
                Card {
                    Text(
                        String.format(Locale.US, "السؤال %d من %d: ما معنى هذا الاهتزاز؟", question + 1, QUESTIONS),
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                SpeakButton(Phrase.ButtonReplayPattern.ar, { asked?.let { playing.value = play(it.patterns(haptics.language)) } }, tone = ButtonTone.Accent, sayHowToUse = false, modifier = Modifier.fillMaxWidth())
                options.forEach { o -> SpeakButton(o.answer, { answer(o) }, hint = "اضغط مطولاً للاختيار", sayHowToUse = false, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}
