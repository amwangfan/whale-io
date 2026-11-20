package com.turboio.addon;

import android.content.Context;
import java.util.Map;
import java.util.UUID;

/** Query/control half of the whale-core integration (the ingest half is WhaleSyncCore). Timeouts for the model tool are
 * 4 s connect / 6 s read; failures degrade to an empty labeled result and never throw into the conversation.
 * Adapted in structure from Turbo1123/Turbo-IO PR #21 (head 2362d19, PolyForm Noncommercial 1.0.0, original author). */
final class RayneoContextClient {
    static final int QUERY_CONNECT_MS = WhaleEndpoint.QUERY_CONNECT_MS, QUERY_READ_MS = WhaleEndpoint.QUERY_READ_MS, CONTROL_CONNECT_MS = WhaleEndpoint.CONTROL_CONNECT_MS, CONTROL_READ_MS = WhaleEndpoint.CONTROL_READ_MS;
    private RayneoContextClient() {}

    static boolean queryReady(Context c) { return WhaleConfig.queryOn(c) && WhaleConfig.hasToken(c) && WhaleConfig.validBase(WhaleConfig.base(c)); }

    private static RayneoContextProtocol.Poster poster(Context c, int connectMs, int readMs) {
        final String base = WhaleConfig.base(c), token = WhaleConfig.token(c);
        return (path, body) -> {
            WhaleHttp.Result r = WhaleHttp.post(WhaleEndpoint.join(base, path), token, body, connectMs, readMs, false);
            if (r.auth()) WhaleSyncScheduler.noteAuthError(c, r.label());
            return RayneoContextProtocol.fromTransport(r);
        };
    }

    /** Result of the rayneo_context_query tool as a JSON string. Never throws. */
    static String queryTool(Context c, String query) {
        long now = System.currentTimeMillis();
        try {
            Map<String, Object> envelope = RayneoQueryBridge.invoke(RayneoQueryBridge.TOOL_NAME, query, poster(c, QUERY_CONNECT_MS, QUERY_READ_MS), queryReady(c), RayneoContextProtocol.QUERY_LIMIT_DEFAULT, now);
            return RayneoContextProtocol.json(envelope);
        } catch (RuntimeException e) {
            return RayneoContextProtocol.json(RayneoQueryBridge.emptyFallback("offline", now));
        }
    }

    /** Settings "检查连接": one query with a throw-away segment id; shows only a status, never server content. */
    static String checkConnection(Context c) {
        if (!queryReady(c)) return "请先填写 token、保存有效的 HTTPS 地址并打开“允许模型查询”";
        String body = "{\"source\":\"rayneo\",\"segment_id\":\"auth-diag-" + UUID.randomUUID() + "\",\"limit\":1,\"max_text_chars\":1,\"max_total_tokens\":1,\"include_derived\":false}";
        WhaleHttp.Result r = WhaleHttp.post(WhaleEndpoint.join(WhaleConfig.base(c), WhaleEndpoint.QUERY), WhaleConfig.token(c), body, CONTROL_CONNECT_MS, CONTROL_READ_MS, false);
        if (!r.ok()) { if (r.auth()) WhaleSyncScheduler.noteAuthError(c, r.label()); return "失败：" + (r.label().isEmpty() ? "网络不可达" : r.label()); }
        try {
            Object parsed = BoundedJson.readLarge(r.body.getBytes(java.nio.charset.StandardCharsets.UTF_8), 1 << 20, 50000);
            Map<?, ?> m = (Map<?, ?>) parsed;
            if (Boolean.TRUE.equals(m.get("query_disabled"))) return "HTTP 200；鉴权通过；服务端已关闭查询";
            boolean ok = Boolean.TRUE.equals(m.get("ok")) && "rayneo".equals(m.get("source")) && m.get("items") instanceof java.util.List && ((java.util.List<?>) m.get("items")).isEmpty();
            return ok ? "HTTP 200；鉴权通过；指定测试标识无记录" : "响应不符合约定；未显示内容";
        } catch (RuntimeException e) { return "响应不符合约定；未显示内容"; }
    }

    /** control: delete_range / purge_all / stop_ingest / resume_ingest. Returns a short status, never server text. */
    static String control(Context c, String action, String from, String to) {
        if (!WhaleConfig.hasToken(c) || !WhaleConfig.validBase(WhaleConfig.base(c))) return "未配置 token 或地址";
        WhaleHttp.Result r = WhaleHttp.post(WhaleEndpoint.join(WhaleConfig.base(c), WhaleEndpoint.CONTROL), WhaleConfig.token(c), RayneoContextProtocol.controlJson(action, from, to), CONTROL_CONNECT_MS, CONTROL_READ_MS, false);
        if (!r.ok()) { if (r.auth()) WhaleSyncScheduler.noteAuthError(c, r.label()); return "失败：" + (r.label().isEmpty() ? "网络不可达" : r.label()); }
        try {
            Map<?, ?> m = (Map<?, ?>) BoundedJson.readLarge(r.body.getBytes(java.nio.charset.StandardCharsets.UTF_8), 1 << 20, 5000);
            if (!Boolean.TRUE.equals(m.get("ok"))) return "失败：响应不符合约定";
            Object seg = m.get("deleted_segments"), ep = m.get("deleted_episodes");
            return "已完成" + (seg instanceof Number ? "；删除原文 " + seg + " 条" : "") + (ep instanceof Number ? "，派生总结 " + ep + " 条" : "");
        } catch (RuntimeException e) { return "失败：响应不符合约定"; }
    }
}
