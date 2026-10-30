package com.basir.run

import com.basir.run.engine.Phrase

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The app's voice, for controls that read themselves aloud. */
val LocalVoice = staticCompositionLocalOf<Voice?> { null }

/**
 * Every control in the app works the same way: a tap reads it aloud (its name and what it
 * does), a long press carries it out. A stray touch therefore never starts, stops or calls
 * anything. With TalkBack, a double tap reads it and a double tap and hold carries it out.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.speakThenHold(spoken: String, actionLabel: String, onHold: () -> Unit, voice: Voice?, onTap: (() -> Unit)? = null, haptic: (() -> Unit)? = null) =
    this.combinedClickable(
        role = Role.Button,
        onClickLabel = "قراءة",
        onLongClickLabel = actionLabel,
        onClick = { onTap?.invoke() ?: voice?.sayControl(spoken) },
        onLongClick = {
            haptic?.invoke()
            onHold()
        },
    )

enum class ButtonTone { Primary, Neutral, Danger, Accent }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeakButton(
    label: String,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.Neutral,
    /** What a tap says; defaults to the label. */
    spoken: String = label,
    hint: String? = "اضغط مطولاً للتشغيل",
    icon: ImageVector? = null,
    height: Dp = 64.dp,
    enabled: Boolean = true,
    /** Say "press and hold to activate" after the name on a tap; off where it is obvious (lessons). */
    sayHowToUse: Boolean = true,
) {
    val voice = LocalVoice.current
    val feedback = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val (fill, ink) = when (tone) {
        ButtonTone.Primary -> Brush.linearGradient(listOf(Color(0xFF22D3C5), Teal, Color(0xFF0EA5A4))) to Color(0xFF041A1C)
        ButtonTone.Danger -> Brush.linearGradient(listOf(Color(0xFFF87171), Danger, Color(0xFFDC2626))) to Color.White
        ButtonTone.Accent -> Brush.linearGradient(listOf(Color(0xFFFFE27A), Tactile, Amber)) to Color(0xFF241800)
        ButtonTone.Neutral -> Brush.linearGradient(listOf(NavyRaised, NavyCard)) to Ink
    }
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .heightIn(min = height)
            .scale(if (pressed) 0.98f else 1f)
            .clip(shape)
            .background(if (enabled) fill else Brush.linearGradient(listOf(NavyCard, NavyCard)))
            .border(1.dp, Color.White.copy(alpha = if (tone == ButtonTone.Neutral) 0.08f else 0.18f), shape)
            .combinedClickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(),
                role = Role.Button,
                onClickLabel = "قراءة",
                onLongClickLabel = label,
                onClick = { voice?.sayControl(spoken, enabled, sayHowToUse) },
                onLongClick = {
                    if (enabled) {
                        feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onHold()
                    } else {
                        voice?.sayControl(spoken, enabled = false)
                    }
                },
            )
            .semantics {
                contentDescription = spoken
                if (!enabled) stateDescription = "غير متاح"
            }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, tint = if (enabled) ink else InkMuted, modifier = Modifier.size(26.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = if (height >= 80.dp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium, color = if (enabled) ink else InkMuted, textAlign = TextAlign.Center)
                if (hint != null) Text(hint, style = MaterialTheme.typography.labelMedium, color = (if (enabled) ink else InkMuted).copy(alpha = 0.75f))
            }
        }
    }
}

/** A setting that is on or off: a tap reads its name and state, a long press switches it. */
@Composable
fun SpeakSwitch(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val voice = LocalVoice.current
    val feedback = LocalHapticFeedback.current
    val state = if (checked) "مفعّل" else "متوقف"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(NavyCard.copy(alpha = 0.92f))
            .speakThenHold(
                spoken = title, actionLabel = if (checked) "إيقاف" else "تفعيل",
                onHold = {
                    onChange(!checked)
                    voice?.saySwitch(title, on = !checked, howToChange = false)
                },
                voice = voice,
                onTap = { voice?.saySwitch(title, on = checked, howToChange = true) },
                haptic = { feedback.performHapticFeedback(HapticFeedbackType.LongPress) },
            )
            .semantics(mergeDescendants = true) { stateDescription = state }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
            Text(hint, style = MaterialTheme.typography.labelMedium, color = InkMuted)
        }
        // A simple pill instead of a Material switch, so a tap cannot flip it.
        Box(
            Modifier
                .size(width = 54.dp, height = 32.dp)
                .clip(RoundedCornerShape(50))
                .background(if (checked) Teal else NavyRaised)
                .padding(4.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(24.dp).clip(CircleShape).background(if (checked) NavyDeep else InkMuted)) }
    }
}

/** A rounded card on the navy background. */
@Composable
fun Card(modifier: Modifier = Modifier, alpha: Float = 0.9f, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(NavyCard.copy(alpha = alpha))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** A short status line with a coloured dot; read by TalkBack as one sentence. */
@Composable
fun StatusLine(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink)
    }
}

@Composable
fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(NavyCard.copy(alpha = 0.92f))
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .clearAndSetSemantics { contentDescription = "$label $value" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = InkMuted)
        Text(value, style = MaterialTheme.typography.titleLarge, color = Ink)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Tactile,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth().semantics { heading() },
    )
}

/** The app mark: the launcher icon's eye with its vibration arcs, on a navy disc. */
@Composable
fun BasirLogo(size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(NavyDeep)
            .border(1.dp, Blue.copy(alpha = 0.35f), CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.basir_logo), contentDescription = null, modifier = Modifier.size(size))
    }
}

@Composable
fun screenModifier(): Modifier = Modifier
    .fillMaxSize()
    .windowInsetsPadding(WindowInsets.safeDrawing)

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onBack != null) {
            SpeakButton("رجوع", onBack, hint = null, height = 52.dp, spoken = Phrase.ButtonBack.ar)
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = Ink,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
    }
}

/** Hardware volume keys during a guided session: down pauses or resumes, up repeats. */
object VolumeKeys {
    @Volatile var handler: ((up: Boolean) -> Boolean)? = null
}
