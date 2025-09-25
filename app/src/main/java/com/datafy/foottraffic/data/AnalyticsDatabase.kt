// AnalyticsDatabase.kt
package com.datafy.foottraffic.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Entity(tableName = "analytics")
data class AnalyticsEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val currentCount: Int,
    val totalEntries: Int,
    val totalExits: Int,
    val uniqueVisitors: Int,
    val fps: Float,
    val zonesJson: String // JSON string of zone data
)

@Entity(tableName = "hourly_stats")
data class HourlyStatsEntity(
    @PrimaryKey
    val hour: String, // Format: "yyyy-MM-dd-HH"
    val avgCount: Float,
    val maxCount: Int,
    val totalEntries: Int,
    val totalExits: Int,
    val uniqueVisitors: Int
)

@Entity(tableName = "daily_stats")
data class DailyStatsEntity(
    @PrimaryKey
    val date: String, // Format: "yyyy-MM-dd"
    val avgCount: Float,
    val maxCount: Int,
    val totalEntries: Int,
    val totalExits: Int,
    val uniqueVisitors: Int,
    val peakHour: Int
)

@Dao
interface AnalyticsDao {

    @Insert
    suspend fun insertAnalytics(analytics: AnalyticsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHourlyStats(stats: HourlyStatsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyStats(stats: DailyStatsEntity)

    @Query("SELECT * FROM analytics WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    fun getAnalyticsBetween(start: Long, end: Long): Flow<List<AnalyticsEntity>>

    @Query("SELECT * FROM analytics WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    suspend fun getAnalyticsBetweenSync(start: Long, end: Long): List<AnalyticsEntity>

    @Query("SELECT * FROM hourly_stats WHERE hour LIKE :date || '%' ORDER BY hour")
    suspend fun getHourlyStatsForDate(date: String): List<HourlyStatsEntity>

    @Query("SELECT * FROM daily_stats WHERE date BETWEEN :start AND :end ORDER BY date")
    suspend fun getDailyStatsBetween(start: String, end: String): List<DailyStatsEntity>

    @Query("DELETE FROM analytics WHERE timestamp < :before")
    suspend fun deleteAnalyticsBefore(before: Long)

    @Query("SELECT AVG(currentCount) FROM analytics WHERE timestamp > :since")
    suspend fun getAverageCountSince(since: Long): Float?

    @Query("SELECT MAX(currentCount) FROM analytics WHERE timestamp > :since")
    suspend fun getMaxCountSince(since: Long): Int?

    @Query("SELECT * FROM analytics ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestAnalytics(): AnalyticsEntity?
}

class DateConverters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }
}

@Database(
    entities = [AnalyticsEntity::class, HourlyStatsEntity::class, DailyStatsEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(DateConverters::class)
abstract class AnalyticsDatabase : RoomDatabase() {

    abstract fun analyticsDao(): AnalyticsDao

    companion object {
        @Volatile
        private var INSTANCE: AnalyticsDatabase? = null

        fun getDatabase(context: Context): AnalyticsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AnalyticsDatabase::class.java,
                    "foottraffic_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

