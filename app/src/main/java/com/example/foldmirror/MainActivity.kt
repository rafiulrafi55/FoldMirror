package com.foldmirror.app

import android.app.Activity
import android.app.Presentation
import android.content.Intent
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var displayManager: DisplayManager
    private lateinit var resultText: TextView
    private lateinit var captureStatus: TextView

    private val displayListener = object : DisplayManager.DisplayListener {

        override fun onDisplayAdded(displayId: Int) {
            refreshDisplays()
        }

        override fun onDisplayRemoved(displayId: Int) {
            refreshDisplays()
        }

        override fun onDisplayChanged(displayId: Int) {
            refreshDisplays()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        displayManager =
            getSystemService(DISPLAY_SERVICE) as DisplayManager

        resultText = TextView(this).apply {
            textSize = 14f
            setPadding(24, 24, 24, 24)
            text = "Detecting displays..."
        }

        val refreshButton = Button(this).apply {
            text = "Refresh Display Information"
            setOnClickListener { refreshDisplays() }
        }

        val startCaptureButton = Button(this).apply {
            text = "Start Screen Capture Test"
            setOnClickListener {
                val manager = getSystemService(MediaProjectionManager::class.java)
                startActivityForResult(
                    manager.createScreenCaptureIntent(),
                    SCREEN_CAPTURE_REQUEST_CODE
                )
            }
        }

        val stopCaptureButton = Button(this).apply {
            text = "Stop Screen Capture Test"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                captureStatus.text = "Capture test stopped."
            }
        }

        val concurrentStateButton = Button(this).apply {
            text = "Request Concurrent Display State"
            setOnClickListener { requestDeviceState("4") }
        }

        val resetStateButton = Button(this).apply {
            text = "Reset Device State"
            setOnClickListener { requestDeviceState("reset") }
        }

        captureStatus = TextView(this).apply {
            text = "Capture test idle."
            setPadding(24, 8, 24, 8)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(refreshButton)
            addView(startCaptureButton)
            addView(stopCaptureButton)
            addView(concurrentStateButton)
            addView(resetStateButton)
            addView(captureStatus)
            addView(
                resultText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }

        val scrollView = ScrollView(this).apply {
            addView(layout)
        }

        setContentView(scrollView)

        initShizuku()
        displayManager.registerDisplayListener(
            displayListener,
            Handler(Looper.getMainLooper())
        )

        refreshDisplays()
    }

    private fun initShizuku() {
        if (!Shizuku.pingBinder()) {
            captureStatus.text = "Shizuku unavailable. Install and start Shizuku first."
            return
        }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            captureStatus.text = "Requesting Shizuku permission..."
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } else {
            captureStatus.text = "Shizuku connected."
        }
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_REQUEST_CODE) {
                return@OnRequestPermissionResultListener
            }
            captureStatus.text =
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    "Shizuku permission granted."
                } else {
                    "Shizuku permission denied."
                }
        }

    private fun requestDeviceState(state: String) {
        if (!Shizuku.pingBinder()) {
            captureStatus.text = "Shizuku is not running."
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            captureStatus.text = "Grant FoldMirror permission in Shizuku first."
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            return
        }

        captureStatus.text = "Requesting device state $state..."
        Thread {
            try {
                val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
                val process = service.newProcess(
                    arrayOf("cmd", "device_state", "state", state),
                    null,
                    null
                )
                val output = process.getInputStream().use { descriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                        .bufferedReader()
                        .use { it.readText() }
                }.trim()
                val exitCode = process.waitFor()
                runOnUiThread {
                    if (exitCode == 0) {
                        captureStatus.text = "Device state command succeeded: $output"
                        refreshDisplays()
                        tryCoverPresentation()
                    } else {
                        captureStatus.text = "Device state command failed ($exitCode): $output"
                    }
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to execute device state command", exception)
                runOnUiThread {
                    captureStatus.text = "Shizuku command failed: ${exception.message}"
                }
            }
        }.start()
    }


    @Deprecated("Use the Activity Result API when this activity is migrated to ComponentActivity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SCREEN_CAPTURE_REQUEST_CODE) {
            return
        }
        if (resultCode != RESULT_OK || data == null) {
            captureStatus.text = "Capture permission was not granted."
            return
        }

        ContextCompat.startForegroundService(
            this,
            Intent(this, CaptureService::class.java).apply {
                putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            }
        )
        tryCoverPresentation()
        captureStatus.text = "Capture test running. Frames are being discarded after capture."
    }

    private fun refreshDisplays() {
        val displays = displayManager.displays
        val presentationDisplays =
            displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)

        val report = StringBuilder()

        report.appendLine("FOLDMIRROR DIAGNOSTICS")
        report.appendLine("======================")
        report.appendLine("Android: ${android.os.Build.VERSION.RELEASE}")
        report.appendLine("SDK: ${android.os.Build.VERSION.SDK_INT}")
        report.appendLine("Manufacturer: ${android.os.Build.MANUFACTURER}")
        report.appendLine("Device: ${android.os.Build.DEVICE}")
        report.appendLine("Model: ${android.os.Build.MODEL}")
        report.appendLine("Default displays found: ${displays.size}")
        report.appendLine("Presentation displays found: ${presentationDisplays.size}")
        report.appendLine()

        appendDisplayReport(report, "DEFAULT", displays)
        appendDisplayReport(report, "PRESENTATION", presentationDisplays)

        report.appendLine("END OF REPORT")

        resultText.text = report.toString()
    }

    private fun appendDisplayReport(
        report: StringBuilder,
        category: String,
        displays: Array<Display>
    ) {
        displays.forEachIndexed { index, display ->
            val metrics = DisplayMetrics()
            display.getMetrics(metrics)

            report.appendLine("$category DISPLAY ${index + 1}")
            report.appendLine("----------------------")
            report.appendLine("ID: ${display.displayId}")
            report.appendLine("Name: ${display.name}")
            report.appendLine("State: ${display.state}")
            report.appendLine("Flags: ${display.flags}")
            report.appendLine("Presentation flag: ${display.flags and Display.FLAG_PRESENTATION != 0}")
            report.appendLine("Rotation: ${display.rotation}")
            report.appendLine(
                "Resolution: ${display.mode.physicalWidth} x ${display.mode.physicalHeight}"
            )
            report.appendLine("Density: ${metrics.densityDpi} dpi")
            report.appendLine()
        }
    }

    /**
     * Attempt to show a Presentation on the cover display (displayId = 1).
     * Logs a warning if the cover display is not enabled.
     */
    private fun tryCoverPresentation() {
        val dm = getSystemService(DisplayManager::class.java)
        val cover = dm.getDisplay(COVER_DISPLAY_ID)
            ?: dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        if (cover == null) {
            Log.w(TAG, "Cover display is not visible to this app")
            return
        }
        if (cover.state != Display.STATE_ON) {
            Log.w(TAG, "Cover display is not ON (state=${cover.state}) – enable dual-screen mode")
            return
        }
        try {
            val presentation = object : Presentation(this, cover) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    val view = View(this@MainActivity)
                    view.setBackgroundColor(Color.BLACK)
                    setContentView(view)
                }
            }
            presentation.show()
            Log.i(TAG, "Cover Presentation shown successfully on display ${cover.displayId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Presentation on cover display", e)
        }
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val SCREEN_CAPTURE_REQUEST_CODE = 100
        private const val SHIZUKU_REQUEST_CODE = 101
        private const val COVER_DISPLAY_ID = 1
    }
}
