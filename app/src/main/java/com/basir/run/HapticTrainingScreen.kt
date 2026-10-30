package com.basir.run

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.basir.run.engine.Phrase
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import run.basir.engine.HapticLanguage
import run.basir.engine.HapticPattern
import run.basir.engine.Level
import run.basir.engine.Side

/** One meaning of the vibration language: its title, spoken explanation and pattern(s). */
private class Lesson(val title: String, val answer: String, val phrase: Phrase, val patterns: (HapticLanguage) -> List<HapticPattern>)

private val LESSONS = listOf(
    Lesson("نبضة قصيرة: صحّح يساراً", "تصحيح إلى اليسار", Phrase.PatternLeft) { listOf(it.correct(Side.LEFT, Level.LOW)) },
    Lesson("نبضتان قصيرتان: صحّح يميناً", "تصحيح إلى اليمين", Phrase.PatternRight) { listOf(it.correct(Side.RIGHT, Level.LOW)) },
    Lesson("الشدة تزيد مع الابتعاد", "", Phrase.PatternStrength) { l -> Level.values().map { l.correct(Side.LEFT, it) } },
    Lesson("نبضة طويلة هادئة: منعطف يسار", "منعطف يسار قادم", Phrase.PatternBendLeft) { listOf(it.curve(Side.LEFT, Level.MEDIUM)) },
    Lesson("نبضتان طويلتان هادئتان: منعطف يمين", "منعطف يمين قادم", Phrase.PatternBendRight) { listOf(it.curve(Side.RIGHT, Level.MEDIUM)) },
    Lesson("ثلاث نبضات سريعة: عائق أو انتباه", "عائق أو انتباه فوري", Phrase.PatternAttention) { listOf(it.obstacle()) },
    Lesson("طويلة ثم نبضتان: توقف فوراً", "توقف فوراً", Phrase.PatternStop) { listOf(it.stop()) },
    Lesson("نبضة التأكيد", "", Phrase.PatternConfirm) { listOf(HapticEngine.CONFIRM) },
    Lesson("نقرة خفيفة: أنت على المسار", "", Phrase.PatternOnPath) { listOf(it.onPath()) },
)

/** The meanings the quiz asks about: every pattern that guides or stops the runner. */
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
    var strength by remember { mutableFloatStateOf(prefs.hapticStrength) }
    var duration by remember { mutableFloatStateOf(prefs.hapticDuration) }
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
            question = -1
            asked = null
        }
    }

    LaunchedEffect(Unit) { voice.say(Phrase.TrainingIntro, interrupt = true) }

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

            if (question < 0) {
                LESSONS.forEach { l ->
                    SpeakButton(l.title, { teach(listOf(l)) }, spoken = l.phrase.ar, hint = "اضغط مطولاً للتجربة", modifier = Modifier.fillMaxWidth())
                }
                SpeakButton("كل الأنماط بالترتيب", { teach(LESSONS) }, spoken = "تشغيل كل الأنماط بالترتيب مع شرحها", tone = ButtonTone.Accent, modifier = Modifier.fillMaxWidth())

                Card {
                    SectionTitle("قوة الاهتزاز")
                    Slider(
                        value = strength,
                        onValueChange = { strength = it },
                        onValueChangeFinished = { prefs.hapticStrength = strength; haptics.play(haptics.language.correct(Side.LEFT, Level.MEDIUM)) },
                        valueRange = 0.3f..1f,
                        colors = SliderDefaults.colors(thumbColor = Tactile, activeTrackColor = Teal, inactiveTrackColor = NavyRaised),
                        modifier = Modifier.semantics {
                            contentDescription = "قوة الاهتزاز"
                            stateDescription = String.format(Locale.US, "%.0f%%", strength * 100)
                        },
                    )
                    SectionTitle("مدة النبضة")
                    Slider(
                        value = duration,
                        onValueChange = { duration = it },
                        onValueChangeFinished = { prefs.hapticDuration = duration; haptics.play(haptics.language.correct(Side.RIGHT, Level.MEDIUM)) },
                        valueRange = 0.6f..1.6f,
                        colors = SliderDefaults.colors(thumbColor = Tactile, activeTrackColor = Teal, inactiveTrackColor = NavyRaised),
                        modifier = Modifier.semantics {
                            contentDescription = "مدة النبضة"
                            stateDescription = String.format(Locale.US, "×%.1f", duration)
                        },
                    )
                }

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
                        spoken = "اختبار من $QUESTIONS أسئلة. يلزم $PASS إجابات صحيحة قبل الجري",
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
                SpeakButton("أعد تشغيل النمط", { asked?.let { playing.value = play(it.patterns(haptics.language)) } }, tone = ButtonTone.Accent, modifier = Modifier.fillMaxWidth())
                options.forEach { o -> SpeakButton(o.answer, { answer(o) }, hint = "اضغط مطولاً للاختيار", modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}
