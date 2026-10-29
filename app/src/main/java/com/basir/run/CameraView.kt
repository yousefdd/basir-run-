package com.basir.run

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.basir.run.vision.Curve
import com.basir.run.vision.FrameSnapshot
import com.basir.run.vision.GuidancePipeline
import com.basir.run.vision.PathStatus
import java.util.concurrent.Executors

/**
 * Rear camera preview plus frame analysis on a background thread. Every analysed frame goes
 * through the pipeline and its snapshot is delivered on the main thread. If the camera cannot
 * start, onError is called with a message for the user.
 */
@Composable
fun CameraHost(pipeline: GuidancePipeline, onSnapshot: (FrameSnapshot) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestSnapshot by rememberUpdatedState(onSnapshot)
    val latestError by rememberUpdatedState(onError)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            keepScreenOn = true
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val fourByThree = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                val preview = Preview.Builder().setResolutionSelector(fourByThree.build()).build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        fourByThree.setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        val snapshot = pipeline.process(image)
                        main.post { latestSnapshot(snapshot) }
                    } catch (e: Exception) {
                        Log.e("CameraHost", "Frame analysis failed", e)
                    } finally {
                        image.close()
                    }
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.e("CameraHost", "Camera failed to start", e)
                latestError("تعذّر تشغيل الكاميرا الخلفية")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/**
 * The detected edges and the virtual running line drawn over the preview. Solid yellow edges and
 * a green running line appear only when the path is trusted; otherwise any partial result is
 * drawn thin, grey and dashed so it cannot be mistaken for a confirmed path.
 */
@Composable
fun PathOverlay(snapshot: FrameSnapshot?, trusted: Boolean, runnerX: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val shot = snapshot ?: return@Canvas
        val estimate = shot.path.estimate
        val aspect = estimate.aspect
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
                val y = estimate.roiTop + (1f - estimate.roiTop) * i / 24
                val p = at(curve.x(y), y)
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
        }
        val dashed = PathEffect.dashPathEffect(floatArrayOf(20f, 16f))

        // Where the runner is, as calibrated.
        drawLine(Color.White.copy(alpha = 0.7f), at(runnerX, 0.82f), at(runnerX, 1f), strokeWidth = 5.px(this), cap = StrokeCap.Round)

        val edges = listOfNotNull(estimate.left, estimate.right)
        if (trusted && estimate.status == PathStatus.Tracked) {
            edges.forEach { drawPath(path(it), Tactile, style = Stroke(9.px(this), cap = StrokeCap.Round)) }
            estimate.center?.let { drawPath(path(it), Good, style = Stroke(6.px(this), cap = StrokeCap.Round)) }
        } else {
            edges.forEach { drawPath(path(it), Color(0xFF9AA4A8), style = Stroke(3.px(this), pathEffect = dashed)) }
        }
        shot.detections.forEach { d ->
            drawRect(
                Danger,
                topLeft = at(d.left, d.top),
                size = androidx.compose.ui.geometry.Size((d.right - d.left) * shownWidth, (d.bottom - d.top) * shownHeight),
                style = Stroke(3.px(this)),
            )
        }
    }
}

private fun Int.px(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) { this@px.toFloat() * density }
