package com.basir.run

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.basir.run.belt.BeltState
import com.basir.run.engine.Phrase
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import run.basir.engine.BeltPattern
import run.basir.engine.BeltPriority
import run.basir.engine.Level
import run.basir.engine.Motor
import run.basir.engine.Side

private class BeltLevel(val value: Int, val name: String)

private val BELT_STRENGTHS = listOf(BeltLevel(55, "خفيف"), BeltLevel(75, "متوسط"), BeltLevel(100, "قوي"))
// names = the recorded phrases (Phrase.BeltCooldown*), so they are said in the recorded voice
private val COOLDOWNS = listOf(BeltLevel(800, "المهلة قصيرة، أقل من ثانية"), BeltLevel(1200, "المهلة متوسطة، ثانية وخُمس"),
    BeltLevel(1600, "المهلة طويلة، ثانية ونصف"), BeltLevel(2000, "المهلة طويلة جداً، ثانيتان"))
private val STRENGTH_PHRASES = listOf(Phrase.StrengthLight, Phrase.StrengthMedium, Phrase.StrengthStrong)
private fun motorName(m: Motor) = "المحرك ${m.ordinal + 1}، ${m.arabic}"
private const val BELT_QUESTIONS = 6
private const val BELT_PASS = 5

private fun List<BeltLevel>.nearest(v: Int) = indices.minBy { abs(this[it].value - v) }

/**
 * The vibration belt: connect it, check that each of the five motors sits where it should, learn every cue (where it
 * vibrates = the direction, how it vibrates = the kind of message, silence = on the path), choose the strength and the
 * quiet time between cues, and take a recognition quiz without looking. Tap any control to hear it, hold to use it.
 */
@Composable
fun BeltTrainingScreen(prefs: Prefs, voice: Voice, haptics: HapticEngine, onBack: () -> Unit) {
    val belt = haptics.belt
    val lang = haptics.beltLanguage
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(belt.state) }
    var strength by remember { mutableIntStateOf(BELT_STRENGTHS.nearest(prefs.beltIntensityPct)) }
    var cooldown by remember { mutableIntStateOf(COOLDOWNS.nearest(prefs.cueCooldownMs)) }
    val playing = remember { mutableStateOf<Job?>(null) }
    var question by remember { mutableIntStateOf(-1) }
    var correct by remember { mutableIntStateOf(0) }
    var asked by remember { mutableStateOf<BeltPattern?>(null) }
    var options by remember { mutableStateOf<List<BeltPattern>>(emptyList()) }
    var answered by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<String?>(null) }
    val quiz = remember { lang.catalog().filter { it.priority != BeltPriority.CONTINUE } }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { belt.connect() }
    fun connect() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (belt.hasPermission()) belt.connect() else permissions.launch(needed)
    }

    // The link state, read a few times a second; every change is said once.
    LaunchedEffect(Unit) {
        voice.sayText("حزام الاهتزاز. مكان الاهتزاز هو الاتجاه، وطريقته هي نوع الرسالة، والسكون يعني أنك على المسار.", interrupt = true)
        var said = belt.state
        while (true) {
            state = belt.state
            if (state != said) { said = state; voice.sayText(state.ar, interrupt = true) }
            delay(300)
        }
    }

    fun playAll(patterns: List<BeltPattern>, spoken: List<String?> = emptyList()) {
        playing.value?.cancel()
        playing.value = scope.launch {
            for ((i, p) in patterns.withIndex()) {
                spoken.getOrNull(i)?.let { voice.sayText(it, interrupt = true); delay(1800) }
                if (!haptics.playBelt(p)) { voice.sayText(BeltState.OFF.ar, interrupt = true); return@launch }
                delay(p.durationMs + 900)
            }
        }
    }

    fun ask(n: Int) {
        val p = quiz.random()
        asked = p
        options = (quiz.filter { it.meaningAr != p.meaningAr }.distinctBy { it.meaningAr }.shuffled().take(3) + p).shuffled()
        question = n
        answered = false
        playAll(listOf(p))
    }

    fun answer(choice: BeltPattern) {
        if (answered) return
        answered = true
        val right = choice.meaningAr == asked?.meaningAr
        if (right) correct++
        voice.say(if (right) Phrase.QuizCorrect else Phrase.QuizWrong, interrupt = true)
        if (!right) asked?.let { voice.sayText(it.meaningAr) }
        if (question + 1 < BELT_QUESTIONS) {
            scope.launch { delay(if (right) 1300 else 4000); ask(question + 1) }
        } else {
            val passed = correct >= BELT_PASS
            if (passed) prefs.beltQuizPassed = true
            result = String.format(Locale.US, "%d من %d صحيحة. %s", correct, BELT_QUESTIONS, if (passed) "اجتزت اختبار الحزام" else "لم تجتز الاختبار بعد")
            voice.say(if (passed) Phrase.QuizPassed else Phrase.QuizFailed, interrupt = false)
            question = -1
            asked = null
        }
    }

    BasirBackground {
        Column(
            modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader("حزام الاهتزاز", onBack)
            Text(state.ar + (if (state == BeltState.CONNECTED) belt.deviceName?.let { ": $it" } ?: "" else ""),
                style = MaterialTheme.typography.titleMedium, color = if (state == BeltState.CONNECTED) Tactile else InkMuted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })

            if (question < 0) {
                Card {
                    if (state != BeltState.CONNECTED) {
                        SpeakButton("ابحث عن الحزام واتصل", { connect() }, tone = ButtonTone.Primary, sayHowToUse = true, modifier = Modifier.fillMaxWidth())
                    } else {
                        SpeakButton("أوقف استعمال الحزام", { belt.disconnect() }, sayHowToUse = true, modifier = Modifier.fillMaxWidth())
                    }
                    SpeakButton("ابحث عن حزام آخر", { belt.forgetAndScan() }, sayHowToUse = true, modifier = Modifier.fillMaxWidth())
                    Text("عند انقطاع الحزام يعود الاهتزاز إلى الهاتف تلقائياً.", style = MaterialTheme.typography.labelMedium, color = InkMuted)
                }

                if (state == BeltState.CONNECTED) {
                    Card {
                        Text("فحص المحركات: يجب أن تشعر بكل محرك في مكانه", style = MaterialTheme.typography.titleMedium, color = Ink)
                        Motor.values().forEach { m ->
                            SpeakButton(motorName(m), { playAll(listOf(lang.motorTest(m))) },
                                hint = "اضغط مطولاً ليهتز", sayHowToUse = false, modifier = Modifier.fillMaxWidth())
                        }
                        SpeakButton("المحركات الخمسة بالترتيب من اليسار إلى اليمين",
                            { playAll(Motor.values().map { lang.motorTest(it) }, Motor.values().map { motorName(it) }) },
                            tone = ButtonTone.Accent, sayHowToUse = false, modifier = Modifier.fillMaxWidth())
                    }

                    Card {
                        Text("قوة الحزام: ${BELT_STRENGTHS[strength].name}", style = MaterialTheme.typography.titleMedium, color = Ink)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf(-1 to "اهتزاز أضعف", 1 to "اهتزاز أقوى").forEach { (step, label) ->
                                SpeakButton(label, {
                                    strength = (strength + step).coerceIn(BELT_STRENGTHS.indices)
                                    prefs.beltIntensityPct = BELT_STRENGTHS[strength].value
                                    belt.applyConfig()
                                    voice.say(STRENGTH_PHRASES[strength], interrupt = true)
                                    playing.value?.cancel()
                                    playing.value = scope.launch {
                                        delay(1800)
                                        val l = lang.correct(Side.LEFT, Level.MEDIUM)
                                        haptics.playBelt(l); delay(l.durationMs + 900)
                                        haptics.playBelt(lang.correct(Side.RIGHT, Level.MEDIUM))
                                    }
                                }, hint = null, sayHowToUse = false, modifier = Modifier.weight(1f))
                            }
                        }
                        Text(COOLDOWNS[cooldown].name, style = MaterialTheme.typography.titleMedium, color = Ink)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf(-1 to "مهلة أقصر", 1 to "مهلة أطول").forEach { (step, label) ->
                                SpeakButton(label, {
                                    cooldown = (cooldown + step).coerceIn(COOLDOWNS.indices)
                                    prefs.cueCooldownMs = COOLDOWNS[cooldown].value
                                    // feel the spacing: a left correction, the chosen quiet time, then a right correction
                                    playing.value?.cancel()
                                    playing.value = scope.launch {
                                        voice.sayText(COOLDOWNS[cooldown].name, interrupt = true); delay(2200)
                                        val l = lang.correct(Side.LEFT, Level.MEDIUM)
                                        haptics.playBelt(l); delay(l.durationMs + COOLDOWNS[cooldown].value)
                                        haptics.playBelt(lang.correct(Side.RIGHT, Level.MEDIUM))
                                    }
                                }, hint = null, sayHowToUse = false, modifier = Modifier.weight(1f))
                            }
                        }
                        Text("التحذيرات والتوقف لا تنتظر هذه المهلة أبداً.", style = MaterialTheme.typography.labelMedium, color = InkMuted)
                    }

                    Card {
                        Text("الإشارات ومعانيها", style = MaterialTheme.typography.titleMedium, color = Ink)
                        lang.catalog().forEach { p ->
                            SpeakButton(p.meaningAr, { playAll(listOf(p), listOf(p.meaningAr)) }, hint = "اضغط مطولاً للتجربة", sayHowToUse = false, modifier = Modifier.fillMaxWidth())
                        }
                    }

                    result?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Tactile, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    SpeakButton(if (prefs.beltQuizPassed) "أعد اختبار الحزام" else "اختبار التعرّف على إشارات الحزام", {
                        playing.value?.cancel(); correct = 0; result = null
                        voice.sayText("سأشغّل ست إشارات. بعد كل إشارة اختر معناها دون النظر إلى الشاشة.", interrupt = true)
                        scope.launch { delay(4000); ask(0) }
                    }, tone = ButtonTone.Primary, sayHowToUse = false, modifier = Modifier.fillMaxWidth())
                }
            } else {
                Card {
                    Text(String.format(Locale.US, "السؤال %d من %d: ما معنى هذا الاهتزاز؟", question + 1, BELT_QUESTIONS),
                        style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                SpeakButton("أعد تشغيل الإشارة", { asked?.let { playAll(listOf(it)) } }, tone = ButtonTone.Accent, sayHowToUse = false, modifier = Modifier.fillMaxWidth())
                options.forEach { o -> SpeakButton(o.meaningAr, { answer(o) }, hint = "اضغط مطولاً للاختيار", sayHowToUse = false, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}
