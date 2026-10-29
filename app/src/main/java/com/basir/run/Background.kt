package com.basir.run

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The navy backdrop with fine, slowly moving yellow shapes: running lanes that bend towards
 * the horizon with dashes flowing along them, a grid of small dots that light up around a
 * touch, thin rotating ring segments and a ripple where the screen was touched. It is purely
 * decorative: hidden from TalkBack, never takes a touch away from the controls on top, and
 * stands still when the phone's animations are turned off or [animated] is false (during
 * guidance, so the processor stays free for the camera).
 */
@Composable
fun BasirBackground(animated: Boolean = true, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    val motionAllowed = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val moving = animated && motionAllowed
    var timeMs by remember { mutableLongStateOf(0L) }
    var touch by remember { mutableStateOf<Offset?>(null) }
    var touchAt by remember { mutableLongStateOf(-10_000L) }

    if (moving) {
        LaunchedEffect(Unit) {
            val start = withFrameMillis { it }
            while (true) withFrameMillis { timeMs = it - start }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            // Watches touches on the way down without consuming them, so buttons work as usual.
            .pointerInput(moving) {
                if (!moving) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val c = e.changes.firstOrNull() ?: continue
                        if (c.pressed && !c.previousPressed) touchAt = timeMs
                        if (c.pressed) touch = c.position
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
            drawBackdrop(timeMs / 1000f, touch, (timeMs - touchAt) / 1000f)
        }
        content()
    }
}

private val YellowSweep = listOf(Color(0xFFFFE27A), Tactile, Amber)

private fun DrawScope.drawBackdrop(t: Float, touch: Offset?, sinceTouch: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1730), Navy, NavyDeep)))

    // Soft warm glow drifting near the top corner.
    val glow = Offset(w * (0.82f + 0.05f * sin(t * 0.21f)), h * (0.12f + 0.03f * cos(t * 0.17f)))
    drawCircle(Brush.radialGradient(listOf(Tactile.copy(alpha = 0.16f), Color.Transparent), glow, w * 0.75f), w * 0.75f, glow)
    val cool = Offset(w * 0.1f, h * 0.9f)
    drawCircle(Brush.radialGradient(listOf(Teal.copy(alpha = 0.10f), Color.Transparent), cool, w * 0.7f), w * 0.7f, cool)

    // Lanes: curves from the bottom edge bending towards a horizon point, leaning towards the touch.
    val lean = touch?.let { (it.x / w - 0.5f) * 0.08f } ?: 0f
    val horizon = Offset(w * (0.62f + lean + 0.02f * sin(t * 0.3f)), h * 0.30f)
    val lanes = 9
    val laneBrush = Brush.linearGradient(YellowSweep.map { it.copy(alpha = 0.22f) }, Offset(0f, h), horizon)
    val flowBrush = Brush.linearGradient(YellowSweep.map { it.copy(alpha = 0.55f) }, Offset(0f, h), horizon)
    for (i in 0 until lanes) {
        val f = i / (lanes - 1f)
        val baseX = w * (-0.35f + 1.7f * f)
        val path = Path().apply {
            moveTo(baseX, h * 1.02f)
            cubicTo(baseX, h * 0.72f, horizon.x + (baseX - horizon.x) * 0.28f, h * 0.48f, horizon.x, horizon.y)
        }
        drawPath(path, laneBrush, style = Stroke(width = 1.1f * density))
        if (i % 2 == 1) {
            val dash = 10f * density
            val gap = 26f * density
            drawPath(
                path, flowBrush,
                style = Stroke(
                    width = 2f * density, cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), -t * 38f * density * (1f + 0.15f * i)),
                ),
            )
        }
    }

    // Dot grid: faint everywhere, brighter on a slow wave and around the last touch.
    val step = 24f * density
    var y = step / 2
    while (y < h) {
        var x = step / 2
        while (x < w) {
            val wave = 0.5f + 0.5f * sin(x / w * 6f + y / h * 4f - t * 0.9f)
            val near = touch?.let { (1f - hypot(it.x - x, it.y - y) / (w * 0.35f)).coerceIn(0f, 1f) } ?: 0f
            val a = 0.05f + 0.10f * wave + 0.45f * near * near
            drawCircle(Tactile.copy(alpha = a.coerceAtMost(0.6f)), (0.9f + 1.4f * near) * density, Offset(x, y))
            x += step
        }
        y += step
    }

    // Thin ring segments turning slowly around a point low on the screen.
    val hub = Offset(w * 0.18f, h * 0.74f)
    listOf(0.20f to 1f, 0.29f to -0.7f, 0.37f to 0.45f).forEachIndexed { i, (r, speed) ->
        val radius = w * r
        val sweep = 70f + 25f * i
        repeat(3) { k ->
            drawArc(
                brush = Brush.sweepGradient(listOf(Color.Transparent, Tactile.copy(alpha = 0.35f), Amber.copy(alpha = 0.10f)), hub),
                startAngle = (t * 12f * speed + k * 120f) % 360f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(hub.x - radius, hub.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = (1f + 0.4f * i) * density, cap = StrokeCap.Round),
            )
        }
    }

    // Small diamonds marking the horizon point, like distance markers on a track.
    repeat(4) { k ->
        val a = (t * 0.4f + k * PI.toFloat() / 2)
        val p = Offset(horizon.x + cos(a) * 18f * density, horizon.y + sin(a) * 9f * density)
        val s = 2.6f * density
        drawPath(Path().apply { moveTo(p.x, p.y - s); lineTo(p.x + s, p.y); lineTo(p.x, p.y + s); lineTo(p.x - s, p.y); close() }, Tactile.copy(alpha = 0.5f))
    }

    // Ripple from the last touch.
    if (touch != null && sinceTouch in 0f..1.4f) {
        val k = sinceTouch / 1.4f
        drawCircle(Tactile.copy(alpha = 0.45f * (1f - k)), w * 0.06f + w * 0.4f * k, touch, style = Stroke(width = 1.5f * density))
        drawCircle(Amber.copy(alpha = 0.25f * (1f - k)), w * 0.03f + w * 0.25f * k, touch, style = Stroke(width = 1f * density))
    }
}
