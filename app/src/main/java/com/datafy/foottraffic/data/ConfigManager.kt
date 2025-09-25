// ConfigManager.kt
package com.datafy.foottraffic.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PointF
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import timber.log.Timber

class ConfigManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "FootTrafficConfig",
        Context.MODE_PRIVATE
    )
    private val gson = Gson()

    companion object {
        private const val KEY_ZONES = "zones"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_CALIBRATION = "calibration"
        private const val KEY_COUNTING_LINE = "counting_line"
    }

    data class Zone(
        val id: String,
        val name: String,
        val points: List<PointF>,
        val capacity: Int = 50,
        val type: ZoneType = ZoneType.COUNTING
    ) {
        fun contains(x: Float, y: Float): Boolean {
            // Point-in-polygon test using ray casting algorithm
            var inside = false
            val p1x = points.first().x
            val p1y = points.first().y

            for (i in 1..points.size) {
                val p2x = points[i % points.size].x
                val p2y = points[i % points.size].y

                if (y > minOf(p1y, p2y)) {
                    if (y <= maxOf(p1y, p2y)) {
                        if (x <= maxOf(p1x, p2x)) {
                            val xinters = if (p1y != p2y) {
                                (y - p1y) * (p2x - p1x) / (p2y - p1y) + p1x
                            } else {
                                p1x
                            }
                            if (p1x == p2x || x <= xinters) {
                                inside = !inside
                            }
                        }
                    }
                }
            }

            return inside
        }
    }

    enum class ZoneType {
        COUNTING, ENTRY, EXIT, EXCLUSION
    }

    data class Settings(
        val confidenceThreshold: Float = 0.45f,
        val iouThreshold: Float = 0.5f,
        val trackingThreshold: Float = 0.3f,
        val maxTrackAge: Int = 30,
        val webServerPort: Int = 8080,
        val dataRetentionDays: Int = 30,
        val enableGpuAcceleration: Boolean = true,
        val frameSkip: Int = 0  // Process every Nth frame (0 = no skip)
    )

    data class CalibrationData(
        val cameraHeight: Float = 2.5f,
        val cameraAngle: Float = 45f,
        val pixelsPerMeter: Float = 100f,
        val calibrationDate: Long = System.currentTimeMillis()
    )

    data class CountingLine(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float
    )

    // Zone management
    fun saveZone(zone: Zone) {
        val zones = getZones().toMutableList()
        zones.removeAll { it.id == zone.id }
        zones.add(zone)

        val json = gson.toJson(zones)
        prefs.edit().putString(KEY_ZONES, json).apply()

        Timber.d("Zone saved: ${zone.name}")
    }

    fun getZones(): List<Zone> {
        val json = prefs.getString(KEY_ZONES, null) ?: return getDefaultZones()

        return try {
            val type = object : TypeToken<List<Zone>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load zones")
            getDefaultZones()
        }
    }

    private fun getDefaultZones(): List<Zone> {
        // Default zone covering entire frame
        return listOf(
            Zone(
                id = "default",
                name = "Main Area",
                points = listOf(
                    PointF(100f, 100f),
                    PointF(1820f, 100f),
                    PointF(1820f, 980f),
                    PointF(100f, 980f)
                ),
                capacity = 100,
                type = ZoneType.COUNTING
            )
        )
    }

    fun deleteZone(zoneId: String) {
        val zones = getZones().toMutableList()
        zones.removeAll { it.id == zoneId }

        val json = gson.toJson(zones)
        prefs.edit().putString(KEY_ZONES, json).apply()
    }

    // Settings management
    fun saveSettings(settings: Settings) {
        val json = gson.toJson(settings)
        prefs.edit().putString(KEY_SETTINGS, json).apply()

        Timber.d("Settings saved")
    }

    fun getSettings(): Settings {
        val json = prefs.getString(KEY_SETTINGS, null) ?: return Settings()

        return try {
            gson.fromJson(json, Settings::class.java)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load settings")
            Settings()
        }
    }

    // Calibration management
    fun saveCalibration(calibration: CalibrationData) {
        val json = gson.toJson(calibration)
        prefs.edit().putString(KEY_CALIBRATION, json).apply()

        Timber.d("Calibration saved")
    }

    fun getCalibration(): CalibrationData {
        val json = prefs.getString(KEY_CALIBRATION, null) ?: return CalibrationData()

        return try {
            gson.fromJson(json, CalibrationData::class.java)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load calibration")
            CalibrationData()
        }
    }

    // Counting line management
    fun saveCountingLine(line: CountingLine) {
        val json = gson.toJson(line)
        prefs.edit().putString(KEY_COUNTING_LINE, json).apply()
    }

    fun getCountingLine(): CountingLine {
        val json = prefs.getString(KEY_COUNTING_LINE, null)
            ?: return CountingLine(0f, 540f, 1920f, 540f) // Default horizontal line

        return try {
            gson.fromJson(json, CountingLine::class.java)
        } catch (e: Exception){
            Timber.e(e, "Failed to load counting line, returning default.")
            //return default
            CountingLine(0f, 540f, 1920f, 540f)
        }
    }
}