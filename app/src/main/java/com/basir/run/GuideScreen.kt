package com.basir.run

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.basir.run.vision.Curve
import com.basir.run.vision.LaneDetector
import com.basir.run.vision.LaneResult
import com.basir.run.vision.LumaFrame
import com.basir.run.vision.PathShape
import java.util.concurrent.Executors
import kotlin.math.abs

// Live camera guidance. The phone sits on the chest harness with the rear camera facing the
// track ahead; the screen stays on while this is open.
@Composable
fun GuideScreen(
    run: RunState,
    prefs: Prefs,
    speaker: Speaker,
    tone: ToneGuide,
    onStopHint: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val detector = remember { LaneDetector(prefs.pathMode) }
    val engine = remember { GuidanceEngine(context, tone, speaker, prefs) }
    var lane by remember { mutableStateOf(LaneResult(found = false)) }
    var guide by remember { mutableStateOf(GuidanceState()) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraFeed(detector) { result ->
            lane = result
            guide = engine.update(result, SystemClock.elapsedRealtime())
        }
        LaneOverlay(lane, Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row {
                StatusPill(shapeLabel(guide), if (guide.found) Tactile else Danger)
                Spacer(Modifier.weight(1f))
                val (number, unit) = distanceParts(run.distanceM)
                StatusPill("$number $unit · ${clock(run.elapsedMs)}", Good)
            }
            Spacer(Modifier.weight(1f))
            GuidancePanel(guide)
            HoldButton(
                label = "إنهاء الجري",
                hint = "اضغط مطولاً للإنهاء",
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                height = 96.dp,
                onTap = onStopHint,
                onHold = onStop,
            )
        }
    }
}

private fun shapeLabel(guide: GuidanceState) = when {
    !guide.found -> "أبحث عن الخط"
    guide.shape == PathShape.CurveLeft -> "منحنى لليسار"
    guide.shape == PathShape.CurveRight -> "منحنى لليمين"
    else -> "المسار مستقيم"
}

@Composable
private fun GuidancePanel(guide: GuidanceState) {
    val color = if (!guide.found) Danger else listOf(Good, Tactile, Warn, Danger)[guide.level]
    val direction = if (guide.moveLeft) "يساراً" else "يميناً"
    val instruction = when {
        !guide.found -> "لا أرى الخط"
        guide.level == 0 -> "أنت في المنتصف"
        guide.level == 1 -> "اتجه $direction قليلاً"
        guide.level == 2 -> "اتجه $direction"
        else -> "اتجه $direction الآن"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xE60E1113))
            .padding(18.dp)
            .clearAndSetSemantics { contentDescription = instruction },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(instruction, style = MaterialTheme.typography.headlineSmall, color = color)
        // The gauge is drawn left to right as the runner feels it, whatever the text direction.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PositionGauge(guide, color)
                Row(Modifier.fillMaxWidth()) {
                    Text("يسار", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("المنتصف", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("يمين", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// The bar is the lane; the dot is the runner. Zones show where each vibration level starts.
@Composable
private fun PositionGauge(guide: GuidanceState, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(34.dp)) {
        val mid = size.width / 2
        val half = size.width / 2 - 17.dp.toPx()
        val barTop = size.height / 2 - 5.dp.toPx()
        drawRoundRect(Color(0xFF2A3236), Offset(0f, barTop), GeoSize(size.width, 10.dp.toPx()), CornerRadius(5.dp.toPx()))
        listOf(0.15f to Good, 0.35f to Tactile, 0.6f to Warn).reversed().forEach { (edge, zone) ->
            val span = edge / GAUGE_RANGE * half
            drawRoundRect(zone.copy(alpha = 0.35f), Offset(mid - span, barTop), GeoSize(span * 2, 10.dp.toPx()), CornerRadius(5.dp.toPx()))
        }
        drawLine(Color.White, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 2.dp.toPx())
        val x = mid + guide.offset.coerceIn(-GAUGE_RANGE, GAUGE_RANGE) / GAUGE_RANGE * half
        drawCircle(if (guide.found) color else Color(0xFF6B7579), radius = 13.dp.toPx(), center = Offset(x, size.height / 2))
        drawCircle(Color(0xFF0E1113), radius = 5.dp.toPx(), center = Offset(x, size.height / 2))
    }
}

private const val GAUGE_RANGE = 1.2f

// Draws the detected edges and the path centre over the preview, matched to its centre crop.
@Composable
private fun LaneOverlay(lane: LaneResult, modifier: Modifier) {
    Canvas(modifier) {
        val aspect = lane.aspect
        val shownWidth: Float
        val shownHeight: Float
        if (size.width / size.height > aspect) {
            shownWidth = size.width
            shownHeight = size.width / aspect
        } else {
            shownHeight = size.height
            shownWidth = size.height * aspect
        }
        val left = (size.width - shownWidth) / 2
        val top = (size.height - shownHeight) / 2
        fun at(x: Float, y: Float) = Offset(left + x * shownWidth, top + y * shownHeight)
        fun path(curve: Curve) = Path().apply {
            for (i in 0..24) {
                val y = lane.roiTop + (1f - lane.roiTop) * i / 24
                val p = at(curve.x(y), y)
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
        }

        drawLine(
            Color.White.copy(alpha = 0.6f), at(0.5f, lane.roiTop), at(0.5f, 1f),
            strokeWidth = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 18f)),
        )
        lane.lines.forEach { drawPath(path(it), Tactile, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round)) }
        lane.center?.let {
            val centerColor = if (abs(lane.offset) < 0.15f) Good else Warn
            drawPath(path(it), centerColor, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun CameraFeed(detector: LaneDetector, onResult: (LaneResult) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnResult by rememberUpdatedState(onResult)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            keepScreenOn = true
        }
    }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        val copier = LumaCopier()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            provider = cameraProvider
            val fourByThree = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            val preview = Preview.Builder()
                .setResolutionSelector(fourByThree.build())
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    fourByThree
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                val result = try {
                    detector.detect(copier.frame(image))
                } finally {
                    image.close()
                }
                main.post { latestOnResult(result) }
            }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

// Copies the Y plane into a reused buffer so the image can be closed straight away.
private class LumaCopier {
    private var bytes = ByteArray(0)

    fun frame(image: ImageProxy): LumaFrame {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val size = buffer.remaining()
        if (bytes.size < size) bytes = ByteArray(size)
        buffer.get(bytes, 0, size)
        return LumaFrame(bytes, image.width, image.height, plane.rowStride, image.imageInfo.rotationDegrees)
    }
}
