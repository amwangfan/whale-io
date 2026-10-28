package com.nova.appmonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.nova.appmonitor.model.FgAppInfo
import java.util.concurrent.CopyOnWriteArrayList

class AppMonitorApp : Application() {
    val history = CopyOnWriteArrayList<FgAppInfo>()
    @Volatile var current: FgAppInfo? = null

    var onFgChanged: ((FgAppInfo) -> Unit)? = null

    fun pushFg(info: FgAppInfo) {
        val prev = current
        if (prev != null && prev.packageName == info.packageName) {
            current = info
            onFgChanged?.invoke(info)
            return
        }
        current = info
        history.add(0, info)
        while (history.size > 100) history.removeAt(history.lastIndex)
        onFgChanged?.invoke(info)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MONITOR,
                    getString(R.string.monitor_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CAPTURE,
                    "截图服务",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        const val CHANNEL_MONITOR = "fg_monitor"
        const val CHANNEL_CAPTURE = "media_projection"
        lateinit var instance: AppMonitorApp
            private set
    }
}
