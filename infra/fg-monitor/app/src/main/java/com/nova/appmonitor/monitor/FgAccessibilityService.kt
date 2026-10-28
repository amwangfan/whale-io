package com.nova.appmonitor.monitor

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.nova.appmonitor.AppMonitorApp
import com.nova.appmonitor.model.FgAppInfo

class FgAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val label = try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            pkg
        }
        AppMonitorApp.instance.pushFg(
            FgAppInfo(pkg, label, System.currentTimeMillis(), "accessibility")
        )
    }

    override fun onInterrupt() {}

    companion object {
        @Volatile var running: Boolean = false
    }

    override fun onServiceConnected() {
        running = true
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
