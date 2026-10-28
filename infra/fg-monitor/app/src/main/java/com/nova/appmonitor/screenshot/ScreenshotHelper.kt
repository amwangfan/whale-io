package com.nova.appmonitor.screenshot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Environment
import android.util.Log
import com.nova.appmonitor.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Root: `su -c screencap -p <path>`
 * Non-root: MediaProjection (requires user consent Intent each session)
 */
class ScreenshotHelper(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()

    interface Callback {
        fun onSuccess(file: File)
        fun onError(message: String)
        /** Non-root only: activity should start this for result. */
        fun onNeedProjection(intent: Intent) {}
    }

    fun capture(activity: Activity?, callback: Callback) {
        if (BuildConfig.IS_ROOT) {
            captureRoot(callback)
        } else {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            callback.onNeedProjection(mpm.createScreenCaptureIntent())
        }
    }

    fun captureRoot(callback: Callback) {
        executor.execute {
            try {
                val out = outputFile()
                val cmd = "screencap -p ${out.absolutePath} && chmod 644 ${out.absolutePath}"
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val code = proc.waitFor()
                if (code == 0 && out.exists() && out.length() > 0) {
                    callback.onSuccess(out)
                } else {
                    val err = proc.errorStream.bufferedReader().readText()
                    callback.onError("screencap failed code=$code $err")
                }
            } catch (e: Exception) {
                Log.e(TAG, "root capture", e)
                callback.onError(e.message ?: "root capture error")
            }
        }
    }

    fun handleProjectionResult(resultCode: Int, data: Intent?, callback: Callback) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            callback.onError("用户取消 MediaProjection 授权")
            return
        }
        val out = outputFile()
        val intent = Intent(context, MediaProjectionService::class.java).apply {
            putExtra(MediaProjectionService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MediaProjectionService.EXTRA_RESULT_DATA, data)
            putExtra(MediaProjectionService.EXTRA_OUT_PATH, out.absolutePath)
        }
        MediaProjectionService.pendingCallback = callback
        context.startForegroundService(intent)
    }

    private fun outputFile(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val name = "shot_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        return File(dir, name)
    }

    companion object {
        private const val TAG = "ScreenshotHelper"
    }
}
