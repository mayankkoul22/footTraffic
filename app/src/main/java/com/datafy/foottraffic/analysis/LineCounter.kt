package com.datafy.foottraffic.analysis

import android.graphics.PointF
import android.graphics.RectF
import java.util.concurrent.ConcurrentHashMap

class LineCounter {

    // Default counting line (horizontal across middle of frame)
    private var countingLine = Line(
        start = PointF(0f, 540f),
        end = PointF(1920f, 540f)
    )

    private val trackHistory = ConcurrentHashMap<Int, TrackData>()

    data class Line(
        val start: PointF,
        val end: PointF
    )

    data class TrackData(
        val trackId: Int,
        val positions: MutableList<PointF> = mutableListOf(),
        var hasCrossed: Boolean = false,
        var crossingDirection: CrossingDirection? = null
    )

    enum class CrossingDirection {
        ENTRY, EXIT
    }

    fun setCountingLine(start: PointF, end: PointF) {
        countingLine = Line(start, end)
        reset()
    }

    fun checkLineCrossing(trackId: Int, bbox: RectF): CrossingDirection? {
        val center = PointF(bbox.centerX(), bbox.centerY())

        val track = trackHistory.getOrPut(trackId) { TrackData(trackId) }
        track.positions.add(center)

        // Keep only recent positions
        if (track.positions.size > 10) {
            track.positions.removeAt(0)
        }

        // Need at least 2 positions to check crossing
        if (track.positions.size < 2) return null

        // Don't check if already crossed
        if (track.hasCrossed) return null

        val prevPos = track.positions[track.positions.size - 2]
        val currPos = track.positions.last()

        // Check if line was crossed
        if (isLineCrossed(prevPos, currPos, countingLine)) {
            track.hasCrossed = true

            // Determine direction based on Y-coordinate change
            val direction = if (currPos.y > prevPos.y) {
                CrossingDirection.EXIT
            } else {
                CrossingDirection.ENTRY
            }

            track.crossingDirection = direction
            return direction
        }

        return null
    }

    private fun isLineCrossed(p1: PointF, p2: PointF, line: Line): Boolean {
        // Calculate which side of the line each point is on
        val side1 = getLineSide(p1, line)
        val side2 = getLineSide(p2, line)

        // Points are on opposite sides if signs are different
        return side1 * side2 < 0
    }

    private fun getLineSide(point: PointF, line: Line): Float {
        // Calculate cross product to determine which side of line
        return (point.x - line.start.x) * (line.end.y - line.start.y) -
                (point.y - line.start.y) * (line.end.x - line.start.x)
    }

    fun reset() {
        trackHistory.clear()
    }

    fun removeOldTracks(activeTrackIds: Set<Int>) {
        trackHistory.keys.removeAll { it !in activeTrackIds }
    }
}