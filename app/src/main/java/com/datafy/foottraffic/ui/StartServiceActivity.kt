package com.datafy.foottraffic.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.datafy.foottraffic.service.CounterService

/**
 * No-UI activity launched from a notification (e.g., after boot).
 * Because it is foreground & user-initiated, it's allowed to start the camera FGS.
 * It requests CAMERA if needed, starts the service, then finishes immediately.
 */
class StartServiceActivity : ComponentActivity() {

    private val reqCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startServiceAndFinish()
            else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // (Optional) Ask for POST_NOTIFICATIONS on API 33+ so your FGS notif is visible
        if (Build.VERSION.SDK_INT >= 33) {
            val reqNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
            val notifPerm = "android.permission.POST_NOTIFICATIONS"
            if (ContextCompat.checkSelfPermission(this, notifPerm) != PackageManager.PERMISSION_GRANTED) {
                reqNotif.launch(notifPerm)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            reqCamera.launch(Manifest.permission.CAMERA)
        } else {
            startServiceAndFinish()
        }
    }

    // StartServiceActivity.kt
    private fun startServiceAndFinish() {
        // 1) CAMERA permission must be granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
            return
        }

        // 2) (API 33+) optionally guard posting notifications
        if (Build.VERSION.SDK_INT >= 33) {
            val notifPerm = "android.permission.POST_NOTIFICATIONS"
            if (ContextCompat.checkSelfPermission(this, notifPerm)
                != PackageManager.PERMISSION_GRANTED
            ) {
                // Don’t crash—just continue. The FGS can still run; the banner might not show.
                // Or prompt the user to grant it in settings if you prefer.
            }
        }

        try {
            val intent = Intent(this, CounterService::class.java)
            ContextCompat.startForegroundService(this, intent)
        } catch (se: SecurityException) {
            Toast.makeText(this, "Not allowed to start camera service right now", Toast.LENGTH_LONG).show()
            // Consider opening app settings if this happens repeatedly.
        } finally {
            finish()
        }
    }

}
