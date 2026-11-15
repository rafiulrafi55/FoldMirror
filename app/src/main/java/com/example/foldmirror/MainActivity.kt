
package com.foldmirror.app

import android.app.Activity
import android.app.Presentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var displayManager: DisplayManager
    private lateinit var resultText: TextView
    private lateinit var captureStatus: TextView

    private var captureService: CaptureService? = null
    private var serviceBound = false

    private var mirrorPresentation: CoverMirrorPresentation? = null
    private var mirrorDisplayId = -1

    private val foldMonitorHandler = Handler(Looper.getMainLooper())
private var foldMonitorRunning = false

private val foldMonitorRunnable = object : Runnable {
    override fun run() {
        if (!foldMonitorRunning || !serviceBound) return

        Thread {
            var baseState: Int? = null

            try {
                val service = IShizukuService.Stub.asInterface(
                    Shizuku.getBinder()
                )

                val process = service.newProcess(
                    arrayOf("dumpsys", "device_state"),
                    null,
                    null
                )

                val output = process.getInputStream().use {
                    ParcelFileDescriptor.AutoCloseInputStream(it)
                        .bufferedReader().use { reader -> reader.readText() }
                }

                process.waitFor()

                baseState = Regex(
                    """mBaseState=Optional\[DeviceState\{identifier=(\d+)"""
                ).find(output)?.groupValues?.get(1)?.toIntOrNull()

            } catch (e: Exception) {
                Log.e(TAG, "Fold detection failed", e)
            }

            runOnUiThread {
                if (!foldMonitorRunning || !serviceBound) return@runOnUiThread

                when (baseState) {
                    3 -> foldMonitorHandler.postDelayed(this, 1000)

                    null -> foldMonitorHandler.postDelayed(this, 1500)

                    else -> {
                        Log.i(TAG, "Device folded. Stopping mirroring.")
                        stopMirroring()
                    }
                }
            }
        }.start()
    }
}

private fun startFoldMonitor() {
    if (foldMonitorRunning) return

    foldMonitorRunning = true
    foldMonitorHandler.post(foldMonitorRunnable)
}

private fun stopFoldMonitor() {
    foldMonitorRunning = false
    foldMonitorHandler.removeCallbacks(foldMonitorRunnable)
}

    private val displayListener = object : DisplayManager.DisplayListener {

        override fun onDisplayAdded(displayId: Int) {
            refreshDisplays()
        }

        override fun onDisplayRemoved(displayId: Int) {
            refreshDisplays()

            if (displayId == mirrorDisplayId) {
                mirrorPresentation?.dismiss()
                mirrorPresentation = null
                mirrorDisplayId = -1
            }
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

        captureStatus = TextView(this).apply {
            text = "Capture idle."
            setPadding(24, 12, 24, 12)
        }

        val refreshButton = Button(this).apply {
            text = "Refresh Display Information"
            setOnClickListener { refreshDisplays() }
        }

        val mirrorButton = Button(this).apply {
            text = "Start Mirroring to Cover"
            setOnClickListener { startMirrorToCover() }
        }

        val stopButton = Button(this).apply {
            text = "Stop Mirroring"
            setOnClickListener { stopMirroring() }
        }

        val concurrentStateButton = Button(this).apply {
            text = "Request Concurrent Display State"
            setOnClickListener { requestDeviceState("4") }
        }

        val resetStateButton = Button(this).apply {
            text = "Reset Device State"
            setOnClickListener { requestDeviceState("reset") }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(refreshButton)
            addView(mirrorButton)
            addView(stopButton)
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

        setContentView(ScrollView(this).apply {
            addView(layout)
        })

        initShizuku()

        displayManager.registerDisplayListener(
            displayListener,
            Handler(Looper.getMainLooper())
        )

        refreshDisplays()
    }

    // ---------------------------------------------------------
    // MIRRORING SERVICE CONNECTION
    // ---------------------------------------------------------

    private val serviceConnection = object : ServiceConnection {

        override fun onServiceConnected(
            name: ComponentName?,
            service: IBinder?
        ) {

            val binder = service as? CaptureService.LocalBinder

            if (binder == null) {
                captureStatus.text = "Invalid CaptureService binder."
                return
            }

            captureService = binder.getService()
            serviceBound = true

            captureStatus.text = "Capture service connected. Preparing cover display..."

            // Show the Presentation only after the service is available.
            tryMirrorPresentation()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            captureService = null

            captureStatus.text = "Capture service disconnected."
        }
    }

    // ---------------------------------------------------------
    // SCREEN CAPTURE PERMISSION HANDLING
    // ---------------------------------------------------------

    private fun requestScreenCapturePermission() {

    val manager =
        getSystemService(MediaProjectionManager::class.java)

    startActivityForResult(
        manager.createScreenCaptureIntent(),
        SCREEN_CAPTURE_REQUEST_CODE
    )
}

    // ---------------------------------------------------------
    // START MIRRORING
    // ---------------------------------------------------------

    private fun startMirrorToCover() {

    if (serviceBound) {
        captureStatus.text = "Already mirroring."
        return
    }

    if (!Shizuku.pingBinder() ||
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
    ) {
        captureStatus.text = "Shizuku permission required."
        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        return
    }

    captureStatus.text = "Enabling concurrent display..."

    Thread {
        try {
            val service = IShizukuService.Stub.asInterface(
                Shizuku.getBinder()
            )

            val process = service.newProcess(
                arrayOf("cmd", "device_state", "state", "4"),
                null,
                null
            )

            val output = process.getInputStream().use { descriptor ->
    ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        .bufferedReader()
        .use { reader -> reader.readText() }
}.trim()
            val exitCode = process.waitFor()

            runOnUiThread {
                if (exitCode == 0) {
                    captureStatus.text = "Requesting screen capture permission..."
                    requestScreenCapturePermission()
                } else {
                    captureStatus.text = "Failed to enable concurrent display: $output"
                }
            }

        } catch (e: Exception) {
            runOnUiThread {
                captureStatus.text = "Device state error: ${e.message}"
            }
        }
    }.start()
}

    @Deprecated("Use Activity Result API when migrating to ComponentActivity.")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != SCREEN_CAPTURE_REQUEST_CODE) return

        if (resultCode != RESULT_OK || data == null) {
            captureStatus.text = "Screen capture permission denied."
            return
        }

        try {

            val serviceIntent =
                Intent(this, CaptureService::class.java).apply {
                    putExtra(
                        CaptureService.EXTRA_RESULT_CODE,
                        resultCode
                    )

                    putExtra(
                        CaptureService.EXTRA_RESULT_DATA,
                        data
                    )
                }

            // Start foreground service with the permission result.
            ContextCompat.startForegroundService(
                this,
                serviceIntent
            )

            // Bind to the same service instance.
            val bound = bindService(
                Intent(this, CaptureService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )

            if (!bound) {
                captureStatus.text = "Failed to bind CaptureService."
                return
            }

            captureStatus.text = "Starting capture service..."

        } catch (e: Exception) {

            Log.e(TAG, "Failed to start mirroring", e)
            captureStatus.text = "Start failed: ${e.message}"

        }
    }

    // ---------------------------------------------------------
    // COVER PRESENTATION
    // ---------------------------------------------------------

    private fun tryMirrorPresentation() {

        if (!serviceBound || captureService == null) {
            Log.w(TAG, "CaptureService is not ready")
            return
        }

        val cover = displayManager.getDisplay(COVER_DISPLAY_ID)
            ?: displayManager.getDisplays(
                DisplayManager.DISPLAY_CATEGORY_PRESENTATION
            ).firstOrNull()

        if (cover == null) {
            captureStatus.text = "No secondary presentation display found."
            return
        }

        if (cover.displayId == Display.DEFAULT_DISPLAY) {
            captureStatus.text = "Secondary display not found."
            return
        }

        if (cover.state != Display.STATE_ON) {
            captureStatus.text =
                "Cover display is not ON. State=${cover.state}"
            return
        }

        // Avoid creating duplicate Presentation windows.
        if (mirrorPresentation != null &&
            mirrorDisplayId == cover.displayId
        ) {
            return
        }

        try {

            mirrorPresentation?.dismiss()
            mirrorPresentation = null

            val presentation =
                CoverMirrorPresentation(this, cover)

            presentation.show()

            mirrorPresentation = presentation
            mirrorDisplayId = cover.displayId

            startFoldMonitor()

            captureStatus.text =
                "Cover display connected. Starting mirror..."

            Log.i(
                TAG,
                "Mirror Presentation shown on display ${cover.displayId}"
            )

        } catch (e: Exception) {

            Log.e(TAG, "Failed to show cover Presentation", e)
            captureStatus.text =
                "Presentation failed: ${e.message}"

        }
    }

    inner class CoverMirrorPresentation(
        context: Context,
        display: Display
    ) : Presentation(context, display) {

        private lateinit var surfaceView: SurfaceView

        private var coverDensity = 0

        override fun onCreate(savedInstanceState: Bundle?) {

            super.onCreate(savedInstanceState)

            val metrics = DisplayMetrics()

            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)

            coverDensity = metrics.densityDpi

            surfaceView = SurfaceView(context)

            surfaceView.holder.addCallback(
                object : SurfaceHolder.Callback {

                    override fun surfaceCreated(holder: SurfaceHolder) {

                        val frame = holder.surfaceFrame

                        attachMirrorSurface(
                            holder,
                            frame.width(),
                            frame.height()
                        )
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {

                        Log.d(
                            TAG,
                            "Cover surface changed: ${width}x${height}"
                        )

                        attachMirrorSurface(
                            holder,
                            width,
                            height
                        )
                    }

                    override fun surfaceDestroyed(
                        holder: SurfaceHolder
                    ) {

                        Log.d(TAG, "Cover surface destroyed")

                        // Detach only. Do not create or destroy
                        // another VirtualDisplay here.
                        captureService?.setMirrorSurface(
                            null,
                            0,
                            0,
                            0
                        )
                    }
                }
            )

            setContentView(
                surfaceView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        private fun attachMirrorSurface(
            holder: SurfaceHolder,
            width: Int,
            height: Int
        ) {

            if (!holder.surface.isValid) return

            if (width <= 0 || height <= 0) return

            Log.d(
                TAG,
                "Attaching mirror surface: ${width}x${height}"
            )

            captureService?.setMirrorSurface(
                holder.surface,
                width,
                height,
                coverDensity
            )
        }

        override fun onStop() {

            captureService?.setMirrorSurface(
                null,
                0,
                0,
                0
            )

            super.onStop()
        }
    }

    // ---------------------------------------------------------
    // STOP MIRRORING
    // ---------------------------------------------------------

   

private fun stopMirroring() {

    Log.i(TAG, "Stop Mirroring requested")
    stopFoldMonitor()

    // Detach the mirror surface.
    try {
        captureService?.setMirrorSurface(null, 0, 0, 0)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to detach mirror surface", e)
    }

    // Dismiss the cover Presentation.
    try {
        mirrorPresentation?.dismiss()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to dismiss Presentation", e)
    }

    mirrorPresentation = null
    mirrorDisplayId = -1

    // Stop the capture service.
    stopService(Intent(this, CaptureService::class.java))

    // Unbind from the service.
    if (serviceBound) {
        try {
            unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind CaptureService", e)
        }

        serviceBound = false
    }

    captureService = null

    captureStatus.text = "Mirroring stopped. Resetting device state..."

    // Allow the capture service to finish releasing its resources.
    Handler(Looper.getMainLooper()).postDelayed({

        Log.i(TAG, "Resetting Samsung device state")

        // Restore Samsung's automatic display state.
        requestDeviceState("reset")

    }, 500)
}



    // ---------------------------------------------------------
    // SHIZUKU
    // ---------------------------------------------------------

    private fun initShizuku() {

        if (!Shizuku.pingBinder()) {
            captureStatus.text = "Shizuku unavailable."
            return
        }

        if (
            Shizuku.checkSelfPermission() !=
            PackageManager.PERMISSION_GRANTED
        ) {

            captureStatus.text = "Requesting Shizuku permission..."

            Shizuku.addRequestPermissionResultListener(
                shizukuPermissionListener
            )

            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)

        } else {

            captureStatus.text = "Shizuku connected."
        }
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener {
                requestCode,
                grantResult ->

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

        if (
            Shizuku.checkSelfPermission() !=
            PackageManager.PERMISSION_GRANTED
        ) {

            captureStatus.text =
                "Grant FoldMirror permission in Shizuku first."

            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            return
        }

        captureStatus.text = "Requesting device state $state..."

        Thread {

            try {

                val service =
                    IShizukuService.Stub.asInterface(
                        Shizuku.getBinder()
                    )

                val process = service.newProcess(
                    arrayOf(
                        "cmd",
                        "device_state",
                        "state",
                        state
                    ),
                    null,
                    null
                )

                val output = process.getInputStream().use {
                    descriptor ->

                    ParcelFileDescriptor.AutoCloseInputStream(
                        descriptor
                    ).bufferedReader().use {
                        it.readText()
                    }
                }.trim()

                val exitCode = process.waitFor()

                runOnUiThread {

                    captureStatus.text =
                        if (exitCode == 0) {
                            "Device state command succeeded: $output"
                        } else {
                            "Device state command failed: $output"
                        }

                    refreshDisplays()
                }

            } catch (e: Exception) {

                Log.e(TAG, "Shizuku command failed", e)

                runOnUiThread {
                    captureStatus.text =
                        "Shizuku error: ${e.message}"
                }
            }

        }.start()
    }

    // ---------------------------------------------------------
    // DISPLAY DIAGNOSTICS
    // ---------------------------------------------------------

    private fun refreshDisplays() {

        val displays = displayManager.displays

        val presentationDisplays =
            displayManager.getDisplays(
                DisplayManager.DISPLAY_CATEGORY_PRESENTATION
            )

        val report = StringBuilder()

        report.appendLine("FOLDMIRROR DIAGNOSTICS")
        report.appendLine("======================")

        report.appendLine(
            "Android: ${android.os.Build.VERSION.RELEASE}"
        )

        report.appendLine(
            "SDK: ${android.os.Build.VERSION.SDK_INT}"
        )

        report.appendLine(
            "Manufacturer: ${android.os.Build.MANUFACTURER}"
        )

        report.appendLine(
            "Device: ${android.os.Build.DEVICE}"
        )

        report.appendLine(
            "Model: ${android.os.Build.MODEL}"
        )

        report.appendLine(
            "Default displays found: ${displays.size}"
        )

        report.appendLine(
            "Presentation displays found: ${presentationDisplays.size}"
        )

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

            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)

            report.appendLine("$category DISPLAY ${index + 1}")
            report.appendLine("----------------------")
            report.appendLine("ID: ${display.displayId}")
            report.appendLine("Name: ${display.name}")
            report.appendLine("State: ${display.state}")
            report.appendLine("Flags: ${display.flags}")

            report.appendLine(
                "Presentation flag: ${
                    display.flags and Display.FLAG_PRESENTATION != 0
                }"
            )

            report.appendLine("Rotation: ${display.rotation}")

            report.appendLine(
                "Resolution: ${display.mode.physicalWidth} x " +
                    "${display.mode.physicalHeight}"
            )

            report.appendLine("Density: ${metrics.densityDpi} dpi")
            report.appendLine()
        }
    }

    // ---------------------------------------------------------
    // ACTIVITY LIFECYCLE
    // ---------------------------------------------------------

    override fun onDestroy() {

        mirrorPresentation?.dismiss()
        mirrorPresentation = null

        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }

        displayManager.unregisterDisplayListener(displayListener)
        stopFoldMonitor()

        super.onDestroy()
        
    }

    companion object {

        private const val TAG = "MainActivity"

        private const val SCREEN_CAPTURE_REQUEST_CODE = 100
        private const val SHIZUKU_REQUEST_CODE = 101

        private const val COVER_DISPLAY_ID = 1
    }
}
