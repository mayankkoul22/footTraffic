package com.datafy.foottraffic.detection

import android.content.Context
import android.graphics.*
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
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
                setNumThreads(4)

                // Simplified GPU delegate setup
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    try {
                        gpuDelegate = GpuDelegate()
                        addDelegate(gpuDelegate)
                        Timber.d("GPU acceleration enabled for YOLO")
                    } catch (e: Exception) {
                        Timber.w(e, "GPU acceleration failed, falling back to NNAPI/CPU")
                        try {
                            setUseNNAPI(true)
                            Timber.d("Using NNAPI acceleration")
                        } catch (nnapiException: Exception) {
                            Timber.w(nnapiException, "NNAPI also failed, using CPU only")
                        }
                    }
                } else {
                    try {
                        setUseNNAPI(true)
                        Timber.d("GPU not supported, using NNAPI acceleration")
                    } catch (e: Exception) {
                        Timber.w(e, "NNAPI failed, using CPU only")
                    }
                }
            }

            interpreter = Interpreter(modelBuffer, options)
            logModelInfo()

        } catch (e: Exception) {
            Timber.e(e, "Failed to load YOLO model")
            loadModelCpuOnly()
        }
    }

    private fun loadModelCpuOnly() {
        try {
            Timber.d("Attempting to load model with CPU only")
            val modelBuffer = loadModelFile()

            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }

            interpreter = Interpreter(modelBuffer, options)
            Timber.d("YOLO model loaded with CPU only")
            logModelInfo()

        } catch (e: Exception) {
            Timber.e(e, "Failed to load model even with CPU only")
            throw RuntimeException("Cannot load YOLO model. Please ensure ${MODEL_PATH} exists in assets folder", e)
        }
    }

    private fun logModelInfo() {
        try {
            val inputShape = interpreter?.getInputTensor(0)?.shape()
            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            Timber.d("Model loaded - Input: ${inputShape?.contentToString()}, Output: ${outputShape?.contentToString()}")
        } catch (e: Exception) {
            Timber.w(e, "Could not log model info")
        }
    }

    private fun loadModelFile(): ByteBuffer {
        try {
            val fileDescriptor = context.assets.openFd(MODEL_PATH)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength

            return fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                startOffset,
                declaredLength
            ).apply {
                order(ByteOrder.nativeOrder())
            }
        } catch (e: Exception) {
            throw RuntimeException("Model file not found: ${MODEL_PATH}. Please add the YOLO model to assets folder", e)
        }
    }

    fun detectPeople(imageProxy: ImageProxy): List<Detection> {
        return try {
            // FIXED: Safe conversion with proper synchronization
            val bitmap = safeImageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                detect(bitmap)
            } else {
                Timber.w("Failed to convert ImageProxy to Bitmap")
                emptyList()
            }
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

            // Ask the model what the output shape is
            val shape = interpreter.getOutputTensor(0).shape() // e.g. [1,25200,85] or [1,84,8400] or [1,8400,84]
            require(shape.size == 3) { "Unexpected output rank: ${shape.contentToString()}" }
            val s1 = shape[1]
            val s2 = shape[2]

            // Allocate a container that matches the tensor exactly
            val rawOut: Any = when {
                s1 == 25200 && s2 == 85   -> Array(1) { Array(25200) { FloatArray(85) } }    // YOLOv5-style
                s1 == 84    && s2 == 8400 -> Array(1) { Array(84)    { FloatArray(8400) } }  // YOLOv8 (channels-first)
                s1 == 8400 && s2 == 84    -> Array(1) { Array(8400) { FloatArray(84) } }     // YOLOv8 (channels-last)
                else -> error("Unsupported output shape: ${shape.contentToString()}")
            }

            // Run inference
            interpreter.run(inputBuffer, rawOut)

            // Normalize to rows = candidates, cols = features
            val rows: Array<FloatArray> = when {
                // already [N, D]
                s1 == 25200 && s2 == 85 -> {
                    @Suppress("UNCHECKED_CAST")
                    (rawOut as Array<Array<FloatArray>>)[0]
                }
                // already [N, D]
                s1 == 8400 && s2 == 84 -> {
                    @Suppress("UNCHECKED_CAST")
                    (rawOut as Array<Array<FloatArray>>)[0]
                }
                // need transpose: [84, 8400] -> [8400, 84]
                s1 == 84 && s2 == 8400 -> {
                    @Suppress("UNCHECKED_CAST")
                    val chMajor = (rawOut as Array<Array<FloatArray>>)[0] // [84][8400]
                    val n = 8400; val d = 84
                    Array(n) { j -> FloatArray(d) { i -> chMajor[i][j] } }
                }
                else -> error("Unsupported output shape after run: ${shape.contentToString()}")
            }

            // Your existing logic continues via processOutputs()
            processOutputs(rows, bitmap.width, bitmap.height)

        } catch (e: Exception) {
            Timber.e(e, "Error during detection inference")
            emptyList()
        }
    }


    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        val pixels = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }

        return inputBuffer
    }

    private fun processOutputs(outputs: Array<FloatArray>, imgWidth: Int, imgHeight: Int): List<Detection> {
        val detections = mutableListOf<Detection>()

        for (row in outputs) {
            when (row.size) {
                85 -> {
                    // YOLOv5-style: [cx, cy, w, h, obj, 80 class scores]
                    val obj = row[4]
                    if (obj < CONFIDENCE_THRESHOLD) continue

                    val classScores = row.copyOfRange(5, 85)
                    val cls = classScores.indices.maxByOrNull { classScores[it] } ?: 0
                    val clsScore = classScores[cls]
                    if (cls != PERSON_CLASS) continue

                    val confidence = obj * clsScore
                    if (confidence >= CONFIDENCE_THRESHOLD) {
                        detections.add(
                            toDetection(
                                cx = row[0], cy = row[1], w = row[2], h = row[3],
                                confidence = confidence,
                                imgWidth = imgWidth, imgHeight = imgHeight
                            )
                        )
                    }
                }

                84 -> {
                    // YOLOv8-style: [cx, cy, w, h, 80 class scores] (no separate objness)
                    val classScores = row.copyOfRange(4, 84)
                    val cls = classScores.indices.maxByOrNull { classScores[it] } ?: 0
                    val clsScore = classScores[cls]
                    if (cls != PERSON_CLASS) continue

                    val confidence = clsScore
                    if (confidence >= CONFIDENCE_THRESHOLD) {
                        detections.add(
                            toDetection(
                                cx = row[0], cy = row[1], w = row[2], h = row[3],
                                confidence = confidence,
                                imgWidth = imgWidth, imgHeight = imgHeight
                            )
                        )
                    }
                }

                else -> {
                    // Unknown format row, skip
                    continue
                }
            }
        }

        return nonMaxSuppression(detections)
    }
    private fun toDetection(
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        confidence: Float,
        imgWidth: Int,
        imgHeight: Int
    ): Detection {
        // Your math, unchanged — convert from model space to image space
        val sx = imgWidth / inputSize.toFloat()
        val sy = imgHeight / inputSize.toFloat()

        val cxPix = cx * sx
        val cyPix = cy * sy
        val wPix  = w  * sx
        val hPix  = h  * sy

        val left   = (cxPix - wPix / 2f).coerceAtLeast(0f)
        val top    = (cyPix - hPix / 2f).coerceAtLeast(0f)
        val right  = (cxPix + wPix / 2f).coerceAtMost(imgWidth.toFloat())
        val bottom = (cyPix + hPix / 2f).coerceAtMost(imgHeight.toFloat())

        return Detection(RectF(left, top, right, bottom), confidence, PERSON_CLASS)
    }


    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }
        val selected = mutableListOf<Detection>()

        for (detection in sorted) {
            var keep = true

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

    // FIXED: Safer ImageProxy to Bitmap conversion
    private fun safeImageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val planes = imageProxy.planes

            // Check if planes are valid
            if (planes.size < 3) {
                Timber.e("Invalid number of planes: ${planes.size}")
                return null
            }

            // Safely get buffers
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            // Check buffer validity
            if (!yBuffer.hasRemaining() || !uBuffer.hasRemaining() || !vBuffer.hasRemaining()) {
                Timber.e("One or more buffers are empty")
                return null
            }

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            // Create NV21 byte array
            val nv21 = ByteArray(ySize + uSize + vSize)

            // Copy data safely
            yBuffer.get(nv21, 0, ySize)
            val vOffset = ySize
            val uOffset = ySize + vSize
            vBuffer.get(nv21, vOffset, vSize)
            uBuffer.get(nv21, uOffset, uSize)

            // Convert to Bitmap
            val yuvImage = YuvImage(
                nv21,
                ImageFormat.NV21,
                imageProxy.width,
                imageProxy.height,
                null
            )

            val out = java.io.ByteArrayOutputStream()
            val rect = Rect(0, 0, imageProxy.width, imageProxy.height)

            if (!yuvImage.compressToJpeg(rect, 100, out)) {
                Timber.e("Failed to compress YUV to JPEG")
                return null
            }

            val imageBytes = out.toByteArray()
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        } catch (e: Exception) {
            Timber.e(e, "Error converting ImageProxy to Bitmap")
            null
        }
    }

    // Alternative simpler conversion method if the above still crashes
    @OptIn(ExperimentalGetImage::class)
    private fun imageProxyToBitmapSimple(imageProxy: ImageProxy): Bitmap? {
        return try {
            // Get the image from ImageProxy
            val image = imageProxy.image ?: return null

            val planes = image.planes
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]

            val ySize = yPlane.buffer.remaining()
            val uSize = uPlane.buffer.remaining()
            val vSize = vPlane.buffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yPlane.buffer.get(nv21, 0, ySize)
            val uvPixelStride = uPlane.pixelStride

            if (uvPixelStride == 1) {
                uPlane.buffer.get(nv21, ySize, uSize)
                vPlane.buffer.get(nv21, ySize + uSize, vSize)
            } else {
                // Interleaved UV
                val uvBuffer = ByteArray(uSize + vSize)
                vPlane.buffer.get(uvBuffer, 0, vSize)
                uPlane.buffer.get(uvBuffer, vSize, uSize)

                var nv21Index = ySize
                for (i in 0 until min(uSize, vSize)) {
                    nv21[nv21Index++] = uvBuffer[i * 2 + vSize]
                    nv21[nv21Index++] = uvBuffer[i * 2]
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)

            val imageBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        } catch (e: Exception) {
            Timber.e(e, "Error in simple conversion")
            null
        }
    }

    fun close() {
        try {
            interpreter?.close()
            gpuDelegate?.close()
            Timber.d("YOLO detector closed")
        } catch (e: Exception) {
            Timber.e(e, "Error closing YOLO detector")
        }
    }
}