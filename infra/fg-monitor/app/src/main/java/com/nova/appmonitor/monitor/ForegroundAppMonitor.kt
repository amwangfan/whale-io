package com.nova.appmonitor.monitor

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.nova.appmonitor.AppMonitorApp
import com.nova.appmonitor.BuildConfig
import com.nova.appmonitor.MainActivity
import com.nova.appmonitor.R
import com.nova.appmonitor.model.FgAppInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polls foreground app via UsageStats; root flavor also tries dumpsys window.
 * AccessibilityService pushes events asynchronously when enabled.
 */
class ForegroundAppMonitor : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var lastPkg: String? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            executor.execute {
                val info = resolveForeground()
                if (info != null && info.packageName != lastPkg) {
                    lastPkg = info.packageName
                    handler.post { AppMonitorApp.instance.pushFg(info) }
                }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (running.compareAndSet(false, true)) {
            handler.post(tick)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        handler.removeCallbacks(tick)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun resolveForeground(): FgAppInfo? {
        if (BuildConfig.IS_ROOT) {
            dumpsysForeground()?.let { return it }
        }
        return usageStatsForeground()
    }

    private fun usageStatsForeground(): FgAppInfo? {
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val end = System.currentTimeMillis()
        val begin = end - 60_000L
        val events = usm.queryEvents(begin, end) ?: return null
        val event = UsageEvents.Event()
        var lastPkg: String? = null
        var lastTs = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                lastPkg = event.packageName
                lastTs = event.timeStamp
            }
        }
        val pkg = lastPkg ?: return null
        if (pkg == packageName) return null
        val label = labelOf(pkg)
        return FgAppInfo(pkg, label, if (lastTs > 0) lastTs else System.currentTimeMillis(), "usage_stats")
    }

    private fun dumpsysForeground(): FgAppInfo? {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys window"))
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line: String?
            var pkg: String? = null
            val mFocus = Regex("""mCurrentFocus=Window\{[^ ]+ [^ ]+ ([^/]+)/""")
            val mResume = Regex("""mResumedActivity:.*? ([^/ ]+)/""")
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                mFocus.find(l)?.groupValues?.getOrNull(1)?.let { pkg = it }
                mResume.find(l)?.groupValues?.getOrNull(1)?.let { pkg = it }
            }
            reader.close()
            proc.waitFor()
            val p = pkg ?: return null
            if (p == packageName || p == "null") return null
            FgAppInfo(p, labelOf(p), System.currentTimeMillis(), "dumpsys")
        } catch (_: Exception) {
            null
        }
    }

    private fun labelOf(pkg: String): String = try {
        val ai = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(ai).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, AppMonitorApp.CHANNEL_MONITOR)
            .setContentTitle(getString(R.string.monitor_notif_title))
            .setContentText(if (BuildConfig.IS_ROOT) "Root 模式" else "User 模式")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val NOTIF_ID = 1001
        const val POLL_MS = 1500L
    }
}
