package com.turboio.addon;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Settings → 雷鸟上下文 · whale. Master switch is off by default; the first enable needs the privacy confirmation. */
final class WhaleSyncUI {
    static final String PRIVACY = "开启后，本机会把眼镜全天对话的转写原文（你说的和 AI 回答的，可能包含他人的声音和敏感信息）按批上传到你配置的 whale-core 服务，"
        + "服务端会保存原文，并用第三方大模型做总结。\n\n"
        + "• 你可以随时暂停；“清除云端副本”会真删云端原文和派生总结。\n"
        + "• 默认仅 Wi-Fi 上传；电量低于 20% 且未充电时暂停。\n"
        + "• 首版不会上传官方总结，也不会上传录音文件。\n"
        + "• 查询开启后，模型工具可读取你的私人与群记忆摘要。\n\n确认开启？";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private WhaleSyncUI() {}

    static void show(Activity a) {
        EditorialUI.Screen s = new EditorialUI.Screen(a, "雷鸟上下文 · whale");
        LinearLayout b = s.body;
        final android.content.Context c = a.getApplicationContext();
        final android.content.SharedPreferences p = WhaleConfig.prefs(c);
        b.addView(EditorialUI.text(a, "把眼镜全天转写上传到你自己的 whale-core，并让模型能查询“我今天说过什么”。默认全部关闭。", 13, EditorialUI.MUTED));

        final boolean[] binding = {false};
        final Switch[] master = new Switch[1];
        master[0] = EditorialUI.toggle(a, b, "采集并上传转写（总开关）", WhaleConfig.enabled(c) && WhaleConfig.acknowledged(c), (v, on) -> {
            if (binding[0]) return;
            if (!on) { p.edit().putBoolean("enabled", false).apply(); return; }
            binding[0] = true; master[0].setChecked(false); binding[0] = false;
            EditorialUI.confirm(a, "隐私确认", PRIVACY, () -> {
                p.edit().putBoolean("enabled", true).putString("privacy_ack", WhaleConfig.PRIVACY_VERSION).apply();
                binding[0] = true; master[0].setChecked(true); binding[0] = false;
                if (!WhaleConfig.hasToken(c)) EditorialUI.notice(a, "已开启，但还没有填写“whale 眼镜 token”，在填写之前不会采集或上传。");
            });
        });
        EditorialUI.toggle(a, b, "仅 Wi-Fi 上传", WhaleConfig.wifiOnly(c), (v, on) -> p.edit().putBoolean("wifi_only", on).apply());
        EditorialUI.toggle(a, b, "允许模型查询（rayneo_context_query）", WhaleConfig.queryOn(c), (v, on) -> p.edit().putBoolean("query", on).apply());
        EditorialUI.toggle(a, b, "上传官方总结（占位，暂未实现）", WhaleConfig.summaryOn(c), (v, on) -> { p.edit().putBoolean("summary", on).apply(); if (on) EditorialUI.notice(a, "官方总结上传尚未实现：先用“诊断 → 官方缓存键名探针”确认字段后才会接入。此开关目前不起作用。"); });

        b.addView(EditorialUI.text(a, "服务地址（HTTPS，可带路径前缀）", 14, EditorialUI.INK));
        EditText base = field(a, b, WhaleConfig.base(c), false, "https://your-core-host.example.com/turbo");
        b.addView(EditorialUI.text(a, "whale 眼镜 token", 14, EditorialUI.INK));
        EditText token = field(a, b, "", true, WhaleConfig.hasToken(c) ? "已保存（留空保留）" : "粘贴 token");
        TextView note = EditorialUI.text(a, "", 13, EditorialUI.LIME);
        EditorialUI.button(a, b, "保存地址与 token", true, () -> {
            String v = WhaleEndpoint.normalize(base.getText().toString());
            if (!WhaleConfig.validBase(v)) { note.setText("地址无效：需要 https://…（不含账号、参数或 #）"); return; }
            try {
                boolean changed = token.length() > 0;
                if (changed) SecretStore.put(c, WhaleConfig.TOKEN, token.getText().toString().trim());
                p.edit().putString("base", v).apply();
                token.setText("");
                note.setText("已保存。" + (changed ? "token 已更新，继续上传。" : ""));
                if (changed) WhaleSyncScheduler.resume(c);
            } catch (Exception e) { note.setText("保存失败"); }
        });
        b.addView(note);

        b.addView(EditorialUI.text(a, "状态", 17, EditorialUI.INK));
        TextView status = EditorialUI.text(a, "", 13, EditorialUI.INK); b.addView(status);
        final boolean[] paused = {WhaleConfig.paused(c, System.currentTimeMillis())};
        TextView[] pauseButton = new TextView[1];
        pauseButton[0] = EditorialUI.button(a, b, paused[0] ? "继续采集与上传" : "暂停采集与上传", false, () -> {
            paused[0] = !paused[0];
            p.edit().putLong("paused_until", paused[0] ? Long.MAX_VALUE : 0).apply();
            pauseButton[0].setText(paused[0] ? "继续采集与上传" : "暂停采集与上传");
        });
        EditorialUI.button(a, b, "立即同步 / 继续上传", false, () -> { note.setText("正在同步…"); WhaleSyncScheduler.resume(c); });
        EditorialUI.button(a, b, "检查连接（不显示内容）", false, () -> { note.setText("正在检查…"); new Thread(() -> { String r = RayneoContextClient.checkConnection(c); MAIN.post(() -> note.setText(r)); }, "turboio-whale-check").start(); });
        EditorialUI.button(a, b, "清空本机待传队列", false, () -> EditorialUI.confirm(a, "清空本机队列", "本机还没上传的转写会被丢弃，不影响云端已有内容。", () -> { WhaleSyncScheduler.clearLocalQueue(c); note.setText("已清空本机待传队列"); }));
        EditorialUI.button(a, b, "清除云端副本（真删）", false, () -> EditorialUI.confirm(a, "清除云端副本", "将删除 whale-core 上保存的全部雷鸟原文和由它们派生的总结，并清空本机待传队列。不可恢复。", () -> { note.setText("正在清除…"); WhaleSyncScheduler.clearCloud(c, r -> MAIN.post(() -> note.setText(r))); }));
        b.addView(EditorialUI.text(a, "说明：采集挂在官方 AlwaysOnController 的回调上，只读文字；角色字段、finished 频率和 roundId 复用方式需真机确认。后台同步依赖宿主进程存活，队列存在本机 SQLite，进程回来后继续。", 12, EditorialUI.MUTED));
        b.addView(EditorialUI.text(a, "日程感知（Enxi 实时事实）：今日+明日日程快照同步到云端日程记忆，供 Enxi 应答时引用。来源：系统日历（未录入系统日历的日程读不到）；内容变化才推送，不重发。", 12, EditorialUI.MUTED));

        Runnable tick = new Runnable() { public void run() { if (!s.dialog.isShowing()) return; status.setText(WhaleSyncScheduler.statusLine(c) + "\n日程快照：" + ScheduleSync.status()); MAIN.postDelayed(this, 3000); } };
        MAIN.post(tick);
        s.dialog.setOnDismissListener(d -> MAIN.removeCallbacks(tick));
    }

    private static EditText field(Activity a, LinearLayout parent, String value, boolean secret, String hint) {
        EditText e = new EditText(a);
        e.setTextColor(EditorialUI.INK); e.setHintTextColor(EditorialUI.MUTED); e.setTextSize(15); e.setSingleLine(true);
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setText(value); e.setHint(hint); e.setBackground(EditorialUI.shape(a, EditorialUI.SURFACE, 16));
        e.setPadding(EditorialUI.dp(a, 14), EditorialUI.dp(a, 12), EditorialUI.dp(a, 14), EditorialUI.dp(a, 12));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = EditorialUI.dp(a, 6); parent.addView(e, p);
        return e;
    }
}
