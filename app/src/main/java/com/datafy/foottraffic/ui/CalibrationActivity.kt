package com.datafy.foottraffic.ui

import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.datafy.foottraffic.R
import com.datafy.foottraffic.data.ConfigManager
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.*

class CalibrationActivity : AppCompatActivity() {

    // View references
    private lateinit var cameraPreview: PreviewView
    private lateinit var overlayView: View
    private lateinit var tvInstructions: TextView
    private lateinit var btnNext: Button
    private lateinit var btnSave: Button

    private lateinit var configManager: ConfigManager
    private var cameraProvider: ProcessCameraProvider? = null

    // Calibration state
    private var currentStep = CalibrationStep.DRAW_LINE
    private val calibrationPoints = mutableListOf<PointF>()
    private val zonePoints = mutableListOf<PointF>()
    private var drawnZones = mutableListOf<ConfigManager.Zone>()

    enum class CalibrationStep {
        DRAW_LINE,      // Draw counting line for entry/exit
        DRAW_ZONES,     // Draw detection zones
        COMPLETE        // Review and save
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        configManager = ConfigManager(this)

        initializeViews()
        setupCamera()
        setupUI()
        updateInstructions()
    }

    private fun initializeViews() {
        cameraPreview = findViewById(R.id.cameraPreview)
        overlayView = findViewById(R.id.overlayView)
        tvInstructions = findViewById(R.id.tvInstructions)
        btnNext = findViewById(R.id.btnNext)
        btnSave = findViewById(R.id.btnSave)
    }

    private fun setupCamera() {
        lifecycleScope.launch {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this@CalibrationActivity)

            cameraProviderFuture.addListener({
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(this@CalibrationActivity))
        }
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(cameraPreview.surfaceProvider)

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            Timber.d("Camera preview started for calibration")
        } catch (e: Exception) {
            Timber.e(e, "Use case binding failed")
            Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupUI() {
        // Touch handler for drawing on overlay
        overlayView.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }

        // Next button action
        btnNext.setOnClickListener {
            nextStep()
        }

        // Save button action
        btnSave.setOnClickListener {
            saveCalibration()
        }

        // Initially hide save button
        btnSave.visibility = View.GONE
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val point = PointF(event.x, event.y)

                when (currentStep) {
                    CalibrationStep.DRAW_LINE -> {
                        if (calibrationPoints.size < 2) {
                            calibrationPoints.add(point)
                            drawOverlay()

                            if (calibrationPoints.size == 2) {
                                Toast.makeText(this, "Counting line set! Tap Next to continue", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    CalibrationStep.DRAW_ZONES -> {
                        zonePoints.add(point)
                        drawOverlay()

                        if (zonePoints.size == 4) {
                            // Zone complete - prompt for name
                            promptForZoneName()
                        }
                    }
                    else -> {}
                }
            }
        }
        return true
    }

    private fun promptForZoneName() {
        val editText = EditText(this)
        editText.hint = "e.g., Entrance, Main Area, Exit"

        AlertDialog.Builder(this)
            .setTitle("Zone Name")
            .setMessage("Enter a name for this zone:")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val zoneName = editText.text.toString().ifEmpty {
                    "Zone ${drawnZones.size + 1}"
                }

                val zone = ConfigManager.Zone(
                    id = UUID.randomUUID().toString(),
                    name = zoneName,
                    points = zonePoints.toList(),
                    capacity = 50,  // Default capacity
                    type = ConfigManager.ZoneType.COUNTING
                )

                drawnZones.add(zone)
                configManager.saveZone(zone)

                Toast.makeText(this, "Zone '$zoneName' added", Toast.LENGTH_SHORT).show()
                zonePoints.clear()
                drawOverlay()
            }
            .setNegativeButton("Cancel") { _, _ ->
                zonePoints.clear()
                drawOverlay()
            }
            .create()
            .show()
    }

    private fun drawOverlay() {
        val bitmap = Bitmap.createBitmap(
            overlayView.width.coerceAtLeast(1),
            overlayView.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)

        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            isAntiAlias = true
        }

        // Draw counting line (red)
        if (calibrationPoints.size >= 2) {
            paint.color = Color.RED
            paint.strokeWidth = 8f
            canvas.drawLine(
                calibrationPoints[0].x,
                calibrationPoints[0].y,
                calibrationPoints[1].x,
                calibrationPoints[1].y,
                paint
            )
        }

        // Draw calibration points
        paint.style = Paint.Style.FILL
        paint.color = Color.YELLOW
        calibrationPoints.forEach { point ->
            canvas.drawCircle(point.x, point.y, 12f, paint)
        }

        // Draw saved zones (green)
        paint.style = Paint.Style.STROKE
        paint.color = Color.GREEN
        paint.strokeWidth = 5f
        drawnZones.forEach { zone ->
            val path = Path()
            zone.points.forEachIndexed { index, point ->
                if (index == 0) {
                    path.moveTo(point.x, point.y)
                } else {
                    path.lineTo(point.x, point.y)
                }
            }
            path.close()
            canvas.drawPath(path, paint)
        }

        // Draw current zone being created (blue)
        if (zonePoints.isNotEmpty()) {
            paint.color = Color.BLUE
            val path = Path()
            zonePoints.forEachIndexed { index, point ->
                if (index == 0) {
                    path.moveTo(point.x, point.y)
                } else {
                    path.lineTo(point.x, point.y)
                }
            }
            if (zonePoints.size > 1) {
                canvas.drawPath(path, paint)
            }

            // Draw zone points
            paint.style = Paint.Style.FILL
            zonePoints.forEach { point ->
                canvas.drawCircle(point.x, point.y, 10f, paint)
            }
        }

        overlayView.background = android.graphics.drawable.BitmapDrawable(resources, bitmap)
    }

    private fun updateInstructions() {
        val instruction = when (currentStep) {
            CalibrationStep.DRAW_LINE ->
                "Step 1: Tap 2 points to draw the entry/exit counting line (people crossing this line will be counted)"
            CalibrationStep.DRAW_ZONES ->
                "Step 2: Tap 4 corners to define counting zones. You can create multiple zones. Tap Next when done."
            CalibrationStep.COMPLETE ->
                "Calibration complete! Review your setup and tap Save to finish."
        }

        tvInstructions.text = instruction
    }

    private fun nextStep() {
        when (currentStep) {
            CalibrationStep.DRAW_LINE -> {
                if (calibrationPoints.size >= 2) {
                    // Save counting line
                    val line = ConfigManager.CountingLine(
                        startX = calibrationPoints[0].x,
                        startY = calibrationPoints[0].y,
                        endX = calibrationPoints[1].x,
                        endY = calibrationPoints[1].y
                    )
                    configManager.saveCountingLine(line)

                    currentStep = CalibrationStep.DRAW_ZONES
                    updateInstructions()
                } else {
                    Toast.makeText(this, "Please draw the counting line first (tap 2 points)", Toast.LENGTH_SHORT).show()
                }
            }
            CalibrationStep.DRAW_ZONES -> {
                if (drawnZones.isNotEmpty() || zonePoints.isEmpty()) {
                    currentStep = CalibrationStep.COMPLETE
                    updateInstructions()
                    btnNext.visibility = View.GONE
                    btnSave.visibility = View.VISIBLE
                } else {
                    Toast.makeText(this, "Please complete the current zone or create at least one zone", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {}
        }
    }

    private fun saveCalibration() {
        // Save calibration data
        val calibrationData = ConfigManager.CalibrationData(
            cameraHeight = 2.5f,  // Default values - could add input dialogs for these
            cameraAngle = 45f,
            pixelsPerMeter = calculatePixelsPerMeter(),
            calibrationDate = System.currentTimeMillis()
        )

        configManager.saveCalibration(calibrationData)

        Toast.makeText(this, "Calibration saved successfully! ${drawnZones.size} zones configured.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun calculatePixelsPerMeter(): Float {
        // Simple estimation based on counting line length
        // In a real deployment, you might want to ask user to measure actual distance
        return if (calibrationPoints.size >= 2) {

            val dx = calibrationPoints[1].x - calibrationPoints[0].x
            val dy = calibrationPoints[1].y - calibrationPoints[0].y

            val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()



            // Assume the line represents approximately 2 meters
            distance / 2f
        } else {
            100f  // Default value
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
    }
}