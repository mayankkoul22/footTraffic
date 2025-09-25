// TrafficAnalyzer.kt - Updated with Crowd Mode
package com.datafy.foottraffic.analysis

import android.content.Context
import androidx.camera.core.ImageProxy
import com.datafy.foottraffic.camera.FrameProcessor
import com.datafy.foottraffic.data.AnalyticsRepository
import com.datafy.foottraffic.data.ConfigManager
import com.datafy.foottraffic.detection.YoloDetector
import com.datafy.foottraffic.tracking.ByteTracker
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

class TrafficAnalyzer(private val context: Context) {

    private val yoloDetector = YoloDetector(context)
    private val byteTracker = ByteTracker()
    private val lineCounter = LineCounter()
    private val zoneManager = ZoneManager()
    private val crowdEstimator = CrowdDensityEstimator()  // Add crowd estimator
    private val configManager = ConfigManager(context)
    private val analyticsRepository = AnalyticsRepository(context)
    private val frameProcessor = FrameProcessor()  // For bitmap conversion

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Metrics
    private val currentCount = AtomicInteger(0)
    private val totalEntries = AtomicInteger(0)
    private val totalExits = AtomicInteger(0)
    private val uniqueVisitors = AtomicInteger(0)

    // Performance tracking
    private var lastProcessTime = System.currentTimeMillis()
    private var fps = 0f
    private var isInCrowdMode = false
    private var crowdModeConfidence = 0f

    data class AnalyticsData(
        val currentCount: Int,
        val totalEntries: Int,
        val totalExits: Int,
        val uniqueVisitors: Int,
        val fps: Float,
        val zones: Map<String, ZoneData>,
        val isInCrowdMode: Boolean,
        val crowdModeConfidence: Float,
        val densityLevel: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class ZoneData(
        val name: String,
        val count: Int,
        val capacity: Int,
        val occupancyPercent: Float
    )

    fun processFrame(imageProxy: ImageProxy) {
        scope.launch {
            try {
                val startTime = System.currentTimeMillis()

                // Convert to bitmap
                val bitmap = frameProcessor.imageProxyToBitmap(imageProxy)

                // Run YOLO detection first
                val detections = yoloDetector.detectPeople(imageProxy)

                // Analyze crowd density
                val crowdAnalysis = crowdEstimator.analyzeCrowdDensity(bitmap, detections.size)

                // Update mode
                isInCrowdMode = crowdAnalysis.isInCrowdMode
                crowdModeConfidence = crowdAnalysis.confidence

                if (isInCrowdMode) {
                    // CROWD MODE: Use density estimation
                    Timber.d("CROWD MODE: Estimated ${crowdAnalysis.estimatedCount} people (${crowdAnalysis.densityLevel})")

                    currentCount.set(crowdAnalysis.estimatedCount)

                    // Still try to track individuals if possible for entry/exit
                    if (detections.size < 50) {  // Only track if not too many
                        val trackedDetections = byteTracker.update(detections)
                        processTrackedDetections(trackedDetections)
                    }

                    // Update zones based on density map
                    updateZonesFromDensityMap(crowdAnalysis.densityMap)

                } else {
                    // NORMAL MODE: Individual tracking
                    val trackedDetections = byteTracker.update(detections)

                    currentCount.set(trackedDetections.size)

                    // Process line counting and zones
                    processTrackedDetections(trackedDetections)

                    // Update zones normally
                    updateZonesFromTracking(trackedDetections)
                }

                // Calculate FPS
                val endTime = System.currentTimeMillis()
                fps = 1000f / (endTime - lastProcessTime)
                lastProcessTime = endTime

                // Log mode status periodically
                if (endTime % 5000 < 100) {  // Every 5 seconds
                    val mode = if (isInCrowdMode) "CROWD" else "NORMAL"
                    Timber.d("Mode: $mode, Count: ${currentCount.get()}, FPS: ${"%.1f".format(fps)}")
                }

                // Save analytics periodically
                if (endTime - lastAnalyticsSave > 1000) {
                    saveAnalytics()
                    lastAnalyticsSave = endTime
                }

            } catch (e: Exception) {
                Timber.e(e, "Error processing frame")
            } finally {
                imageProxy.close()
            }
        }
    }

    private fun processTrackedDetections(trackedDetections: List<YoloDetector.Detection>) {
        trackedDetections.forEach { detection ->
            // Check line crossing for entry/exit
            val crossing = lineCounter.checkLineCrossing(detection.trackId, detection.bbox)
            when (crossing) {
                LineCounter.CrossingDirection.ENTRY -> {
                    totalEntries.incrementAndGet()
                    uniqueVisitors.incrementAndGet()
                    Timber.d("Entry detected: Track ${detection.trackId}")
                }
                LineCounter.CrossingDirection.EXIT -> {
                    totalExits.incrementAndGet()
                    Timber.d("Exit detected: Track ${detection.trackId}")
                }
                null -> { /* No crossing */ }
            }
        }
    }

    private fun updateZonesFromTracking(trackedDetections: List<YoloDetector.Detection>) {
        val zones = configManager.getZones()
        zones.forEach { zone ->
            val count = trackedDetections.count { detection ->
                zone.contains(detection.bbox.centerX(), detection.bbox.centerY())
            }
            zoneManager.updateZone(zone.id, count)
        }
    }

    private fun updateZonesFromDensityMap(densityMap: Array<FloatArray>) {
        // Convert density map to zone counts
        val zones = configManager.getZones()
        zones.forEach { zone ->
            // Estimate count for this zone based on density
            val zoneCount = estimateZoneCountFromDensity(zone, densityMap)
            zoneManager.updateZone(zone.id, zoneCount)
        }
    }

    private fun estimateZoneCountFromDensity(
        zone: ConfigManager.Zone,
        densityMap: Array<FloatArray>
    ): Int {
        // Map zone coordinates to density grid
        val gridSize = densityMap.size
        var totalDensity = 0f
        var cellCount = 0

        // Sample density within zone bounds
        for (y in 0 until gridSize) {
            for (x in 0 until gridSize) {
                // Convert grid coordinates to image coordinates
                val imgX = (x.toFloat() / gridSize) * 1920
                val imgY = (y.toFloat() / gridSize) * 1080

                if (zone.contains(imgX, imgY)) {
                    totalDensity += densityMap[y][x]
                    cellCount++
                }
            }
        }

        // Convert density to count
        return if (cellCount > 0) {
            (totalDensity * zone.capacity * 0.5f).toInt()  // Estimation factor
        } else {
            0
        }
    }

    private var lastAnalyticsSave = System.currentTimeMillis()

    private fun saveAnalytics() {
        scope.launch {
            val analytics = getAnalytics()
            analyticsRepository.saveAnalytics(analytics)
        }
    }

    fun getAnalytics(): AnalyticsData {
        val zones = configManager.getZones().associate { zone ->
            zone.id to ZoneData(
                name = zone.name,
                count = zoneManager.getZoneCount(zone.id),
                capacity = zone.capacity,
                occupancyPercent = (zoneManager.getZoneCount(zone.id).toFloat() / zone.capacity) * 100
            )
        }

        val densityLevel = when (currentCount.get()) {
            0 -> "Empty"
            in 1..10 -> "Sparse"
            in 11..30 -> "Moderate"
            in 31..50 -> "Dense"
            else -> "Packed"
        }

        return AnalyticsData(
            currentCount = currentCount.get(),
            totalEntries = totalEntries.get(),
            totalExits = totalExits.get(),
            uniqueVisitors = uniqueVisitors.get(),
            fps = fps,
            zones = zones,
            isInCrowdMode = isInCrowdMode,
            crowdModeConfidence = crowdModeConfidence,
            densityLevel = densityLevel
        )
    }

    fun resetCounters() {
        currentCount.set(0)
        totalEntries.set(0)
        totalExits.set(0)
        uniqueVisitors.set(0)
        byteTracker.reset()
        lineCounter.reset()
        zoneManager.reset()
        isInCrowdMode = false
    }

    fun cleanup() {
        scope.cancel()
        yoloDetector.close()
    }
}

// FrameProcessor.kt - Helper for image conversion
