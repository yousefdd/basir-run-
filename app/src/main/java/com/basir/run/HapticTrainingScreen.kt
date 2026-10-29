package com.basir.run

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    Lesson(Haptic.Correction, "نبضة قصيرة واحدة: تصحيح بسيط", Phrase.PatternCorrection),
    Lesson(Haptic.Attention, "نبضتان قصيرتان: منحنى أو اقتراب من الحد", Phrase.PatternAttention),
    Lesson(Haptic.Hazard, "ثلاث نبضات سريعة: خطر محتمل", Phrase.PatternHazard),
    Lesson(Haptic.Stop, "اهتزاز طويل: توقف فوراً", Phrase.PatternStop),
)

/**
 * Teaches the vibration patterns before the first guided run and lets the user tune them.
 * Each lesson says the meaning first, then plays the pattern, so the two are linked.
 */
@Composable
fun HapticTrainingScreen(prefs: Prefs, voice: Voice, haptics: HapticEngine, onDone: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var strength by remember { mutableFloatStateOf(prefs.hapticStrength) }
    var duration by remember { mutableFloatStateOf(prefs.hapticDuration) }
    val playing = remember { mutableStateOf<Job?>(null) }

    fun teach(lessons: List<Lesson>) {
        playing.value?.cancel()
        playing.value = scope.launch {
            for (l in lessons) {
                voice.say(l.phrase, interrupt = true)
                delay(2600)
                if (!haptics.play(l.haptic)) voice.say(Phrase.HapticsUnavailable, interrupt = true)
                delay(1600)
            }
        }
    }

    LaunchedEffect(Unit) { voice.say(Phrase.TrainingIntro, interrupt = true) }

    Column(
        modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("تعلّم أنماط الاهتزاز", onBack)
        Text(
            "هذه الأنماط اقتراح أولي. جرّب كل نمط حتى تميّزه دون النظر إلى الشاشة. عدّل القوة والمدة حتى تشعر بها بوضوح أثناء الحركة.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!haptics.available) {
            Text(
                "هذا الهاتف لا يدعم الاهتزاز. سيعتمد التطبيق على الصوت.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        LESSONS.forEach { l ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(l.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Button(
                    onClick = { teach(listOf(l)) },
                    modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = "جرّب ${l.title}" },
                    shape = RoundedCornerShape(18.dp),
                ) { Text("جرّب", style = MaterialTheme.typography.labelLarge) }
            }
        }
        Button(
            onClick = { teach(LESSONS) },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
        ) { Text("تشغيل كل الأنماط بالترتيب", style = MaterialTheme.typography.labelLarge) }

        SectionTitle("قوة الاهتزاز")
        Slider(
            value = strength,
            onValueChange = { strength = it },
            onValueChangeFinished = { prefs.hapticStrength = strength; haptics.play(Haptic.Correction) },
            valueRange = HapticPatterns.MIN_STRENGTH..HapticPatterns.MAX_STRENGTH,
            modifier = Modifier.semantics { stateDescription = String.format(Locale.US, "%.0f%%", strength * 100) },
        )
        SectionTitle("مدة النبضة")
        Slider(
            value = duration,
            onValueChange = { duration = it },
            onValueChangeFinished = { prefs.hapticDuration = duration; haptics.play(Haptic.Attention) },
            valueRange = HapticPatterns.MIN_DURATION..HapticPatterns.MAX_DURATION,
            modifier = Modifier.semantics { stateDescription = String.format(Locale.US, "×%.1f", duration) },
        )

        Button(
            onClick = {
                playing.value?.cancel()
                prefs.hapticTrained = true
                onDone()
            },
            modifier = Modifier.fillMaxWidth().height(68.dp),
            shape = RoundedCornerShape(26.dp),
        ) { Text("فهمت الأنماط", style = MaterialTheme.typography.titleMedium) }
    }
}
