package com.datafy.foottraffic.analysis

import java.util.concurrent.ConcurrentHashMap

class ZoneManager {

    private val zoneCounts = ConcurrentHashMap<String, Int>()
    private val zoneHistories = ConcurrentHashMap<String, MutableList<Int>>()

    fun updateZone(zoneId: String, count: Int) {
        zoneCounts[zoneId] = count

        // Keep history for averaging
        val history = zoneHistories.getOrPut(zoneId) { mutableListOf() }
        history.add(count)

        // Keep only last 30 values
        if (history.size > 30) {
            history.removeAt(0)
        }
    }

    fun getZoneCount(zoneId: String): Int {
        return zoneCounts[zoneId] ?: 0
    }

    fun getZoneAverage(zoneId: String): Float {
        val history = zoneHistories[zoneId] ?: return 0f
        return if (history.isNotEmpty()) {
            history.average().toFloat()
        } else {
            0f
        }
    }

    fun getAllZoneCounts(): Map<String, Int> {
        return zoneCounts.toMap()
    }

    fun reset() {
        zoneCounts.clear()
        zoneHistories.clear()
    }
}