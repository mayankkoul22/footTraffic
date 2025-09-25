package com.datafy.foottraffic.tracking

import android.graphics.RectF

class KalmanFilter {

    // State: [cx, cy, width, height, vx, vy]
    private var cx = 0f
    private var cy = 0f
    private var width = 0f
    private var height = 0f
    private var vx = 0f
    private var vy = 0f

    // Process and measurement noise
    private val processNoise = 0.01f
    private val measurementNoise = 0.1f

    fun initiate(bbox: RectF) {
        cx = bbox.centerX()
        cy = bbox.centerY()
        width = bbox.width()
        height = bbox.height()
        vx = 0f
        vy = 0f
    }

    fun predict(): RectF {
        // Simple constant velocity model
        cx += vx
        cy += vy

        return RectF(
            cx - width / 2,
            cy - height / 2,
            cx + width / 2,
            cy + height / 2
        )
    }

    fun update(measurement: RectF) {
        val measuredCx = measurement.centerX()
        val measuredCy = measurement.centerY()

        // Update velocity estimate
        vx = (measuredCx - cx) * 0.5f + vx * 0.5f
        vy = (measuredCy - cy) * 0.5f + vy * 0.5f

        // Update position
        cx = measuredCx
        cy = measuredCy
        width = measurement.width()
        height = measurement.height()
    }
}

