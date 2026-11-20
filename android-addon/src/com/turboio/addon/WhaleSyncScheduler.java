package com.turboio.addon;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Android side of the whale sync: owns the outbox/capture/core singletons, one background thread, the gates
 * (switches, token, pause, Wi-Fi only, battery < 20 % unless charging) and the triggers (after a capture burst, every
 * 5 minutes while the host process is alive, and the manual "立即同步"). It never touches the UI thread. The host process
 * keeping alive in the background is NOT guaranteed (待核); queued rows are durable in SQLite and resume next time. */
final class WhaleSyncScheduler {
    private static WhaleOutboxSqlite sql;
    private static WhaleOutbox outbox;
    private static WhaleLiveCapture capture;
    private static WhaleSyncCore core;
    private static ScheduledExecutorService exec;
    private static volatile String lastStatus = "尚未同步", lastGate = "";
    private static volatile long lastSyncMs;
    private static boolean syncQueued, tickQueued;

    private WhaleSyncScheduler() {}

    /** Application context: the add-on's when installed, else the host Application (always-on callbacks can fire before any Activity). */
    static Context context() {
        Context c = TurboAddon.appContext();
        if (c != null) return c;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? ((Context) app).getApplicationContext() : null;
        } catch (Throwable ignored) { return null; }
    }

    private static synchronized void ensure(Context c) {
        if (exec != null) return;
        sql = new WhaleOutboxSqlite(c.getApplicationContext());
        outbox = new WhaleOutbox(sql);
        final String instance = WhaleConfig.instance(c);
        capture = new WhaleLiveCapture(outbox, instance, TimeZone.getDefault().getID());
        final Context app = c.getApplicationContext();
        core = new WhaleSyncCore(outbox, (path, body) -> WhaleHttp.post(WhaleEndpoint.join(WhaleConfig.base(app), path), WhaleConfig.token(app), body, 10000, 30000, false), instance, new Random());
        exec = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() { public Thread newThread(Runnable r) { Thread t = new Thread(r, "turboio-whale"); t.setDaemon(true); return t; } });
        exec.scheduleWithFixedDelay(() -> { try { syncBody(app, false); } catch (Throwable ignored) { } }, 2, 5, TimeUnit.MINUTES);
    }

    // ---- capture entry points (called from WhaleCaptureHook on the host's callback thread; they only enqueue work) ----
    static void onResponse(String role, String roundId, String text, boolean finished) {
        Context c = context();
        if (c == null) return;
        final long now = System.currentTimeMillis();
        if (!WhaleConfig.captureAllowed(c, now)) return;
        ensure(c);
        exec.execute(() -> { try { capture.onResponse(role, roundId, text, finished, now); afterCapture(c, finished); } catch (Throwable ignored) { } });
    }
    static void onStart() {
        Context c = context(); if (c == null) return;
        final long now = System.currentTimeMillis();
        if (!WhaleConfig.captureAllowed(c, now)) return;
        ensure(c);
        exec.execute(() -> { try { capture.onAudioStart(now); } catch (Throwable ignored) { } });
    }
    static void onEnd() {
        Context c = context(); if (c == null) return;
        if (!WhaleConfig.captureAllowed(c, System.currentTimeMillis())) return;
        ensure(c);
        exec.execute(() -> { try { capture.onEnd(System.currentTimeMillis()); scheduleSync(c, 20); } catch (Throwable ignored) { } });
    }

    private static synchronized void afterCapture(Context c, boolean finished) {
        if (capture.hasPartials() && !tickQueued) {
            tickQueued = true;
            exec.schedule(() -> { synchronized (WhaleSyncScheduler.class) { tickQueued = false; } capture.tick(System.currentTimeMillis()); afterCapture(c, false); }, 6, TimeUnit.SECONDS);
        }
        if (finished) scheduleSync(c, 30);
    }

    private static synchronized void scheduleSync(Context c, int delaySeconds) {
        if (syncQueued) return;
        syncQueued = true;
        exec.schedule(() -> { synchronized (WhaleSyncScheduler.class) { syncQueued = false; } try { syncBody(c, false); } catch (Throwable ignored) { } }, delaySeconds, TimeUnit.SECONDS);
    }

    // ---- gates ----
    /** "" when uploading is allowed now, else a short reason code. */
    static String gate(Context c, long now, boolean manual) {
        if (!WhaleConfig.enabled(c)) return "disabled";
        if (!WhaleConfig.acknowledged(c)) return "no_ack";
        if (!WhaleConfig.hasToken(c)) return "no_token";
        if (!WhaleConfig.validBase(WhaleConfig.base(c))) return "bad_base";
        if (WhaleConfig.paused(c, now)) return "paused";
        if (!manual && !networkOk(c, WhaleConfig.wifiOnly(c))) return WhaleConfig.wifiOnly(c) ? "wifi" : "offline";
        if (manual && !networkOk(c, false)) return "offline";
        if (!manual && lowBattery(c)) return "battery";
        return "";
    }

    static boolean networkOk(Context c, boolean wifiOnly) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork(); if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
            if (!wifiOnly) return true;
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        } catch (Throwable e) { return false; }
    }

    static boolean lowBattery(Context c) {
        try {
            Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return false;
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100), status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            return level >= 0 && scale > 0 && level * 100 / scale < 20 && !charging;
        } catch (Throwable e) { return false; }
    }

    // ---- sync ----
    private static void syncBody(Context c, boolean manual) {
        ensure(c);
        long now = System.currentTimeMillis();
        capture.tick(now);
        String g = gate(c, now, manual);
        lastGate = g;
        if (!g.isEmpty()) { lastStatus = describeGate(g); return; }
        WhaleSyncCore.Report r = core.runOnce(now, 20);
        lastSyncMs = now;
        lastStatus = describe(r);
        // Enxi realtime facts (2026-11-17): hash-gated schedule push to the schedule-memory endpoint, and a
        // weather re-read of the glasses dashboard when the cache is older than 30 minutes and glasses are up.
        try { ScheduleSync.tick(c, now); } catch (Throwable ignored) { }
        try {
            if (ChatContext.weatherAge() > 30L * 60 * 1000 && !NativeTransfer.connected().isEmpty()
                    && !OtaController.busy() && !OfficialOtaBridge.busy() && !NativeTransfer.busy()) CardTransport.inspect();
        } catch (Throwable ignored) { }
    }

    /** Manual "立即同步" (ignores Wi-Fi-only and battery gates but still needs a network). */
    static void syncNow(Context c, Runnable done) {
        ensure(c);
        exec.execute(() -> { try { syncBody(c, true); } catch (Throwable ignored) { } if (done != null) done.run(); });
    }

    static void noteAuthError(Context c, String label) {
        try { ensure(c); outbox.store().meta("auth_error", label); outbox.store().meta("last_error", label); lastStatus = describe("auth_paused", label); } catch (Throwable ignored) { }
    }

    /** After a token change or "继续上传": lifts the 401 pause and tries again. */
    static void resume(Context c) {
        ensure(c);
        exec.execute(() -> { core.resumeAuth(); try { syncBody(c, true); } catch (Throwable ignored) { } });
    }

    /** control purge_all, then drop the local queue (including anything not yet uploaded). */
    static void clearCloud(Context c, java.util.function.Consumer<String> done) {
        ensure(c);
        exec.execute(() -> {
            String r = RayneoContextClient.control(c, "purge_all", null, null);
            boolean ok = r.startsWith("已完成");
            if (ok) outbox.clearAll();
            done.accept(ok ? "云端副本已清除；" + r + "；本机待传队列已清空" : r + "（本机队列未清空，请重试）");
        });
    }

    /** Stop capturing and drop only the local, not-yet-uploaded rows (does not touch the cloud). */
    static void clearLocalQueue(Context c) { ensure(c); exec.execute(() -> outbox.clearAll()); }

    // ---- status text (content-free) ----
    static String statusLine(Context c) {
        try {
            ensure(c);
            String auth = outbox.store().meta("auth_error");
            String hint = auth != null && !auth.isEmpty() ? "\n⚠ " + auth + "：token 无效或已更换，上传已暂停；更新“whale 眼镜 token”后点“继续上传”。" : "";
            long age = lastSyncMs == 0 ? -1 : (System.currentTimeMillis() - lastSyncMs) / 1000;
            return "待传 " + outbox.pending() + " · 已确认 " + outbox.acked() + " · 终态丢弃 " + outbox.dead() + (age < 0 ? "" : " · 距上次同步 " + age + " 秒") + "\n" + lastStatus + hint;
        } catch (Throwable e) { return "状态不可用"; }
    }

    static String describeGate(String g) {
        switch (g) {
            case "disabled": return "总开关关闭，不采集、不上传";
            case "no_ack": return "尚未确认隐私说明，不采集、不上传";
            case "no_token": return "未填写 whale 眼镜 token";
            case "bad_base": return "服务地址无效（需要 HTTPS）";
            case "paused": return "已暂停（采集与上传均停止）";
            case "wifi": return "等待 Wi-Fi（仅 Wi-Fi 上传已开启）";
            case "offline": return "无网络，队列保留，恢复后继续";
            case "battery": return "电量低于 20% 且未充电，暂停上传";
            default: return g;
        }
    }
    static String describe(WhaleSyncCore.Report r) { return describe(r.status, r.error) + (r.batches > 0 ? "（本次 " + r.sent + " 条，确认 " + r.acked + "，丢弃 " + r.dead + "）" : ""); }
    static String describe(String status, String error) {
        switch (status) {
            case "idle": return "没有待传内容";
            case "ok": return "上次同步成功";
            case "waiting": return "退避等待中（" + error + "）";
            case "auth_paused": return "鉴权失败，已暂停（" + error + "）";
            case "server_disabled": return "服务端已关闭 ingest，1 小时后重试";
            case "rate_limited": return "服务端限速，稍后重试";
            case "offline": return "网络不可达，稍后重试";
            case "timeout": return "超时，稍后重试";
            case "protocol_error": return "响应不符合约定（" + error + "），稍后重试";
            default: return "服务端错误（" + error + "），稍后重试";
        }
    }
}
