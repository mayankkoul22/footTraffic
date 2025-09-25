package com.datafy.foottraffic.data


import android.content.Context
import com.datafy.foottraffic.analysis.TrafficAnalyzer
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

class AnalyticsRepository(context: Context) {

    private val database = AnalyticsDatabase.getDatabase(context)
    private val dao = database.analyticsDao()
    private val gson = Gson()

    suspend fun saveAnalytics(data: TrafficAnalyzer.AnalyticsData) = withContext(Dispatchers.IO) {
        try {
            val entity = AnalyticsEntity(
                timestamp = data.timestamp,
                currentCount = data.currentCount,
                totalEntries = data.totalEntries,
                totalExits = data.totalExits,
                uniqueVisitors = data.uniqueVisitors,
                fps = data.fps,
                zonesJson = gson.toJson(data.zones)
            )

            dao.insertAnalytics(entity)

            // Update hourly and daily stats
            updateHourlyStats(entity)
            updateDailyStats(entity)

        } catch (e: Exception) {
            Timber.e(e, "Failed to save analytics")
        }
    }

    private suspend fun updateHourlyStats(entity: AnalyticsEntity) {
        val hourFormat = SimpleDateFormat("yyyy-MM-dd-HH", Locale.US)
        val hourKey = hourFormat.format(Date(entity.timestamp))

        // Get existing stats for this hour
        val hourStart = hourFormat.parse(hourKey)?.time ?: return
        val hourEnd = hourStart + 3600000 // Add 1 hour

        val hourlyData = dao.getAnalyticsBetweenSync(hourStart, hourEnd)

        if (hourlyData.isNotEmpty()) {
            val avgCount = hourlyData.map { it.currentCount }.average().toFloat()
            val maxCount = hourlyData.maxOf { it.currentCount }
            val totalEntries = hourlyData.last().totalEntries - hourlyData.first().totalEntries
            val totalExits = hourlyData.last().totalExits - hourlyData.first().totalExits
            val uniqueVisitors = hourlyData.maxOf { it.uniqueVisitors }

            dao.insertHourlyStats(
                HourlyStatsEntity(
                    hour = hourKey,
                    avgCount = avgCount,
                    maxCount = maxCount,
                    totalEntries = totalEntries,
                    totalExits = totalExits,
                    uniqueVisitors = uniqueVisitors
                )
            )
        }
    }

    private suspend fun updateDailyStats(entity: AnalyticsEntity) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dateKey = dateFormat.format(Date(entity.timestamp))

        // Get existing stats for this day
        val dayStart = dateFormat.parse(dateKey)?.time ?: return
        val dayEnd = dayStart + 86400000 // Add 1 day

        val dailyData = dao.getAnalyticsBetweenSync(dayStart, dayEnd)

        if (dailyData.isNotEmpty()) {
            val avgCount = dailyData.map { it.currentCount }.average().toFloat()
            val maxCount = dailyData.maxOf { it.currentCount }
            val totalEntries = dailyData.last().totalEntries - dailyData.first().totalEntries
            val totalExits = dailyData.last().totalExits - dailyData.first().totalExits
            val uniqueVisitors = dailyData.maxOf { it.uniqueVisitors }

            // Find peak hour
            val hourlyStats = dao.getHourlyStatsForDate(dateKey)
            val peakHour = hourlyStats.maxByOrNull { it.avgCount }?.hour?.split("-")?.last()?.toIntOrNull() ?: 0

            dao.insertDailyStats(
                DailyStatsEntity(
                    date = dateKey,
                    avgCount = avgCount,
                    maxCount = maxCount,
                    totalEntries = totalEntries,
                    totalExits = totalExits,
                    uniqueVisitors = uniqueVisitors,
                    peakHour = peakHour
                )
            )
        }
    }

    fun getAnalyticsBetween(start: Long, end: Long): Flow<List<AnalyticsEntity>> {
        return dao.getAnalyticsBetween(start, end)
    }

    suspend fun getLatestAnalytics(): AnalyticsEntity? {
        return dao.getLatestAnalytics()
    }

    suspend fun exportToCSV(startDate: Date, endDate: Date): String = withContext(Dispatchers.IO) {
        val entries = dao.getAnalyticsBetweenSync(startDate.time, endDate.time)
        val csv = StringBuilder()

        // CSV header
        csv.append("Timestamp,Current Count,Total Entries,Total Exits,Unique Visitors,FPS\n")

        // Add data rows
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        entries.forEach { entry ->
            csv.append("${dateFormat.format(Date(entry.timestamp))},")
            csv.append("${entry.currentCount},")
            csv.append("${entry.totalEntries},")
            csv.append("${entry.totalExits},")
            csv.append("${entry.uniqueVisitors},")
            csv.append("${entry.fps}\n")
        }

        csv.toString()
    }

    suspend fun cleanupOldData(daysToKeep: Int) = withContext(Dispatchers.IO) {
        val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
        dao.deleteAnalyticsBefore(cutoffTime)
        Timber.d("Cleaned up data older than $daysToKeep days")
    }
}