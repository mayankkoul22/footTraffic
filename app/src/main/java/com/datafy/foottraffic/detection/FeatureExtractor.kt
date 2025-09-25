package com.datafy.foottraffic.detection


import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.sqrt

class FeatureExtractor {

    companion object {
        private const val FEATURE_DIM = 128
        private const val SIMILARITY_THRESHOLD = 0.7f
    }

    data class PersonFeature(
        val featureVector: FloatArray,
        val timestamp: Long,
        val trackId: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PersonFeature) return false
            return featureVector.contentEquals(other.featureVector) &&
                    timestamp == other.timestamp &&
                    trackId == other.trackId
        }

        override fun hashCode(): Int {
            var result = featureVector.contentHashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + trackId
            return result
        }
    }

    fun extractFeatures(bitmap: Bitmap, bbox: RectF): FloatArray {
        // Extract person crop
        val cropBitmap = cropBitmap(bitmap, bbox)

        // Extract color histogram features
        val colorFeatures = extractColorHistogram(cropBitmap)

        // Extract texture features
        val textureFeatures = extractTextureFeatures(cropBitmap)

        // Extract shape features
        val shapeFeatures = extractShapeFeatures(bbox)

        // Combine all features
        return combineFeatures(colorFeatures, textureFeatures, shapeFeatures)
    }

    private fun cropBitmap(bitmap: Bitmap, bbox: RectF): Bitmap {
        val left = bbox.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = bbox.top.toInt().coerceIn(0, bitmap.height - 1)
        val width = (bbox.width().toInt()).coerceIn(1, bitmap.width - left)
        val height = (bbox.height().toInt()).coerceIn(1, bitmap.height - top)

        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    private fun extractColorHistogram(bitmap: Bitmap): FloatArray {
        val histogram = FloatArray(48) // 16 bins per channel (RGB)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (pixel in pixels) {
            val r = (pixel shr 16 and 0xFF) / 16
            val g = (pixel shr 8 and 0xFF) / 16
            val b = (pixel and 0xFF) / 16

            histogram[r]++
            histogram[16 + g]++
            histogram[32 + b]++
        }

        // Normalize
        val sum = histogram.sum()
        if (sum > 0) {
            for (i in histogram.indices) {
                histogram[i] /= sum
            }
        }

        return histogram
    }

    private fun extractTextureFeatures(bitmap: Bitmap): FloatArray {
        val features = FloatArray(32)
        val grayBitmap = toGrayscale(bitmap)

        // Calculate Local Binary Pattern (LBP) histogram
        val lbpHistogram = calculateLBP(grayBitmap)
        System.arraycopy(lbpHistogram, 0, features, 0, min(lbpHistogram.size, 32))

        return features
    }

    private fun toGrayscale(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val grayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16 and 0xFF)
                val g = (pixel shr 8 and 0xFF)
                val b = (pixel and 0xFF)
                val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                val grayPixel = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                grayBitmap.setPixel(x, y, grayPixel)
            }
        }

        return grayBitmap
    }

    private fun calculateLBP(bitmap: Bitmap): FloatArray {
        val histogram = FloatArray(256)
        val width = bitmap.width
        val height = bitmap.height

        for (x in 1 until width - 1) {
            for (y in 1 until height - 1) {
                val center = bitmap.getPixel(x, y) and 0xFF
                var lbpValue = 0

                // 8 neighbors
                val neighbors = intArrayOf(
                    bitmap.getPixel(x-1, y-1) and 0xFF,
                    bitmap.getPixel(x, y-1) and 0xFF,
                    bitmap.getPixel(x+1, y-1) and 0xFF,
                    bitmap.getPixel(x+1, y) and 0xFF,
                    bitmap.getPixel(x+1, y+1) and 0xFF,
                    bitmap.getPixel(x, y+1) and 0xFF,
                    bitmap.getPixel(x-1, y+1) and 0xFF,
                    bitmap.getPixel(x-1, y) and 0xFF
                )

                for (i in neighbors.indices) {
                    if (neighbors[i] >= center) {
                        lbpValue = lbpValue or (1 shl i)
                    }
                }

                histogram[lbpValue]++
            }
        }

        // Normalize
        val sum = histogram.sum()
        if (sum > 0) {
            for (i in histogram.indices) {
                histogram[i] /= sum
            }
        }

        return histogram.sliceArray(0..31) // Return first 32 bins
    }

    private fun extractShapeFeatures(bbox: RectF): FloatArray {
        return floatArrayOf(
            bbox.width() / bbox.height(), // Aspect ratio
            bbox.width(),
            bbox.height(),
            bbox.centerX(),
            bbox.centerY()
        )
    }

    private fun combineFeatures(vararg features: FloatArray): FloatArray {
        val combined = FloatArray(FEATURE_DIM)
        var offset = 0

        for (feature in features) {
            val copySize = minOf(feature.size, combined.size - offset)
            System.arraycopy(feature, 0, combined, offset, copySize)
            offset += copySize
            if (offset >= combined.size) break
        }

        // Normalize combined features
        val magnitude = sqrt(combined.sumOf { it * it.toDouble() }).toFloat()
        if (magnitude > 0) {
            for (i in combined.indices) {
                combined[i] /= magnitude
            }
        }

        return combined
    }

    fun calculateSimilarity(features1: FloatArray, features2: FloatArray): Float {
        if (features1.size != features2.size) return 0f

        // Cosine similarity
        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f

        for (i in features1.indices) {
            dotProduct += features1[i] * features2[i]
            norm1 += features1[i] * features1[i]
            norm2 += features2[i] * features2[i]
        }

        return if (norm1 > 0 && norm2 > 0) {
            dotProduct / (sqrt(norm1) * sqrt(norm2))
        } else {
            0f
        }
    }

    fun isMatch(features1: FloatArray, features2: FloatArray): Boolean {
        return calculateSimilarity(features1, features2) > SIMILARITY_THRESHOLD
    }
}

private fun min(a: Int, b: Int): Int = if (a < b) a else b