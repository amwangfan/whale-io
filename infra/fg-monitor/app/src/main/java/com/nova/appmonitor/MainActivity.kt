package com.nova.appmonitor

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nova.appmonitor.model.FgAppInfo
import com.nova.appmonitor.monitor.FgAccessibilityService
import com.nova.appmonitor.monitor.ForegroundAppMonitor
import com.nova.appmonitor.screenshot.ScreenshotHelper
import com.nova.appmonitor.ui.HistoryAdapter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var tvMode: TextView
    private lateinit var tvFgLabel: TextView
    private lateinit var tvFgPackage: TextView
    private lateinit var tvFgTime: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnScreenshot: Button
    private lateinit var adapter: HistoryAdapter
    private lateinit var shotHelper: ScreenshotHelper
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>

    private var monitoring = false
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val shotCb = object : ScreenshotHelper.Callback {
        override fun onSuccess(file: File) {
            runOnUiThread {
                tvStatus.text = "截图已保存: ${file.absolutePath}"
                Toast.makeText(this@MainActivity, "截图成功", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onError(message: String) {
            runOnUiThread {
                tvStatus.text = "截图失败: $message"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }

        override fun onNeedProjection(intent: Intent) {
            projectionLauncher.launch(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            shotHelper.handleProjectionResult(result.resultCode, result.data, shotCb)
        }

        tvMode = findViewById(R.id.tvMode)
        tvFgLabel = findViewById(R.id.tvFgLabel)
        tvFgPackage = findViewById(R.id.tvFgPackage)
        tvFgTime = findViewById(R.id.tvFgTime)
        tvStatus = findViewById(R.id.tvStatus)
        btnToggle = findViewById(R.id.btnToggle)
        btnScreenshot = findViewById(R.id.btnScreenshot)
        val rv = findViewById<RecyclerView>(R.id.rvHistory)
        adapter = HistoryAdapter()
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        shotHelper = ScreenshotHelper(this)

        tvMode.text = if (BuildConfig.IS_ROOT) {
            "模式: ROOT · screencap / dumpsys 可用"
        } else {
            "模式: USER · UsageStats + Accessibility + MediaProjection"
        }

        btnToggle.setOnClickListener {
            if (monitoring) stopMonitor() else startMonitor()
        }
        btnScreenshot.setOnClickListener {
            tvStatus.text = "正在截图…"
            shotHelper.capture(this, shotCb)
        }
        findViewById<Button>(R.id.btnUsage).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        AppMonitorApp.instance.onFgChanged = { info ->
            runOnUiThread { render(info) }
        }
        AppMonitorApp.instance.current?.let { render(it) }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        adapter.submit(AppMonitorApp.instance.history.toList())
    }

    override fun onDestroy() {
        AppMonitorApp.instance.onFgChanged = null
        super.onDestroy()
    }

    private fun startMonitor() {
        if (!hasUsageAccess()) {
            tvStatus.text = "需要「使用情况访问」权限"
            Toast.makeText(this, "请先授予使用情况权限", Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(Intent(this, ForegroundAppMonitor::class.java))
        monitoring = true
        btnToggle.setText(R.string.btn_stop)
        tvStatus.text = "监测已启动" + if (isAccessibilityEnabled()) "（含无障碍）" else "（建议开启无障碍）"
    }

    private fun stopMonitor() {
        stopService(Intent(this, ForegroundAppMonitor::class.java))
        monitoring = false
        btnToggle.setText(R.string.btn_start)
        tvStatus.text = "监测已停止"
    }

    private fun render(info: FgAppInfo) {
        tvFgLabel.text = info.label
        tvFgPackage.text = info.packageName
        tvFgTime.text = "${timeFmt.format(Date(info.timestampMs))} · ${info.source}"
        adapter.submit(AppMonitorApp.instance.history.toList())
    }

    private fun refreshStatus() {
        val usage = if (hasUsageAccess()) "Usage✓" else "Usage✗"
        val a11y = if (isAccessibilityEnabled()) "A11y✓" else "A11y✗"
        tvStatus.text = "权限: $usage  $a11y"
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, FgAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val cn = ComponentName.unflattenFromString(splitter.next())
            if (cn != null && cn == expected) return true
        }
        return false
    }
}
