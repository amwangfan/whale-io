package com.nova.appmonitor.screenshot

import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.nova.appmonitor.AppMonitorApp
import java.io.File
import java.io.FileOutputStream

/**
 * One-shot MediaProjection capture then stop.
 */
class MediaProjectionService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val outPath = intent?.getStringExtra(EXTRA_OUT_PATH)
        if (resultCode != Activity.RESULT_OK || data == null || outPath.isNullOrBlank()) {
            pendingCallback?.onError("MediaProjection 参数无效")
            pendingCallback = null
            stopSelf()
            return START_NOT_STICKY
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        captureOnce(File(outPath))
        return START_NOT_STICKY
    }

    private fun captureOnce(out: File) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        thread = HandlerThread("mp-capture").also { it.start() }
        val handler = Handler(thread!!.looper)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "nova-shot",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            var image = reader.acquireLatestImage()
            try {
                if (image != null) {
                    val plane = image.planes[0]
                    val buf = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buf)
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    FileOutputStream(out).use { fos ->
                        cropped.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    bitmap.recycle()
                    cropped.recycle()
                    pendingCallback?.onSuccess(out)
                } else {
                    pendingCallback?.onError("未拿到 Image 帧")
                }
            } catch (e: Exception) {
                pendingCallback?.onError(e.message ?: "capture error")
            } finally {
                image?.close()
                pendingCallback = null
                cleanup()
                stopSelf()
            }
        }, handler)

        // Timeout fallback
        handler.postDelayed({
            if (pendingCallback != null) {
                pendingCallback?.onError("截图超时")
                pendingCallback = null
                cleanup()
                stopSelf()
            }
        }, 5000)
    }

    private fun cleanup() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { thread?.quitSafely() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        projection = null
        thread = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, AppMonitorApp.CHANNEL_CAPTURE)
            .setContentTitle("正在截图")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

    companion object {
        const val NOTIF_ID = 1002
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_OUT_PATH = "out_path"
        @Volatile var pendingCallback: ScreenshotHelper.Callback? = null
    }
}
