// CrowdDensityEstimator.kt
package com.datafy.foottraffic.analysis

import android.graphics.Bitmap
import android.graphics.Color
import timber.log.Timber
import kotlin.math.*

/**
 * Estimates crowd density when individual tracking becomes impractical.
 * Uses multiple techniques: edge detection, texture analysis, and optical flow.
 */
class CrowdDensityEstimator {

    companion object {
        // Thresholds for mode switching
        const val CROWD_MODE_THRESHOLD = 20  // Switch to crowd mode above this count
        const val HIGH_DENSITY_THRESHOLD = 0.7f  // Density above this triggers crowd mode

        // Estimation parameters
        private const val GRID_SIZE = 32  // Divide image into 32x32 grid
        private const val MIN_EDGE_THRESHOLD = 50
        private const val TEXTURE_WINDOW_SIZE = 16

        // Calibration factors (adjust based on camera height and angle)
        private const val PIXELS_PER_PERSON_SPARSE = 5000f  // In sparse crowds
        private const val PIXELS_PER_PERSON_DENSE = 2500f   // In dense crowds
        private const val PIXELS_PER_PERSON_PACKED = 1500f  // In packed crowds
    }

    data class CrowdAnalysis(
        val estimatedCount: Int,
        val densityLevel: DensityLevel,
        val densityMap: Array<FloatArray>,  // Heatmap of crowd density
        val confidence: Float,  // Confidence in the estimate
        val isInCrowdMode: Boolean
    )

    enum class DensityLevel {
        EMPTY,      // 0 people
        SPARSE,     // 1-10 people/zone
        MODERATE,   // 10-30 people/zone
        DENSE,      // 30-50 people/zone
        PACKED      // 50+ people/zone
    }

    private var previousFrame: Bitmap? = null
    private val motionHistory = mutableListOf<Float>()

    /**
     * Main method to analyze crowd density
     */
    fun analyzeCrowdDensity(
        bitmap: Bitmap,
        detectedCount: Int = 0  // Count from YOLO if available
    ): CrowdAnalysis {

        // Quick check if we should use crowd mode
        val shouldUseCrowdMode = shouldEnterCrowdMode(bitmap, detectedCount)

        if (!shouldUseCrowdMode) {
            // Return normal mode with YOLO count
            return CrowdAnalysis(
                estimatedCount = detectedCount,
                densityLevel = getDensityLevel(detectedCount),
                densityMap = Array(GRID_SIZE) { FloatArray(GRID_SIZE) },
                confidence = 0.95f,
                isInCrowdMode = false
            )
        }

        Timber.d("Entering crowd mode analysis")

        // Perform multiple density estimations
        val edgeDensity = calculateEdgeDensity(bitmap)
        val textureDensity = calculateTextureDensity(bitmap)
        val motionDensity = calculateMotionDensity(bitmap)
        val foregroundRatio = calculateForegroundRatio(bitmap)

        // Create density heatmap
        val densityMap = createDensityMap(bitmap)

        // Combine all estimates with weighted average
        val combinedDensity = (
                edgeDensity * 0.3f +
                        textureDensity * 0.25f +
                        motionDensity * 0.2f +
                        foregroundRatio * 0.25f
                )

        // Convert density to people count
        val estimatedCount = estimateCountFromDensity(combinedDensity, bitmap)

        // Use YOLO count as minimum if available
        val finalCount = max(estimatedCount, detectedCount)

        // Calculate confidence based on consistency of methods
        val confidence = calculateConfidence(
            edgeDensity,
            textureDensity,
            motionDensity,
            foregroundRatio
        )

        return CrowdAnalysis(
            estimatedCount = finalCount,
            densityLevel = getDensityLevel(finalCount),
            densityMap = densityMap,
            confidence = confidence,
            isInCrowdMode = true
        )
    }

    /**
     * Determines if crowd mode should be activated
     */
    private fun shouldEnterCrowdMode(bitmap: Bitmap, detectedCount: Int): Boolean {
        // Enter crowd mode if:
        // 1. Detected count exceeds threshold
        if (detectedCount > CROWD_MODE_THRESHOLD) return true

        // 2. Image density is very high (many edges/textures)
        val quickDensity = calculateQuickDensity(bitmap)
        if (quickDensity > HIGH_DENSITY_THRESHOLD) return true

        return false
    }

    /**
     * Quick density calculation for mode switching
     */
    private fun calculateQuickDensity(bitmap: Bitmap): Float {
        // Sample the image at intervals for speed
        val sampleRate = 10
        var edgePixels = 0
        var totalSampled = 0

        for (y in 0 until bitmap.height step sampleRate) {
            for (x in 0 until bitmap.width step sampleRate) {
                val pixel = bitmap.getPixel(x, y)

                // Check neighboring pixels for edges
                if (x > 0 && y > 0) {
                    val leftPixel = bitmap.getPixel(x - sampleRate, y)
                    val topPixel = bitmap.getPixel(x, y - sampleRate)

                    val colorDiff = colorDifference(pixel, leftPixel) +
                            colorDifference(pixel, topPixel)

                    if (colorDiff > MIN_EDGE_THRESHOLD * 2) {
                        edgePixels++
                    }
                }
                totalSampled++
            }
        }

        return edgePixels.toFloat() / totalSampled
    }

    /**
     * Calculate density based on edge detection
     */
    private fun calculateEdgeDensity(bitmap: Bitmap): Float {
        var edgeCount = 0
        val width = bitmap.width
        val height = bitmap.height

        // Sobel edge detection
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val gx = sobelX(bitmap, x, y)
                val gy = sobelY(bitmap, x, y)
                val magnitude = sqrt(gx * gx + gy * gy)

                if (magnitude > MIN_EDGE_THRESHOLD) {
                    edgeCount++
                }
            }
        }

        // Normalize by image size
        val totalPixels = width * height
        return (edgeCount.toFloat() / totalPixels) * 10  // Scale factor
    }

    /**
     * Calculate density based on texture complexity
     */
    private fun calculateTextureDensity(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        var totalComplexity = 0f
        var windowCount = 0

        // Analyze texture in windows
        for (y in 0 until height - TEXTURE_WINDOW_SIZE step TEXTURE_WINDOW_SIZE) {
            for (x in 0 until width - TEXTURE_WINDOW_SIZE step TEXTURE_WINDOW_SIZE) {
                val complexity = calculateWindowComplexity(bitmap, x, y)
                totalComplexity += complexity
                windowCount++
            }
        }

        return if (windowCount > 0) {
            (totalComplexity / windowCount) * 2  // Scale factor
        } else {
            0f
        }
    }

    /**
     * Calculate texture complexity for a window
     */
    private fun calculateWindowComplexity(bitmap: Bitmap, startX: Int, startY: Int): Float {
        val pixels = mutableListOf<Int>()

        for (y in startY until min(startY + TEXTURE_WINDOW_SIZE, bitmap.height)) {
            for (x in startX until min(startX + TEXTURE_WINDOW_SIZE, bitmap.width)) {
                pixels.add(getGrayValue(bitmap.getPixel(x, y)))
            }
        }

        if (pixels.isEmpty()) return 0f

        // Calculate standard deviation as measure of complexity
        val mean = pixels.average()
        val variance = pixels.map { (it - mean) * (it - mean) }.average()

        return sqrt(variance).toFloat() / 128f  // Normalize
    }

    /**
     * Calculate density based on motion (if previous frame available)
     */
    private fun calculateMotionDensity(bitmap: Bitmap): Float {
        val prevFrame = previousFrame ?: run {
            previousFrame = bitmap
            return 0.5f  // Default value for first frame
        }

        var motionPixels = 0
        val sampleRate = 5  // Sample every 5th pixel for speed
        var totalSampled = 0

        for (y in 0 until bitmap.height step sampleRate) {
            for (x in 0 until bitmap.width step sampleRate) {
                val currentPixel = bitmap.getPixel(x, y)
                val previousPixel = prevFrame.getPixel(x, y)

                val diff = colorDifference(currentPixel, previousPixel)
                if (diff > 30) {  // Motion threshold
                    motionPixels++
                }
                totalSampled++
            }
        }

        previousFrame = bitmap

        val motionRatio = motionPixels.toFloat() / totalSampled
        motionHistory.add(motionRatio)

        // Keep only recent history
        if (motionHistory.size > 10) {
            motionHistory.removeAt(0)
        }

        // Average recent motion
        return motionHistory.average().toFloat() * 5  // Scale factor
    }

    /**
     * Calculate foreground/background ratio
     */
    private fun calculateForegroundRatio(bitmap: Bitmap): Float {
        // Simple background subtraction based on color
        // Assumes background is relatively uniform (floor, ground)

        val histogram = IntArray(256)

        // Build grayscale histogram
        for (y in 0 until bitmap.height step 5) {
            for (x in 0 until bitmap.width step 5) {
                val gray = getGrayValue(bitmap.getPixel(x, y))
                histogram[gray]++
            }
        }

        // Find dominant background color (mode)
        val backgroundGray = histogram.indices.maxByOrNull { histogram[it] } ?: 128

        // Count foreground pixels
        var foregroundPixels = 0
        var totalPixels = 0

        for (y in 0 until bitmap.height step 5) {
            for (x in 0 until bitmap.width step 5) {
                val gray = getGrayValue(bitmap.getPixel(x, y))
                if (abs(gray - backgroundGray) > 30) {
                    foregroundPixels++
                }
                totalPixels++
            }
        }

        return (foregroundPixels.toFloat() / totalPixels) * 3  // Scale factor
    }

    /**
     * Create a density heatmap grid
     */
    private fun createDensityMap(bitmap: Bitmap): Array<FloatArray> {
        val densityMap = Array(GRID_SIZE) { FloatArray(GRID_SIZE) }
        val cellWidth = bitmap.width / GRID_SIZE
        val cellHeight = bitmap.height / GRID_SIZE

        for (gridY in 0 until GRID_SIZE) {
            for (gridX in 0 until GRID_SIZE) {
                val startX = gridX * cellWidth
                val startY = gridY * cellHeight

                // Calculate density for this cell
                var cellDensity = 0f

                for (y in startY until min(startY + cellHeight, bitmap.height)) {
                    for (x in startX until min(startX + cellWidth, bitmap.width)) {
                        // Simple density based on non-background pixels
                        val pixel = bitmap.getPixel(x, y)
                        val gray = getGrayValue(pixel)

                        // Assume darker pixels are people (simplified)
                        if (gray < 100) {
                            cellDensity += 1f
                        }
                    }
                }

                // Normalize by cell size
                densityMap[gridY][gridX] = cellDensity / (cellWidth * cellHeight)
            }
        }

        return densityMap
    }

    /**
     * Convert density value to estimated people count
     */
    private fun estimateCountFromDensity(density: Float, bitmap: Bitmap): Int {
        val imageArea = bitmap.width * bitmap.height

        // Determine pixels per person based on density level
        val pixelsPerPerson = when {
            density < 0.3f -> PIXELS_PER_PERSON_SPARSE
            density < 0.6f -> PIXELS_PER_PERSON_DENSE
            else -> PIXELS_PER_PERSON_PACKED
        }

        // Calculate estimated count
        val baseCount = (imageArea * density / pixelsPerPerson).toInt()

        // Apply correction factors
        val correctedCount = (baseCount * getCorrectionFactor(density)).toInt()

        return max(1, correctedCount)  // At least 1 if any density detected
    }

    /**
     * Get correction factor based on density patterns
     */
    private fun getCorrectionFactor(density: Float): Float {
        // Empirically derived correction factors
        return when {
            density < 0.2f -> 1.2f   // Low density: slight overestimate
            density < 0.5f -> 1.0f   // Medium density: accurate
            density < 0.7f -> 0.9f   // High density: slight underestimate
            else -> 0.85f            // Very high: account for occlusion
        }
    }

    /**
     * Calculate confidence in the estimate
     */
    private fun calculateConfidence(vararg densities: Float): Float {
        // Calculate standard deviation of estimates
        val mean = densities.average()
        val variance = densities.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)

        // Lower standard deviation = higher confidence
        val confidence = 1f - min(stdDev.toFloat() / mean.toFloat(), 1f)

        return max(0.3f, min(0.9f, confidence))  // Clamp between 30% and 90%
    }

    /**
     * Helper: Sobel X operator
     */
    private fun sobelX(bitmap: Bitmap, x: Int, y: Int): Float {
        val kernel = arrayOf(
            intArrayOf(-1, 0, 1),
            intArrayOf(-2, 0, 2),
            intArrayOf(-1, 0, 1)
        )

        var sum = 0f
        for (dy in -1..1) {
            for (dx in -1..1) {
                val pixel = bitmap.getPixel(x + dx, y + dy)
                val gray = getGrayValue(pixel)
                sum += gray * kernel[dy + 1][dx + 1]
            }
        }

        return sum
    }

    /**
     * Helper: Sobel Y operator
     */
    private fun sobelY(bitmap: Bitmap, x: Int, y: Int): Float {
        val kernel = arrayOf(
            intArrayOf(-1, -2, -1),
            intArrayOf(0, 0, 0),
            intArrayOf(1, 2, 1)
        )

        var sum = 0f
        for (dy in -1..1) {
            for (dx in -1..1) {
                val pixel = bitmap.getPixel(x + dx, y + dy)
                val gray = getGrayValue(pixel)
                sum += gray * kernel[dy + 1][dx + 1]
            }
        }

        return sum
    }

    /**
     * Helper: Calculate color difference between two pixels
     */
    private fun colorDifference(pixel1: Int, pixel2: Int): Int {
        val r1 = Color.red(pixel1)
        val g1 = Color.green(pixel1)
        val b1 = Color.blue(pixel1)

        val r2 = Color.red(pixel2)
        val g2 = Color.green(pixel2)
        val b2 = Color.blue(pixel2)

        return abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2)
    }

    /**
     * Helper: Get grayscale value of pixel
     */
    private fun getGrayValue(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    /**
     * Get density level from count
     */
    private fun getDensityLevel(count: Int): DensityLevel {
        return when (count) {
            0 -> DensityLevel.EMPTY
            in 1..10 -> DensityLevel.SPARSE
            in 11..30 -> DensityLevel.MODERATE
            in 31..50 -> DensityLevel.DENSE
            else -> DensityLevel.PACKED
        }
    }
}