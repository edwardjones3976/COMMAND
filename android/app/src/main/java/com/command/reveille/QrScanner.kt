package com.command.reveille

import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The camera of the QR handshake, behind a seam: the ring screen draws whatever [LocalQrScanner] holds, which is the real camera
 * ([CameraXQrScanner]) unless a test provides another.
 */
interface QrScanner {
    /**
     * The viewfinder, filling [modifier]'s room. Each code it reads is reported to [onDecoded] on the main thread (the same code is reported
     * again for as long as it is in view: the caller decides what a repeat means). [onError] is called when the camera cannot be used: it
     * failed to start, it is in use by another app, or it stopped. The camera is let go when the viewfinder leaves the screen.
     */
    @Composable
    fun Viewfinder(modifier: Modifier, onDecoded: (String) -> Unit, onError: () -> Unit)
}

/** The scanner the ring screen uses. */
val LocalQrScanner = staticCompositionLocalOf<QrScanner> { CameraXQrScanner }

/**
 * The phone's camera through CameraX: a preview and an analysis of the latest frame only (frames that arrive while one is being read are
 * dropped), bound to the screen's lifecycle. Each frame's brightness is read by [QrDecoder] on a thread of its own; nothing of a frame is
 * kept or sent anywhere. It cannot run under Robolectric, so the tests use a fake scanner and test [QrDecoder] on its own.
 */
object CameraXQrScanner : QrScanner {
    @Composable
    override fun Viewfinder(modifier: Modifier, onDecoded: (String) -> Unit, onError: () -> Unit) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val decoded by rememberUpdatedState(onDecoded)
        val failed by rememberUpdatedState(onError)
        val previewView = remember { PreviewView(context) }
        DisposableEffect(lifecycleOwner) {
            val gone = AtomicBoolean(false)
            val reported = AtomicBoolean(false)
            val main = ContextCompat.getMainExecutor(context)
            val analyser = Executors.newSingleThreadExecutor()
            fun fail() {
                if (!gone.get() && reported.compareAndSet(false, true)) main.execute { if (!gone.get()) failed() }
            }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(analyser) { image ->
                image.use {
                    val plane = it.planes[0]
                    val buffer = plane.buffer
                    val luma = ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
                    val text = QrDecoder.decode(luma, it.width, it.height, plane.rowStride)
                    if (text != null) main.execute { if (!gone.get()) decoded(text) }
                }
            }
            var provider: ProcessCameraProvider? = null
            // What watches the camera's state, kept so that it is let go with the camera and not left on the screen's lifecycle.
            var cameraState: LiveData<CameraState>? = null
            val stateObserver = Observer<CameraState> { state -> if (state.error != null) fail() }
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (gone.get()) return@addListener
                try {
                    val cameras = future.get()
                    provider = cameras
                    val selector = if (cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    val camera = cameras.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                    // A camera that another app holds, or that fails after it opened, says so here.
                    cameraState = camera.cameraInfo.cameraState.also { it.observe(lifecycleOwner, stateObserver) }
                } catch (_: Exception) {
                    fail()
                }
            }, main)
            onDispose {
                gone.set(true)
                cameraState?.removeObserver(stateObserver)
                provider?.unbind(preview, analysis)
                analysis.clearAnalyzer()
                analyser.shutdown()
            }
        }
        AndroidView(factory = { previewView }, modifier = modifier)
    }
}
