// CameraController.kt
package com.datafy.foottraffic.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraController(private val context: Context, private val lifecycleOwner: LifecycleOwner) {

    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    companion object {
        private const val TARGET_WIDTH = 1920
        private const val TARGET_HEIGHT = 1080
        private const val FRAME_RATE_MS = 33L // ~30 FPS
    }

    suspend fun startCamera(onFrameReady: (ImageProxy) -> Unit) = withContext(Dispatchers.Main) {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProvider = cameraProviderFuture.get()

            bindCameraUseCases(onFrameReady)

            Timber.d("Camera started successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start camera")
            throw e
        }
    }

    private fun bindCameraUseCases(onFrameReady: (ImageProxy) -> Unit) {
        val cameraProvider = cameraProvider ?: run {
            Timber.e("Camera provider is null")
            return
        }

        // Unbind all use cases before rebinding
        cameraProvider.unbindAll()

        // Select back camera
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        // Configure image analysis
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetResolution(Size(TARGET_WIDTH, TARGET_HEIGHT))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .apply {
                setAnalyzer(cameraExecutor, FrameAnalyzer(onFrameReady))
            }

        try {
            // Bind use cases to camera using provided lifecycle owner
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageAnalyzer
            )

            Timber.d("Camera use cases bound successfully")

        } catch (e: Exception) {
            Timber.e(e, "Use case binding failed")
            throw e
        }
    }

    fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
            Timber.d("Camera stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping camera")
        }
    }

    private class FrameAnalyzer(
        private val onFrameReady: (ImageProxy) -> Unit
    ) : ImageAnalysis.Analyzer {

        private var lastAnalyzedTimestamp = 0L

        override fun analyze(image: ImageProxy) {
            val currentTimestamp = System.currentTimeMillis()

            // Control frame rate
            if (currentTimestamp - lastAnalyzedTimestamp >= FRAME_RATE_MS) {
                try {
                    onFrameReady(image)
                    lastAnalyzedTimestamp = currentTimestamp
                } catch (e: Exception) {
                    Timber.e(e, "Error in frame analysis")
                    image.close()
                }
            } else {
                // Skip frame to maintain target frame rate
                image.close()
            }
        }
    }
}