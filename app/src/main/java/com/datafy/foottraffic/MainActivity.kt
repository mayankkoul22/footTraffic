// MainActivity.kt - Fixed version with proper permission handling
package com.datafy.foottraffic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.datafy.foottraffic.service.CounterService
import com.datafy.foottraffic.ui.CalibrationActivity
import com.datafy.foottraffic.utils.NetworkUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    // View references
    private lateinit var tvTitle: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvIpAddress: TextView
    private lateinit var btnStartService: Button
    private lateinit var btnCalibrate: Button
    private lateinit var btnMinimize: Button

    private var wakeLock: PowerManager.WakeLock? = null

    // Permission launcher with better error handling
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        handlePermissionResults(permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        initializeViews()

        // Keep screen on for monitoring
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Acquire wake lock for continuous operation
        acquireWakeLock()

        // Setup UI
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        // Check permissions when app resumes
        checkAndRequestPermissions()
        // Update service status
        updateServiceStatus(CounterService.isRunning)
    }

    private fun initializeViews() {
        tvTitle = findViewById(R.id.tvTitle)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        btnStartService = findViewById(R.id.btnStartService)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnMinimize = findViewById(R.id.btnMinimize)
    }

    private fun setupUI() {
        // Display device IP address for web interface
        val ipAddress = NetworkUtils.getDeviceIpAddress(this)
        tvIpAddress.text = "Web Interface: http://$ipAddress:8080"

        // Update service status display
        updateServiceStatus(CounterService.isRunning)

        // Start/Stop service button - check permissions first
        btnStartService.setOnClickListener {
            if (checkAllPermissions()) {
                if (CounterService.isRunning) {
                    stopCounterService()
                } else {
                    startCounterService()
                }
            } else {
                checkAndRequestPermissions()
            }
        }

        // Calibration button - check camera permission first
        btnCalibrate.setOnClickListener {
            if (checkCameraPermission()) {
                val intent = Intent(this, CalibrationActivity::class.java)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Camera permission required for calibration", Toast.LENGTH_SHORT).show()
                checkAndRequestPermissions()
            }
        }

        // Minimize button
        btnMinimize.setOnClickListener {
            moveTaskToBack(true)
            Toast.makeText(this, "App minimized. Access web interface at http://$ipAddress:8080", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAllPermissions(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        Timber.d("Permission check - Camera: $cameraGranted, Notifications: $notificationGranted")

        return cameraGranted && notificationGranted
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }

        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            Timber.d("Requesting permissions: $permissionsToRequest")

            // Show explanation dialog before requesting
            AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("This app needs:\n\n" +
                        "• Camera permission to detect and count people\n" +
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "• Notification permission to show service status\n" else "" +
                                "\nPlease grant these permissions to use the app.")
                .setPositiveButton("Grant Permissions") { _, _ ->
                    permissionLauncher.launch(permissionsToRequest.toTypedArray())
                }
                .setNegativeButton("Cancel") { _, _ ->
                    Toast.makeText(this, "Cannot start without required permissions", Toast.LENGTH_LONG).show()
                }
                .show()
        } else {
            // All permissions granted
            Timber.d("All permissions already granted")
            if (!CounterService.isRunning) {
                // Auto-start service if permissions are granted
                startCounterService()
            }
        }
    }

    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        val allGranted = permissions.all { it.value }

        Timber.d("Permission results: $permissions")

        if (allGranted) {
            Toast.makeText(this, "Permissions granted! Starting service...", Toast.LENGTH_SHORT).show()
            startCounterService()
        } else {
            // Check which specific permission was denied
            val deniedPermissions = permissions.filter { !it.value }.keys

            var message = "The following permissions were denied:\n"
            deniedPermissions.forEach { permission ->
                message += when (permission) {
                    Manifest.permission.CAMERA -> "\n• Camera - Required for people detection"
                    Manifest.permission.POST_NOTIFICATIONS -> "\n• Notifications - Required for service status"
                    else -> "\n• $permission"
                }
            }

            message += "\n\nThe app cannot function without these permissions. Please grant them in Settings."

            AlertDialog.Builder(this)
                .setTitle("Permissions Denied")
                .setMessage(message)
                .setPositiveButton("Open Settings") { _, _ ->
                    // Open app settings
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = android.net.Uri.fromParts("package", packageName, null)
                    startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startCounterService() {
        // Double-check permissions before starting
        if (!checkAllPermissions()) {
            Toast.makeText(this, "Cannot start service without required permissions", Toast.LENGTH_LONG).show()
            checkAndRequestPermissions()
            return
        }

        Timber.d("Starting CounterService")
        val serviceIntent = Intent(this, CounterService::class.java)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            updateServiceStatus(true)

            // Auto-minimize after starting service
            lifecycleScope.launch {
                delay(2000)
                moveTaskToBack(true)
                val ipAddress = NetworkUtils.getDeviceIpAddress(this@MainActivity)
                Toast.makeText(
                    this@MainActivity,
                    "Service running. Web interface: http://$ipAddress:8080",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start service")
            Toast.makeText(this, "Failed to start service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopCounterService() {
        Timber.d("Stopping CounterService")
        val serviceIntent = Intent(this, CounterService::class.java)
        stopService(serviceIntent)
        updateServiceStatus(false)
    }

    private fun updateServiceStatus(isRunning: Boolean) {
        tvServiceStatus.text = if (isRunning) "Service: Running" else "Service: Stopped"
        tvServiceStatus.setTextColor(
            ContextCompat.getColor(this, if (isRunning) R.color.green else R.color.red)
        )
        btnStartService.text = if (isRunning) "Stop Service" else "Start Service"
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FootTrafficCounter::WakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes with timeout
        }
        Timber.d("Wake lock acquired")
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Timber.d("Wake lock released")
            }
        }
    }
}