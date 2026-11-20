package com.turboio.addon;

import android.app.Activity;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

/** Diagnostics button for OfficialSummaryProbe: reads the single api_cache_user_*.hive read-only and writes a text file
 * of key names to Download/TurboIO. Nothing leaves the phone and no cache content is written. */
final class OfficialSummaryProbeUI {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern FILE = Pattern.compile("^api_cache_user_[0-9]+\\.hive$");
    private static volatile boolean busy;
    private OfficialSummaryProbeUI() {}

    static void run(Activity a) {
        if (Build.VERSION.SDK_INT < 29) { EditorialUI.notice(a, "需要 Android 10+（MediaStore.Downloads）"); return; }
        if (busy) { EditorialUI.notice(a, "进行中，请稍候"); return; }
        busy = true;
        new Thread(() -> {
            String message; Uri uri = null;
            try {
                File found = null; int count = 0;
                for (File d : CeBridge.dirs()) { File[] files = d.listFiles(); if (files == null) continue; for (File f : files) if (FILE.matcher(f.getName()).matches()) { found = f; count++; } }
                if (count == 0) throw new IllegalStateException("没有找到官方缓存文件（api_cache_user_*.hive）；请先在官方 App 打开一次智记事件后再试。");
                if (count > 1) throw new IllegalStateException("检测到多个官方账号缓存，为避免混入其他账号，已停止。");
                if (Files.isSymbolicLink(found.toPath()) || !found.isFile() || found.length() > CeHive.MAX_BYTES) throw new IllegalStateException("缓存文件不可只读打开或超过 32 MB。");
                long size = found.length(), modified = found.lastModified(); byte[] data;
                try (InputStream in = new FileInputStream(found)) { data = BoundedHTTP.read(in, CeHive.MAX_BYTES); }
                if (data.length != size || found.length() != size || found.lastModified() != modified) throw new IllegalStateException("官方缓存正在更新，请稍后重试；原文件未改动。");
                String report = OfficialSummaryProbe.report(data);
                String name = "TurboIO-official-cache-keys-" + System.currentTimeMillis() + ".txt";
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name); v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TurboIO"); v.put(MediaStore.Downloads.IS_PENDING, 1);
                uri = a.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("insert");
                try (OutputStream out = a.getContentResolver().openOutputStream(uri)) { if (out == null) throw new IOException("stream"); out.write(report.getBytes(StandardCharsets.UTF_8)); }
                v.clear(); v.put(MediaStore.Downloads.IS_PENDING, 0); a.getContentResolver().update(uri, v, null, null);
                message = "已导出键名报告（" + report.length() + " 字符，不含内容）：\nDownload/TurboIO/" + name;
            } catch (IllegalStateException | IllegalArgumentException e) {
                message = e.getMessage() == null ? "探针失败" : e.getMessage();
            } catch (Exception e) {
                if (uri != null) try { a.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                message = "探针失败（" + e.getClass().getSimpleName() + "）";
            }
            final String text = message;
            busy = false;
            MAIN.post(() -> EditorialUI.notice(a, text));
        }, "turboio-summary-probe").start();
    }
}
