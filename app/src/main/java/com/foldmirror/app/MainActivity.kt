
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
import android.widget.TextView
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import com.foldmirror.app.databinding.ActivityMainBinding
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var displayManager: DisplayManager
    private lateinit var binding: ActivityMainBinding
    private val captureStatus: TextView
    get() = binding.mirrorStatus


private val resultText: TextView
    get() = binding.resultText

    private var captureService: CaptureService? = null
    private var serviceBound = false
    private var isStartingMirroring = false

    private var mirrorPresentation: CoverMirrorPresentation? = null
    private var mirrorDisplayId = -1

    private val foldMonitorHandler = Handler(Looper.getMainLooper())
private var foldMonitorRunning = false


    private fun updateFoldStatus(baseState: Int?) {
        binding.foldStatus.text = getString(
            when (baseState) {
                0 -> R.string.folded
                1 -> R.string.tent_mode
                2 -> R.string.half_open
                3 -> R.string.unfolded
                4 -> R.string.concurrent_display
                else -> R.string.unknown
            }
        )
    }
    private fun getCurrentBaseState(): Int? {
    return try {
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
                .bufferedReader()
                .use { reader -> reader.readText() }
        }

        process.waitFor()

        Regex(
            """mBaseState=Optional\[DeviceState\{identifier=(\d+)"""
        ).find(output)?.groupValues?.get(1)?.toIntOrNull()

    } catch (e: Exception) {
        Log.e(TAG, "Unable to check fold state", e)
        null
    }
}


    private fun logEvent(level: String, message: String) {
        runOnUiThread {
            val timestamp = SimpleDateFormat(
                "HH:mm:ss",
                Locale.getDefault()
            ).format(Date())

            val color = when (level.uppercase()) {
                "SUCCESS" -> Color.rgb(101, 214, 165)
                "WARNING" -> Color.rgb(255, 193, 7)
                "ERROR" -> Color.rgb(255, 100, 100)
                else -> Color.rgb(130, 180, 255)
            }

            val logLine = SpannableString(
                "[$timestamp] [$level] $message\n"
            )

            logLine.setSpan(
                ForegroundColorSpan(color),
                0,
                logLine.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            binding.terminalText.append(logLine)

            // Keep terminal size under control.
            val terminalContent = binding.terminalText.text

            if (terminalContent.length > 12000) {
                binding.terminalText.text = terminalContent.subSequence(
                    terminalContent.length - 9000,
                    terminalContent.length
                )
            }

            binding.terminalScroll.post {
                binding.terminalScroll.fullScroll(
                    android.view.View.FOCUS_DOWN
                )
            }
        }
    }

    private val foldMonitorRunnable = object : Runnable {
        override fun run() {
            if (!foldMonitorRunning) return

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
                    if (!foldMonitorRunning) return@runOnUiThread

                    updateFoldStatus(baseState)

                    when (baseState) {
                        3 -> foldMonitorHandler.postDelayed(this, 1000)

                        null -> foldMonitorHandler.postDelayed(this, 1500)

                        else -> {
                            if (serviceBound) {
                                Log.i(TAG, "Device folded. Stopping mirroring.")
                                stopMirroring()
                            } else {
                                foldMonitorHandler.postDelayed(this, 1000)
                            }
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


    private fun updateShizukuStatus() {
        binding.shizukuStatus.text = getString(
            if (Shizuku.pingBinder()) {
                R.string.shizuku_connected
            } else {
                R.string.shizuku_disconnected
            }
        )
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

        binding = ActivityMainBinding.inflate(layoutInflater)
setContentView(binding.root)
        binding.terminalText.text = ""

        logEvent("SUCCESS", "FoldMirror initialized")
        logEvent("INFO", "Initializing system services...")

// Start mirroring
binding.btnStart.setOnClickListener {
    startMirrorToCover()
}

// Stop mirroring
binding.btnStop.setOnClickListener {
    stopMirroring()
}

// Refresh display information
binding.btnRefresh.setOnClickListener {
    refreshDisplays()
}
        logEvent("INFO", "Shizuku initialization requested")
Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)

initShizuku()

        if (
            Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startFoldMonitor()
        }

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
    isStartingMirroring = false
    captureStatus.text = "Invalid CaptureService binder."
                logEvent("ERROR", "Invalid CaptureService binder")
    stopMirroring()
    return
}

            captureService = binder.getService()
            serviceBound = true
            isStartingMirroring = false

            captureStatus.text = "Capture service connected. Preparing cover display..."
            logEvent("SUCCESS", "CaptureService connected")
            logEvent("INFO", "Preparing cover display presentation...")

            // Show the Presentation only after the service is available.
            tryMirrorPresentation()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            captureService = null
            isStartingMirroring = false
            logEvent("WARNING", "CaptureService disconnected")

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
        logEvent("INFO", "Mirror startup requested")

    if (isStartingMirroring || serviceBound) {
        logEvent("WARNING", "Mirroring is already starting or active")
    captureStatus.text = getString(R.string.mirroring_already_active)
    return
}
    

    if (!Shizuku.pingBinder() ||
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
    ) {
        logEvent("WARNING", "Cannot start mirroring: Shizuku permission required")
        captureStatus.text = getString(R.string.shizuku_permission_required)
        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        return
    }
    isStartingMirroring = true
        logEvent("INFO", "Enabling concurrent display state...")

    captureStatus.text = getString(R.string.enabling_concurrent_display)


        Thread {
    try {

        // Check the physical fold state before changing anything.
        val currentState = getCurrentBaseState()

        if (currentState == 0) {
            runOnUiThread {
                isStartingMirroring = false

                captureStatus.text =
                    "Device is folded. Unfold your device before mirroring."

                logEvent(
                    "WARNING",
                    "Mirroring blocked: Device is folded."
                )

                updateFoldStatus(currentState)
            }

            return@Thread
        }

        if (currentState == null) {
            runOnUiThread {
                isStartingMirroring = false

                captureStatus.text =
                    "Unable to determine fold state. Mirroring cancelled."

                logEvent(
                    "ERROR",
                    "Could not verify fold state. Startup cancelled."
                )
            }

            return@Thread
        }

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
                    logEvent("SUCCESS", "Concurrent display state enabled")
                    logEvent("INFO", "Requesting screen capture permission...")
                    captureStatus.text = getString(R.string.requesting_capture_permission)
                    requestScreenCapturePermission()
                } else {
                    captureStatus.text = "Failed to enable concurrent display: $output"
                    logEvent("ERROR", "Failed to enable concurrent display: $output")
                    isStartingMirroring = false
                }
            }

        } catch (e: Exception) {
            
            isStartingMirroring = false
            runOnUiThread {
                logEvent("ERROR", "Device state error: ${e.message}")
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
    captureStatus.text = "Permission denied. Resetting device state..."
            logEvent("WARNING", "Screen capture permission denied or cancelled")
    isStartingMirroring = false

    requestDeviceState("reset")
    return
}
        logEvent("SUCCESS", "Screen capture permission granted")

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
    isStartingMirroring = false
    captureStatus.text = "Failed to bind CaptureService."

    stopService(Intent(this, CaptureService::class.java))
    requestDeviceState("reset")
    return
}

            captureStatus.text = "Starting capture service..."

        } catch (e: Exception) {
            isStartingMirroring = false
requestDeviceState("reset")

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
            logEvent("WARNING", "CaptureService is not ready for presentation")
            return
        }

        val cover = displayManager.getDisplay(COVER_DISPLAY_ID)
            ?: displayManager.getDisplays(
                DisplayManager.DISPLAY_CATEGORY_PRESENTATION
            ).firstOrNull()

        if (cover == null) {
            captureStatus.text = "No secondary presentation display found."
            logEvent("ERROR", "No secondary presentation display found")
            return
        }

        if (cover.displayId == Display.DEFAULT_DISPLAY) {
            captureStatus.text = "Secondary display not found."
            logEvent("ERROR", "Secondary display identification failed")
            return
        }

        if (cover.state != Display.STATE_ON) {
            captureStatus.text =
                "Cover display is not ON. State=${cover.state}"
            logEvent("WARNING", "Cover display is not ON. State=${cover.state}")
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
            logEvent("SUCCESS", "Cover presentation displayed on display ${cover.displayId}.")

            Log.i(
                TAG,
                "Mirror Presentation shown on display ${cover.displayId}"
            )

        } catch (e: Exception) {
Log.e(TAG, "Failed to show cover Presentation", e)

captureStatus.text = "Presentation failed: ${e.message}"
            logEvent("ERROR", "Presentation failed: ${e.message}")

stopMirroring()

        }
    }

    inner class CoverMirrorPresentation(
        context: Context,
        display: Display
    ) : Presentation(context, display) {

        private lateinit var surfaceView: SurfaceView

        private var coverDensity = 0
        private var lastLoggedWidth = -1
        private var lastLoggedHeight = -1

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
                        lastLoggedWidth = -1
                        lastLoggedHeight = -1
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

            if (width != lastLoggedWidth || height != lastLoggedHeight) {
                logEvent("SUCCESS", "Mirror surface attached: ${width}x${height}")
                lastLoggedWidth = width
                lastLoggedHeight = height
            }
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
    logEvent("INFO","Stop Mirroring requested...")
    isStartingMirroring = false

    

    // Detach the mirror surface.
    try {
        captureService?.setMirrorSurface(null, 0, 0, 0)



    } catch (e: Exception) {
        Log.e(TAG, "Failed to detach mirror surface", e)
        logEvent("WARNING","Failed to detach mirror surface")
    }


    // Dismiss the cover Presentation.
    try {
        mirrorPresentation?.dismiss()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to dismiss Presentation", e)
        logEvent("WARNING","Failed to dismiss Presentation")
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
            logEvent("WARNING","Failed to unbind CaptureService")
        }

        serviceBound = false
    }

    captureService = null

    captureStatus.text = "Mirroring stopped. Resetting device state..."
    logEvent("SUCCESS","Mirroring stopped")

    // Allow the capture service to finish releasing its resources.
    Handler(Looper.getMainLooper()).postDelayed({

        Log.i(TAG, "Resetting Samsung device state")
        logEvent("INFO","Resetting Samsung device state...")

        // Restore Samsung's automatic display state.
        requestDeviceState("reset")
        logEvent("SUCCESS","Samsung device state restored")

    }, 500)
}



    // ---------------------------------------------------------
    // SHIZUKU
    // ---------------------------------------------------------


    private fun initShizuku() {

        if (!Shizuku.pingBinder()) {
            captureStatus.text = "Shizuku unavailable"
            logEvent("WARNING", "Shizuku service is unavailable")
            updateShizukuStatus()
            return
        }

        updateShizukuStatus()

        if (
            Shizuku.checkSelfPermission() !=
            PackageManager.PERMISSION_GRANTED
        ) {
            captureStatus.text = "Requesting Shizuku permission..."
            logEvent("INFO", "Requesting Shizuku permission")

            Shizuku.addRequestPermissionResultListener(
                shizukuPermissionListener
            )

            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)

        } else {
            captureStatus.text = "Shizuku connected."
            logEvent("SUCCESS", "Shizuku is connected and permission is granted")
        }
    }


    private val shizukuPermissionListener =
    Shizuku.OnRequestPermissionResultListener {
        requestCode, grantResult ->

        if (requestCode != SHIZUKU_REQUEST_CODE) {
            return@OnRequestPermissionResultListener
        }
        updateShizukuStatus()

        if (grantResult == PackageManager.PERMISSION_GRANTED) {

            captureStatus.text = "Shizuku permission granted."
            logEvent("SUCCESS", "Shizuku permission granted")
            startFoldMonitor()

            if (resetStateWhenShizukuReturns) {
    requestDeviceState("reset")
}

        } else {
            captureStatus.text = "Shizuku permission denied."
            logEvent("WARNING", "Shizuku permission denied")
        }
    }

        // ---------------------------------------------------------
// SHIZUKU CONNECTION MONITORING
// ---------------------------------------------------------

private var resetStateWhenShizukuReturns = false

// Called when Shizuku disconnects or its process dies.
private val shizukuBinderDeadListener =
    Shizuku.OnBinderDeadListener {

        Log.w(TAG, "Shizuku binder disconnected")
        logEvent("WARNING", "Shizuku binder disconnected")
        updateShizukuStatus()

        if (serviceBound || isStartingMirroring || mirrorPresentation != null) {

            resetStateWhenShizukuReturns = true

            captureStatus.text =
                "Shizuku disconnected. Stopping mirroring..."

            stopMirroring()

        } else {

            captureStatus.text = "Shizuku disconnected."
        }
    }

// Called when Shizuku becomes available again.
private val shizukuBinderReceivedListener =
    Shizuku.OnBinderReceivedListener {

        Log.i(TAG, "Shizuku binder connected")
        logEvent("SUCCESS", "Shizuku binder connected")
        updateShizukuStatus()

        if (resetStateWhenShizukuReturns) {

            if (Shizuku.checkSelfPermission() ==
                PackageManager.PERMISSION_GRANTED
            ) {
                captureStatus.text = "Restoring normal display state..."

                requestDeviceState("reset")

            } else {
                captureStatus.text = "Shizuku permission required for display recovery."

                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            }

        } else {
            captureStatus.text = "Shizuku connected."
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

        captureStatus.text = getString(
            R.string.device_state_requesting,
            state
        )

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

                    if (exitCode == 0) {

    if (state == "reset") {
        resetStateWhenShizukuReturns = false
    }

                        captureStatus.text = getString(
                            R.string.device_state_success,
                            output
                        )

} else {
                        captureStatus.text = getString(
                            R.string.device_state_failed,
                            output
                        )
}

                    refreshDisplays()
                }

            } catch (e: Exception) {

                Log.e(TAG, "Shizuku command failed", e)

                runOnUiThread {
                    captureStatus.text = getString(
                        R.string.shizuku_error,
                        e.message ?: getString(R.string.unknown_error)
                    )
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
        stopFoldMonitor()
    stopMirroring()

    displayManager.unregisterDisplayListener(displayListener)
    Shizuku.removeRequestPermissionResultListener(
        shizukuPermissionListener
    )
    Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)

    super.onDestroy()
}

    companion object {

        private const val TAG = "MainActivity"

        private const val SCREEN_CAPTURE_REQUEST_CODE = 100
        private const val SHIZUKU_REQUEST_CODE = 101

        private const val COVER_DISPLAY_ID = 1
    }
}
