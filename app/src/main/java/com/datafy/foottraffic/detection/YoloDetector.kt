package com.datafy.foottraffic.detection

import android.content.Context
import android.graphics.*
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import timber.log.Timber
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class YoloDetector(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val inputSize = 640
    private val outputSize = 25200
    private val numClasses = 80

    companion object {
        private const val MODEL_PATH = "yolov8n.tflite"
        private const val CONFIDENCE_THRESHOLD = 0.45f
        private const val IOU_THRESHOLD = 0.5f
        private const val PERSON_CLASS = 0
    }

    data class Detection(
        val bbox: RectF,
        val confidence: Float,
        val classId: Int = PERSON_CLASS,
        var trackId: Int = -1
    )

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val modelBuffer = loadModelFile()

            val options = Interpreter.Options().apply {
                setNumThreads(numThreads)

                try {
                    // ✅ Safe GPU delegate init
                    gpuDelegate = GpuDelegate()
                    addDelegate(gpuDelegate)
                    Timber.d("GPU delegate enabled for YOLO")
                } catch (e: Throwable) {
                    Timber.w("GPU delegate not available, falling back to CPU: $e")
                }
            }

            interpreter = Interpreter(modelBuffer, options)
            Timber.d("YOLO model loaded: $MODEL_PATH")

        } catch (e: Exception) {
            Timber.e(e, "Failed to load YOLO model")
        }
    }


    private fun tryNNAPIFallback(options: Interpreter.Options) {
        try {
            options.setUseNNAPI(true)
            Timber.d("Using NNAPI acceleration")
        } catch (e: Exception) {
            Timber.w(e, "NNAPI also failed, using CPU only")
        }
    }

    private fun loadModelCpuOnly() {
        try {
            Timber.d("Attempting to load model with CPU only")
            val modelBuffer = loadModelFile()

            val options = Interpreter.Options().apply {
                setNumThreads(4)
                // Explicitly disable any acceleration
                setUseNNAPI(false)
            }

            interpreter = Interpreter(modelBuffer, options)

            if (interpreter == null) {
                throw RuntimeException("Failed to create CPU-only interpreter")
            }

            Timber.d("YOLO model loaded with CPU only")
            logModelInfo()

        } catch (e: Exception) {
            Timber.e(e, "Failed to load model even with CPU only")
            cleanup()
            throw RuntimeException("Cannot load YOLO model. Please ensure ${MODEL_PATH} exists in assets folder", e)
        }
    }

    private fun logModelInfo() {
        try {
            interpreter?.let { interp ->
                val inputTensor = interp.getInputTensor(0)
                val outputTensor = interp.getOutputTensor(0)

                if (inputTensor != null && outputTensor != null) {
                    val inputShape = inputTensor.shape()
                    val outputShape = outputTensor.shape()
                    Timber.d("Model loaded successfully:")
                    Timber.d("  Input shape: ${inputShape?.contentToString()}")
                    Timber.d("  Output shape: ${outputShape?.contentToString()}")
                    Timber.d("  Input data type: ${inputTensor.dataType()}")
                    Timber.d("  Output data type: ${outputTensor.dataType()}")
                } else {
                    Timber.w("Could not retrieve tensor information")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not log model info")
        }
    }

    private fun loadModelFile(): ByteBuffer {
        return try {
            context.assets.openFd(MODEL_PATH).use { fileDescriptor ->
                FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                    val fileChannel = inputStream.channel
                    val startOffset = fileDescriptor.startOffset
                    val declaredLength = fileDescriptor.declaredLength

                    fileChannel.map(
                        FileChannel.MapMode.READ_ONLY,
                        startOffset,
                        declaredLength
                    ).apply {
                        order(ByteOrder.nativeOrder())
                    }
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Model file not found: ${MODEL_PATH}. Please add the YOLO model to assets folder", e)
        }
    }

    fun detectPeople(imageProxy: ImageProxy): List<Detection> {
        return try {
            if (interpreter == null) {
                Timber.w("Interpreter is null, cannot detect people")
                return emptyList()
            }

            val bitmap = imageProxyToBitmap(imageProxy)
            detect(bitmap)
        } catch (e: Exception) {
            Timber.e(e, "Error detecting people from ImageProxy")
            emptyList()
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val interpreter = interpreter ?: run {
            Timber.e("Interpreter is null - model not loaded")
            return emptyList()
        }

        return try {
            val inputBuffer = preprocessImage(bitmap)
            val outputBuffer = Array(1) { Array(outputSize) { FloatArray(85) } }

            // Run inference with timeout handling
            interpreter.run(inputBuffer, outputBuffer)

            processOutputs(outputBuffer[0], bitmap.width, bitmap.height)

        } catch (e: Exception) {
            Timber.e(e, "Error during detection inference")
            emptyList()
        }
    }

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        // Resize bitmap to model input size with proper scaling
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // Allocate buffer for RGB values (3 channels * 4 bytes per float)
        val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        // Get pixel values
        val pixels = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        // Convert pixels to normalized RGB float values (0.0 to 1.0)
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }

        // Clean up temporary bitmap if it's different from original
        if (resizedBitmap != bitmap && !resizedBitmap.isRecycled) {
            resizedBitmap.recycle()
        }

        return inputBuffer
    }

    private fun processOutputs(outputs: Array<FloatArray>, imgWidth: Int, imgHeight: Int): List<Detection> {
        val detections = mutableListOf<Detection>()

        for (i in outputs.indices) {
            val output = outputs[i]

            // Validate output array size
            if (output.size < 85) {
                Timber.w("Output array too small: ${output.size}, expected at least 85")
                continue
            }

            // YOLO format: [x, y, w, h, objectness, class_scores...]
            val objectness = output[4]

            if (objectness > CONFIDENCE_THRESHOLD) {
                // Get class scores (80 classes starting at index 5)
                val classScores = output.sliceArray(5..84)
                val maxClassIndex = classScores.indices.maxByOrNull { classScores[it] } ?: 0
                val maxClassScore = classScores[maxClassIndex]

                // We only want person detections (class 0)
                if (maxClassIndex == PERSON_CLASS) {
                    val confidence = objectness * maxClassScore

                    if (confidence > CONFIDENCE_THRESHOLD) {
                        // Convert YOLO coordinates to image coordinates
                        val cx = output[0] * imgWidth / inputSize
                        val cy = output[1] * imgHeight / inputSize
                        val w = output[2] * imgWidth / inputSize
                        val h = output[3] * imgHeight / inputSize

                        // Validate coordinates
                        if (w > 0 && h > 0) {
                            // Convert center coordinates to corner coordinates
                            val left = (cx - w / 2).coerceAtLeast(0f)
                            val top = (cy - h / 2).coerceAtLeast(0f)
                            val right = (cx + w / 2).coerceAtMost(imgWidth.toFloat())
                            val bottom = (cy + h / 2).coerceAtMost(imgHeight.toFloat())

                            // Ensure valid bounding box
                            if (right > left && bottom > top) {
                                detections.add(
                                    Detection(
                                        bbox = RectF(left, top, right, bottom),
                                        confidence = confidence,
                                        classId = PERSON_CLASS
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Apply Non-Maximum Suppression
        return nonMaxSuppression(detections)
    }

    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        // Sort by confidence (highest first)
        val sorted = detections.sortedByDescending { it.confidence }
        val selected = mutableListOf<Detection>()

        for (detection in sorted) {
            var keep = true

            // Check IoU with already selected detections
            for (selectedDetection in selected) {
                if (calculateIoU(detection.bbox, selectedDetection.bbox) > IOU_THRESHOLD) {
                    keep = false
                    break
                }
            }

            if (keep) {
                selected.add(detection)
            }
        }

        return selected
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val x1 = max(box1.left, box2.left)
        val y1 = max(box1.top, box2.top)
        val x2 = min(box1.right, box2.right)
        val y2 = min(box1.bottom, box2.bottom)

        val intersectionArea = max(0f, x2 - x1) * max(0f, y2 - y1)

        if (intersectionArea == 0f) return 0f

        val box1Area = box1.width() * box1.height()
        val box2Area = box2.width() * box2.height()
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        return try {
            val planes = imageProxy.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            // Copy YUV data
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            // Convert to JPEG via YuvImage
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)

            val imageBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: throw RuntimeException("Failed to decode image bytes to bitmap")

        } catch (e: Exception) {
            Timber.e(e, "Error converting ImageProxy to Bitmap")
            // Create a fallback bitmap to prevent crashes
            Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
        }
    }

    private fun cleanup() {
        try {
            gpuDelegate?.close()
            gpuDelegate = null
        } catch (e: Exception) {
            Timber.w(e, "Error closing GPU delegate")
        }
    }

    fun close() {
        try {
            interpreter?.close()
            interpreter = null
            cleanup()
            Timber.d("YOLO detector closed")
        } catch (e: Exception) {
            Timber.e(e, "Error closing YOLO detector")
        }
    }
}