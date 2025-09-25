// CounterService.kt (drop-in)
package com.datafy.foottraffic.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.datafy.foottraffic.MainActivity
import com.datafy.foottraffic.R
import com.datafy.foottraffic.analysis.TrafficAnalyzer
import com.datafy.foottraffic.camera.CameraController
import com.datafy.foottraffic.web.WebServer
import kotlinx.coroutines.*
import timber.log.Timber

class CounterService : LifecycleService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cameraController: CameraController? = null
    private var trafficAnalyzer: TrafficAnalyzer? = null
    private var webServer: WebServer? = null

    companion object {
        const val CHANNEL_ID = "FootTrafficCounterChannel"
        const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("CounterService onCreate")

        // Must have CAMERA granted or we cannot legally run a camera FGS.
        if (!hasCameraPermission()) {
            Timber.w("CAMERA permission not granted; stopping service")
            stopSelf()
            return
        }

        createNotificationChannel()

        // Build the notification early so we can use it in startForeground
        val notification = buildOngoingNotification("Starting…")

        // Foreground start must succeed within 5s or the system will kill us.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (se: SecurityException) {
            // Happens if started from an ineligible state or missing FGS_CAMERA perm
            Timber.e(se, "startForeground denied (camera FGS)")
            stopSelf()
            return
        } catch (ise: IllegalStateException) {
            // If system thinks we're not in the right state to go foreground
            Timber.e(ise, "startForeground failed due to state")
            stopSelf()
            return
        }

        // Now safe to init the heavy stuff
        initializeComponents()

        isRunning = true
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Foot Traffic Counter Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when foot traffic counting is active"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildOngoingNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Foot Traffic Counter")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_camera) // ensure this exists; else use a system icon
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun initializeComponents() {
        serviceScope.launch {
            try {
                // Double-check permission just before touching the camera.
                if (!hasCameraPermission()) {
                    Timber.w("CAMERA permission revoked before init; stopping")
                    stopSelf()
                    return@launch
                }

                // Initialize subsystems
                cameraController = CameraController(applicationContext, this@CounterService)
                trafficAnalyzer = TrafficAnalyzer(applicationContext)
                webServer = WebServer(applicationContext, trafficAnalyzer!!)
                webServer?.start()

                // Start camera & processing
                try {
                    cameraController?.startCamera { imageProxy ->
                        // Guard again for runtime revocation mid-stream
                        if (!hasCameraPermission()) {
                            Timber.w("CAMERA permission revoked during streaming; stopping")
                            stopSelf()
                            return@startCamera
                        }
                        trafficAnalyzer?.processFrame(imageProxy)
                    }
                } catch (se: SecurityException) {
                    Timber.e(se, "SecurityException starting camera; stopping service")
                    stopSelf()
                    return@launch
                }

                // Update notification text once running
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(
                    NOTIFICATION_ID,
                    buildOngoingNotification("Monitoring active")
                )

                Timber.d("All components initialized successfully")

            } catch (t: Throwable) {
                Timber.e(t, "Failed to initialize components")
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Timber.d("CounterService onStartCommand")
        // Keep running — suitable for 24/7 use with ongoing notification.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Optional: schedule a gentle restart; OEM behavior may vary
        super.onTaskRemoved(rootIntent)
        // If you do a restart alarm here, ensure it only fires when app is foreground-able
        // to stay compliant with camera FGS policy.
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("CounterService onDestroy")

        isRunning = false

        serviceScope.launch {
            try {
                cameraController?.stopCamera()
                webServer?.stop()
                trafficAnalyzer?.cleanup()
            } catch (e: Exception) {
                Timber.e(e, "Error during cleanup")
            }
        }

        serviceScope.cancel()
        // Remove the ongoing notification
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
