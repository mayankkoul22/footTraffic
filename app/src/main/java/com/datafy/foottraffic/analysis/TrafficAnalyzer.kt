// TrafficAnalyzer.kt - Fixed version
package com.datafy.foottraffic.analysis

import android.content.Context
import androidx.camera.core.ImageProxy
import com.datafy.foottraffic.data.AnalyticsRepository
import com.datafy.foottraffic.data.ConfigManager
import com.datafy.foottraffic.detection.YoloDetector
import com.datafy.foottraffic.tracking.ByteTracker
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TrafficAnalyzer(private val context: Context) {

    private val yoloDetector = YoloDetector(context)
    private val byteTracker = ByteTracker()
    private val lineCounter = LineCounter()
    private val zoneManager = ZoneManager()
    private val configManager = ConfigManager(context)
    private val analyticsRepository = AnalyticsRepository(context)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isProcessing = AtomicBoolean(false)

    // Metrics
    private val currentCount = AtomicInteger(0)
    private val totalEntries = AtomicInteger(0)
    private val totalExits = AtomicInteger(0)
    private val uniqueVisitors = AtomicInteger(0)

    private var lastProcessTime = System.currentTimeMillis()
    private var fps = 0f

    data class AnalyticsData(
        val currentCount: Int,
        val totalEntries: Int,
        val totalExits: Int,
        val uniqueVisitors: Int,
        val fps: Float,
        val zones: Map<String, ZoneData>,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class ZoneData(
        val name: String,
        val count: Int,
        val capacity: Int,
        val occupancyPercent: Float
    )

    fun processFrame(imageProxy: ImageProxy) {
        // Skip if already processing
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        scope.launch {
            try {
                val startTime = System.currentTimeMillis()

                // IMPORTANT: Process the frame before closing
                val detections = withContext(Dispatchers.IO) {
                    yoloDetector.detectPeople(imageProxy)
                }

                // Now we can close the ImageProxy after we're done with it
                imageProxy.close()

                // Process detections on Default dispatcher
                withContext(Dispatchers.Default) {
                    // Update tracker
                    val trackedDetections = byteTracker.update(detections)

                    // Update current count
                    currentCount.set(trackedDetections.size)

                    // Process each tracked detection
                    trackedDetections.forEach { detection ->
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

                    // Update zones
                    val zones = configManager.getZones()
                    zones.forEach { zone ->
                        val count = trackedDetections.count { detection ->
                            zone.contains(detection.bbox.centerX(), detection.bbox.centerY())
                        }
                        zoneManager.updateZone(zone.id, count)
                    }
                }

                // Calculate FPS
                val endTime = System.currentTimeMillis()
                val processingTime = endTime - startTime
                fps = if (processingTime > 0) 1000f / processingTime else 0f
                lastProcessTime = endTime

                // Log status periodically
                if (endTime % 5000 < 100) {
                    Timber.d("Count: ${currentCount.get()}, FPS: ${"%.1f".format(fps)}, Processing: ${processingTime}ms")
                }

                // Save analytics periodically
                if (endTime - lastAnalyticsSave > 1000) {
                    saveAnalytics()
                    lastAnalyticsSave = endTime
                }

            } catch (e: Exception) {
                Timber.e(e, "Error processing frame")
                try {
                    imageProxy.close()
                } catch (closeError: Exception) {
                    Timber.e(closeError, "Error closing ImageProxy after exception")
                }
            } finally {
                isProcessing.set(false)
            }
        }
    }

    private var lastAnalyticsSave = System.currentTimeMillis()

    private fun saveAnalytics() {
        scope.launch {
            try {
                val analytics = getAnalytics()
                analyticsRepository.saveAnalytics(analytics)
            } catch (e: Exception) {
                Timber.e(e, "Error saving analytics")
            }
        }
    }

    fun getAnalytics(): AnalyticsData {
        val zones = try {
            configManager.getZones().associate { zone ->
                zone.id to ZoneData(
                    name = zone.name,
                    count = zoneManager.getZoneCount(zone.id),
                    capacity = zone.capacity,
                    occupancyPercent = (zoneManager.getZoneCount(zone.id).toFloat() / zone.capacity) * 100
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting zone data")
            emptyMap()
        }

        return AnalyticsData(
            currentCount = currentCount.get(),
            totalEntries = totalEntries.get(),
            totalExits = totalExits.get(),
            uniqueVisitors = uniqueVisitors.get(),
            fps = fps,
            zones = zones
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
    }

    fun cleanup() {
        scope.cancel()
        yoloDetector.close()
    }
}