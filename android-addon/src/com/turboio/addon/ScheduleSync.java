package com.turboio.addon;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Periodically pushes the today+tomorrow schedule snapshot to the core schedule-memory endpoint
 *  (contract: docs/enxi-realtime-phone-design.md §2, co-signed with the core side 2026-11-17).
 *  Rides the WhaleSyncScheduler loop (same gates: switch, token, network, battery). Hash-gated: pushes only
 *  when the snapshot changed, plus once per local date. Full-snapshot replace semantics; failures are not
 *  retried (the next tick tries again). Schedule CONTENT is never logged (privacy parity with lifelog). */
final class ScheduleSync {
    private ScheduleSync() {}

    private static volatile String lastStatus = "尚未推送";
    private static volatile int lastHttp = 0;            // last push HTTP status (0 = never / no response)
    private static volatile int lastEvents = -1;         // today+tomorrow entries in the last snapshot (-1 = unread)
    static String status() { return lastStatus + (lastHttp > 0 ? "（HTTP " + lastHttp + "）" : ""); }

    /** Runs on the whale scheduler thread after each sync body. Cheap no-op when nothing changed. */
    static void tick(Context app, long nowMs) {
        try {
            List<String[]> t0 = ChatContext.events(app, 0), t1 = ChatContext.events(app, 1);
            String json = snapshot(app);
            int n0 = t0 == null ? -1 : t0.size(), n1 = t1 == null ? -1 : t1.size();
            lastEvents = n0 < 0 || n1 < 0 ? -1 : n0 + n1;
            String hash = sha256(json);
            SharedPreferences p = app.getSharedPreferences("turboio_schedule_sync", 0);   // no_backup by platform
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(nowMs));
            String last = p.getString("hash", "");
            boolean due = due(hash, day, last, p.getString("day", ""));
            /* Observability (2026-11-18, live-integration blind spot): one line per tick — counts and HTTP status
             *  only, never schedule content. */
            android.util.Log.i("TurboIOTransfer", "schedule-sync tick day=" + day + " today=" + n0 + " tomorrow=" + n1 + " due=" + due + " lastHttp=" + lastHttp);
            if (!due) return;
            WhaleHttp.Result r = WhaleHttp.post(WhaleEndpoint.join(WhaleConfig.base(app), "/v1/context/sync"),
                    WhaleConfig.token(app), json, 10000, 30000, false);
            lastHttp = r.status;
            if (r.ok()) {
                p.edit().putString("hash", hash).putString("day", day).apply();
                lastStatus = "日程快照已同步";
                android.util.Log.i("TurboIOTransfer", "schedule-sync pushed ok http=" + r.status);
            } else if (r.auth()) {
                lastStatus = "日程推送鉴权失败（下次同步自动重试）";
                android.util.Log.w("TurboIOTransfer", "schedule-sync auth failed http=" + r.status);
            } else {
                lastStatus = "日程推送未成功（" + r.label() + "；下次同步自动重试）";
                android.util.Log.w("TurboIOTransfer", "schedule-sync failed http=" + r.status + " class=" + (r.errorClass == null ? "" : r.errorClass));
            }
        } catch (Throwable ignored) { }
    }

    /** Push gate: only when the snapshot changed, or the local date rolled over. */
    static boolean due(String hash, String day, String lastHash, String lastDay) {
        return !hash.equals(lastHash) || !day.equals(lastDay);
    }

    /** The contract payload (whale-core v2 /v1/context/sync, schema co-signed 2026-11-18): a bare calendar map —
     *  today and tomorrow as ISO date keys (Asia/Shanghai anchored server-side); [] = known-empty, null = unreadable.
     *  Events carry start ("HH:mm")/title/allDay only. */
    static String snapshot(Context app) {
        StringBuilder s = new StringBuilder("{\"calendar\":{");
        dayJson(s, isoDay(0), ChatContext.events(app, 0));
        s.append(',');
        dayJson(s, isoDay(1), ChatContext.events(app, 1));
        s.append("}}");
        return s.toString();
    }

    private static String isoDay(int offset) {
        Calendar c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, offset);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(c.getTime());
    }

    private static void dayJson(StringBuilder s, String key, List<String[]> evs) {
        s.append('"').append(key).append("\":");
        if (evs == null) { s.append("null"); return; }   // unreadable (no permission / provider absent)
        s.append('[');
        for (int i = 0; i < evs.size(); i++) {
            String[] e = evs.get(i);
            if (i > 0) s.append(',');
            s.append('{');
            if (!e[0].isEmpty()) s.append("\"start\":\"").append(e[0]).append("\",");
            s.append("\"title\":\"").append(esc(e[1])).append("\"");
            if (e[2].equals("1")) s.append(",\"allDay\":true");
            s.append('}');
        }
        s.append(']');
    }

    private static String esc(String s) {
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') o.append('\\').append(c);
            else if (c >= 0x20 && c < 0x7f) o.append(c);
            else if (c < 0x20) o.append(' ');
            else o.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
        }
        return o.toString();
    }

    private static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format(Locale.ROOT, "%02x", x & 255));
            return b.toString();
        } catch (Exception e) { return ""; }
    }
}
