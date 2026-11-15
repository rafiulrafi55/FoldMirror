
package com.foldmirror.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.os.Handler
import androidx.core.app.NotificationCompat

class CaptureService : Service() {

    private val TAG = "CaptureService"

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var sourceWidth = 0
    private var sourceHeight = 0
    private var sourceDensity = 0

    private var isStopping = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection callback: onStop")

            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.d(TAG, "onStartCommand called")

        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Prevent multiple projection sessions in the same service.
        if (mediaProjection != null) {
            Log.w(TAG, "Capture session already active")
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            createNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1

        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "Invalid MediaProjection permission result")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val manager =
                getSystemService(MediaProjectionManager::class.java)

            mediaProjection = manager.getMediaProjection(
                resultCode,
                resultData
            )

            mediaProjection?.registerCallback(
                projectionCallback,
                mainHandler
            )

            Log.d(TAG, "MediaProjection obtained successfully")

            startCapture()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection", e)
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startCapture() {

        Log.d(TAG, "startCapture() entering")

        val displayManager =
            getSystemService(DisplayManager::class.java)

        val display =
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)

        if (display == null) {
            Log.e(TAG, "No default display found")
            stopCapture()
            stopSelf()
            return
        }

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        sourceWidth = metrics.widthPixels
        sourceHeight = metrics.heightPixels
        sourceDensity = metrics.densityDpi

        Log.d(
            TAG,
            "Source display: ${sourceWidth}x${sourceHeight}, " +
                "density=${sourceDensity}"
        )

        // IMPORTANT:
        // Do not create a VirtualDisplay here.
        // It will be created once when the cover SurfaceView becomes ready.
        Log.d(TAG, "Waiting for cover display surface")
    }

    fun setMirrorSurface(
        surface: Surface?,
        width: Int,
        height: Int,
        density: Int
    ) {

        Log.d(
            TAG,
            "setMirrorSurface: surface=${surface != null}, " +
                "size=${width}x${height}, density=$density"
        )

        val projection = mediaProjection

        if (projection == null || isStopping) {
            Log.w(TAG, "No active MediaProjection")
            return
        }

        // Surface destroyed: detach it, but keep the VirtualDisplay alive.
        if (surface == null) {

            Log.d(TAG, "Cover surface destroyed; detaching surface")

            virtualDisplay?.setSurface(null)

            return
        }

        if (!surface.isValid) {
            Log.e(TAG, "Cover surface is invalid")
            return
        }

        if (width <= 0 || height <= 0 || density <= 0) {
            Log.e(TAG, "Invalid cover surface dimensions")
            return
        }

        // If a VirtualDisplay already exists, reuse it.
        if (virtualDisplay != null) {

            Log.d(TAG, "Reusing existing VirtualDisplay")

            virtualDisplay?.resize(width, height, density)
            virtualDisplay?.setSurface(surface)

            Log.i(TAG, "Cover surface attached to existing VirtualDisplay")

            return
        }

        // First and ONLY VirtualDisplay creation for this session.
        try {

            virtualDisplay = projection.createVirtualDisplay(
                "FoldMirrorCover",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                mainHandler
            )

            if (virtualDisplay != null) {

                Log.i(
                    TAG,
                    "Mirror VirtualDisplay created successfully: " +
                        "${width}x${height}"
                )

            } else {

                Log.e(TAG, "Failed to create Mirror VirtualDisplay")

            }

        } catch (e: SecurityException) {

            Log.e(TAG, "MediaProjection security restriction", e)

        } catch (e: Exception) {

            Log.e(TAG, "Failed to create Mirror VirtualDisplay", e)

        }
    }

    private fun createNotification(): Notification {

        val channelId = "capture"

        val notificationManager =
            getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            notificationManager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Screen capture",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("FoldMirror")
            .setContentText("Mirroring the main display")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    private fun stopCapture() {

        if (isStopping) return

        isStopping = true

        Log.d(TAG, "stopCapture()")

        // Clear references before stopping projection to avoid
        // duplicate cleanup if callbacks arrive.
        val oldProjection = mediaProjection
        mediaProjection = null

        virtualDisplay?.setSurface(null)
        virtualDisplay?.release()
        virtualDisplay = null

        oldProjection?.unregisterCallback(projectionCallback)
        oldProjection?.stop()

        sourceWidth = 0
        sourceHeight = 0
        sourceDensity = 0

        Log.d(TAG, "Capture resources released")
    }

    override fun onDestroy() {

        Log.d(TAG, "onDestroy()")

        stopCapture()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    inner class LocalBinder : android.os.Binder() {

        fun getService(): CaptureService = this@CaptureService
    }

    companion object {

        const val ACTION_STOP =
            "com.foldmirror.app.action.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "result_code"

        const val EXTRA_RESULT_DATA = "result_data"

        private const val NOTIFICATION_ID = 1001
    }
}
