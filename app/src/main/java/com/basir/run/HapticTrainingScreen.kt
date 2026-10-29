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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.basir.run.engine.Haptic
import com.basir.run.engine.HapticPatterns
import com.basir.run.engine.Phrase
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class Lesson(val haptic: Haptic, val title: String, val phrase: Phrase)

private val LESSONS = listOf(
    Lesson(Haptic.SteerLeft, "نبضة قصيرة واحدة: انحرف يساراً", Phrase.PatternLeft),
    Lesson(Haptic.SteerRight, "نبضتان قصيرتان: انحرف يميناً", Phrase.PatternRight),
    Lesson(Haptic.BendAhead, "اهتزاز يتصاعد: منحنى قادم", Phrase.PatternBend),
    Lesson(Haptic.Attention, "ثلاث نبضات سريعة: عائق أو انتباه فوري", Phrase.PatternAttention),
    Lesson(Haptic.Stop, "اهتزاز طويل: توقف فوراً", Phrase.PatternStop),
    Lesson(Haptic.Confirm, "نبضة تأكيد: اكتمل الفحص أو بدأت الجلسة", Phrase.PatternConfirm),
)

/**
 * Teaches the vibration language and lets the user tune strength and length. Each lesson says
 * the meaning first, then plays the pattern, so the two are linked. Part of the first use, and
 * kept in settings.
 */
@Composable
fun HapticTrainingScreen(prefs: Prefs, voice: Voice, haptics: HapticEngine, doneLabel: String, onDone: () -> Unit, onBack: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    var strength by remember { mutableFloatStateOf(prefs.hapticStrength) }
    var duration by remember { mutableFloatStateOf(prefs.hapticDuration) }
    val playing = remember { mutableStateOf<Job?>(null) }

    fun teach(lessons: List<Lesson>) {
        playing.value?.cancel()
        playing.value = scope.launch {
            for (l in lessons) {
                voice.say(l.phrase, interrupt = true)
                delay(2800)
                if (!haptics.play(l.haptic)) voice.say(Phrase.HapticsUnavailable, interrupt = true)
                delay(1800)
            }
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
                "جرّب كل نمط حتى تميّزه دون النظر إلى الشاشة، ثم عدّل القوة والمدة حتى تشعر بها بوضوح أثناء الحركة. الاهتزاز الطويل (توقف) يسبق أي نمط آخر.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkMuted,
            )
            if (!haptics.available) {
                Text("هذا الهاتف لا يدعم الاهتزاز. سيعتمد التطبيق على الصوت.", style = MaterialTheme.typography.titleMedium, color = Danger)
            }
            LESSONS.forEach { l ->
                SpeakButton(l.title, { teach(listOf(l)) }, spoken = "تجربة: ${l.title}", hint = "اضغط مطولاً للتجربة", modifier = Modifier.fillMaxWidth())
            }
            SpeakButton("كل الأنماط بالترتيب", { teach(LESSONS) }, spoken = "تشغيل كل الأنماط بالترتيب مع شرحها", tone = ButtonTone.Accent, modifier = Modifier.fillMaxWidth())

            Card {
                SectionTitle("قوة الاهتزاز")
                Slider(
                    value = strength,
                    onValueChange = { strength = it },
                    onValueChangeFinished = { prefs.hapticStrength = strength; haptics.play(Haptic.SteerLeft, level = 2) },
                    valueRange = HapticPatterns.MIN_STRENGTH..HapticPatterns.MAX_STRENGTH,
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
                    onValueChangeFinished = { prefs.hapticDuration = duration; haptics.play(Haptic.SteerRight, level = 2) },
                    valueRange = HapticPatterns.MIN_DURATION..HapticPatterns.MAX_DURATION,
                    colors = SliderDefaults.colors(thumbColor = Tactile, activeTrackColor = Teal, inactiveTrackColor = NavyRaised),
                    modifier = Modifier.semantics {
                        contentDescription = "مدة النبضة"
                        stateDescription = String.format(Locale.US, "×%.1f", duration)
                    },
                )
            }

            SpeakButton(
                doneLabel,
                {
                    playing.value?.cancel()
                    prefs.hapticTrained = true
                    onDone()
                },
                tone = ButtonTone.Primary,
                height = 76.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
