// ByteTracker.kt
package com.datafy.foottraffic.tracking

import android.graphics.RectF
import com.datafy.foottraffic.detection.YoloDetector
import timber.log.Timber
import kotlin.math.*

class ByteTracker(
    private val trackThresh: Float = 0.5f,
    private val trackBuffer: Int = 30,
    private val matchThresh: Float = 0.8f
) {

    private var frameId = 0
    private val tracks = mutableMapOf<Int, Track>()
    private val lostTracks = mutableMapOf<Int, Track>()
    private var nextTrackId = 1

    data class Track(
        val trackId: Int,
        var bbox: RectF,
        var confidence: Float,
        var age: Int = 0,
        var timeSinceUpdate: Int = 0,
        val kalmanFilter: KalmanFilter = KalmanFilter()
    ) {
        val history = mutableListOf<RectF>()

        fun addToHistory(box: RectF) {
            history.add(box)
            if (history.size > 30) {
                history.removeAt(0)
            }
        }
    }

    fun update(detections: List<YoloDetector.Detection>): List<YoloDetector.Detection> {
        frameId++

        // Predict existing tracks
        tracks.values.forEach { track ->
            track.bbox = track.kalmanFilter.predict()
            track.timeSinceUpdate++
        }

        // Separate detections by confidence
        val highConfDetections = detections.filter { it.confidence >= trackThresh }
        val lowConfDetections = detections.filter { it.confidence < trackThresh }

        // Match high confidence detections to existing tracks
        val (matchedTracks, unmatchedTracks, unmatchedDetections) =
            associateDetectionsToTracks(tracks.values.toList(), highConfDetections)

        // Update matched tracks
        matchedTracks.forEach { (track, detection) ->
            track.bbox = detection.bbox
            track.confidence = detection.confidence
            track.kalmanFilter.update(detection.bbox)
            track.timeSinceUpdate = 0
            track.age++
            track.addToHistory(detection.bbox)
        }

        // Try to recover lost tracks with low confidence detections
        val remainingTracks = unmatchedTracks.toMutableList()
        if (lowConfDetections.isNotEmpty() && remainingTracks.isNotEmpty()) {
            val (recoveredTracks, stillLostTracks, _) =
                associateDetectionsToTracks(remainingTracks, lowConfDetections)

            recoveredTracks.forEach { (track, detection) ->
                track.bbox = detection.bbox
                track.confidence = detection.confidence
                track.kalmanFilter.update(detection.bbox)
                track.timeSinceUpdate = 0
                track.age++
                track.addToHistory(detection.bbox)
                remainingTracks.remove(track)
            }
        }

        // Create new tracks for unmatched high confidence detections
        unmatchedDetections.forEach { detection ->
            val newTrack = Track(
                trackId = nextTrackId++,
                bbox = detection.bbox,
                confidence = detection.confidence
            ).apply {
                kalmanFilter.initiate(detection.bbox)
                addToHistory(detection.bbox)
            }
            tracks[newTrack.trackId] = newTrack
        }

        // Handle lost tracks
        remainingTracks.forEach { track ->
            if (track.timeSinceUpdate > trackBuffer) {
                tracks.remove(track.trackId)
                Timber.d("Track ${track.trackId} removed after ${track.timeSinceUpdate} frames")
            }
        }

        // Return active tracks as detections
        return tracks.values.map { track ->
            YoloDetector.Detection(
                bbox = track.bbox,
                confidence = track.confidence,
                classId = 0,
                trackId = track.trackId
            )
        }
    }

    private fun associateDetectionsToTracks(
        trackList: List<Track>,
        detectionList: List<YoloDetector.Detection>
    ): Triple<List<Pair<Track, YoloDetector.Detection>>, List<Track>, List<YoloDetector.Detection>> {

        if (trackList.isEmpty() || detectionList.isEmpty()) {
            return Triple(emptyList(), trackList, detectionList)
        }

        // Build cost matrix based on IoU
        val costMatrix = Array(trackList.size) { i ->
            FloatArray(detectionList.size) { j ->
                1.0f - calculateIoU(trackList[i].bbox, detectionList[j].bbox)
            }
        }

        // Simple greedy matching (can be replaced with Hungarian algorithm for better results)
        val matched = mutableListOf<Pair<Track, YoloDetector.Detection>>()
        val usedTracks = mutableSetOf<Int>()
        val usedDetections = mutableSetOf<Int>()

        // Find best matches
        val matches = mutableListOf<Triple<Int, Int, Float>>()
        for (i in trackList.indices) {
            for (j in detectionList.indices) {
                if (costMatrix[i][j] < matchThresh) {
                    matches.add(Triple(i, j, costMatrix[i][j]))
                }
            }
        }

        // Sort by cost (best matches first)
        matches.sortBy { it.third }

        // Assign matches
        for ((trackIdx, detIdx, _) in matches) {
            if (trackIdx !in usedTracks && detIdx !in usedDetections) {
                matched.add(trackList[trackIdx] to detectionList[detIdx])
                usedTracks.add(trackIdx)
                usedDetections.add(detIdx)
            }
        }

        // Find unmatched
        val unmatchedTracks = trackList.filterIndexed { index, _ -> index !in usedTracks }
        val unmatchedDetections = detectionList.filterIndexed { index, _ -> index !in usedDetections }

        return Triple(matched, unmatchedTracks, unmatchedDetections)
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val x1 = max(box1.left, box2.left)
        val y1 = max(box1.top, box2.top)
        val x2 = min(box1.right, box2.right)
        val y2 = min(box1.bottom, box2.bottom)

        val intersectionArea = max(0f, x2 - x1) * max(0f, y2 - y1)
        val box1Area = box1.width() * box1.height()
        val box2Area = box2.width() * box2.height()
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    fun getTracks(): List<Track> = tracks.values.toList()

    fun reset() {
        tracks.clear()
        lostTracks.clear()
        frameId = 0
        nextTrackId = 1
    }
}

// KalmanFilter.kt
