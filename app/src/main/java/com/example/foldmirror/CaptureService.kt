package com.foldmirror.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat

class CaptureService : Service() {

    private val TAG = "CaptureService"

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection callback: onStop")
            stopCapture()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")

        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "ACTION_STOP received")
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        // Start foreground immediately for media projection (Android 14/15 requirement)
        startForeground(
            NOTIFICATION_ID,
            createNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        Log.d(TAG, "startForeground called")

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        Log.d(TAG, "resultCode=$resultCode, resultData=${if (resultData == null) "null" else "present"}")

        if (resultCode == Activity.RESULT_CANCELED || resultData == null) {
            Log.e(TAG, "Invalid result code or data, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = manager.getMediaProjection(resultCode, resultData).also {
            it.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        }
        Log.d(TAG, "MediaProjection obtained successfully")

        startCapture()
        return START_NOT_STICKY
    }

    private fun startCapture() {
        Log.d(TAG, "startCapture() entering")

        val displayManager = getSystemService(DisplayManager::class.java)
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: run {
            Log.e(TAG, "No default display found")
            stopSelf()
            return
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        Log.d(TAG, "Display metrics: ${metrics.widthPixels}x${metrics.heightPixels}, densityDpi=${metrics.densityDpi}")

        captureThread = HandlerThread("FoldMirrorCapture").also { it.start() }
        val handler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            android.graphics.PixelFormat.RGBA_8888,
            2
        ).also { reader ->
            reader.setOnImageAvailableListener({ source ->
                Log.d(TAG, "Frame available")
                source.acquireLatestImage()?.close()
            }, handler)
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "FoldMirrorCapture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler
        )

        if (virtualDisplay == null) {
            Log.e(TAG, "Failed to create VirtualDisplay")
            stopSelf()
            return
        }

        Log.d(TAG, "VirtualDisplay created successfully, capture pipeline active")
    }

    private fun createNotification(): Notification {
        val channelId = "capture"
        val notificationManager = getSystemService(NotificationManager::class.java)
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
            .setContentTitle("FoldMirror capture test")
            .setContentText("Capturing the active display")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    private fun stopCapture() {
        Log.d(TAG, "stopCapture()")
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        captureThread?.quitSafely()
        captureThread = null
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.foldmirror.app.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 1001
    }
}