package com.turboio.addon;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** rayneo-context/v1 wire helpers: constants, request builders, a tiny JSON writer, hashing and ISO time.
 * Query-side shapes are adapted from Turbo1123/Turbo-IO PR #21 (head 2362d19, PolyForm Noncommercial 1.0.0,
 * original author's work). The PR's 256-bound window queue, capture support and window delivery are NOT used:
 * all-day capture goes through WhaleOutbox. Paths follow the whale-core handoff (/v1/rayneo/ingest|query|control),
 * not the PR's bare /ingest. */
public final class RayneoContextProtocol {
    public static final String CONTRACT_VERSION = "rayneo-context/v1";
    public static final String SOURCE = "rayneo";
    public static final String INGEST_PATH = WhaleEndpoint.INGEST, QUERY_PATH = WhaleEndpoint.QUERY, CONTROL_PATH = WhaleEndpoint.CONTROL;
    public static final int QUERY_LIMIT_DEFAULT = 8, QUERY_LIMIT_MAX = 50;
    private RayneoContextProtocol() {}

    public interface Poster { HttpResult post(String path, String jsonBody); }

    public static final class HttpResult {
        public final int status;
        public final String body;
        public final String errorClass;   // null when ok
        HttpResult(int status, String body, String errorClass) { this.status = status; this.body = body == null ? "" : body; this.errorClass = errorClass; }
        public boolean ok() { return errorClass == null && status >= 200 && status < 300; }
        public static HttpResult success(int status, String body) { return new HttpResult(status, body, null); }
        public static HttpResult error(String errorClass) { return new HttpResult(0, "", errorClass); }
        public static HttpResult unconfigured() { return error("unconfigured"); }
        public static HttpResult timeout() { return error("timeout"); }
        public static HttpResult offline() { return error("offline"); }
        public static HttpResult permission() { return error("permission"); }
    }

    /** Maps a transport result to the query-side classes: auth -> permission, 429/5xx/other -> http, passthrough network classes. */
    static HttpResult fromTransport(WhaleHttp.Result r) {
        if (r.ok()) return HttpResult.success(r.status, r.body);
        if (r.auth()) return HttpResult.permission();
        if ("timeout".equals(r.errorClass) || "offline".equals(r.errorClass)) return HttpResult.error(r.errorClass);
        return HttpResult.error("http");
    }

    public static Map<String, Object> queryRequest(String topic, int limit) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("source", SOURCE);
        body.put("topic", topic);
        body.put("limit", Integer.valueOf(Math.max(1, Math.min(QUERY_LIMIT_MAX, limit <= 0 ? QUERY_LIMIT_DEFAULT : limit))));
        body.put("max_text_chars", Integer.valueOf(2000));
        body.put("max_total_tokens", Integer.valueOf(4000));
        body.put("include_derived", Boolean.TRUE);
        return body;
    }

    public static String queryJson(String topic, int limit) { return json(queryRequest(topic, limit)); }

    static String controlJson(String action, String from, String to) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("source", SOURCE);
        body.put("action", action);
        if (from != null) body.put("from", from);
        if (to != null) body.put("to", to);
        return json(body);
    }

    static String sha256(String text) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder(64);
            for (byte b : h) s.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(b & 255)));
            return s.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String isoUtc(long ms) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        calendar.setTimeInMillis(ms);
        return String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
            Integer.valueOf(calendar.get(Calendar.YEAR)), Integer.valueOf(calendar.get(Calendar.MONTH) + 1),
            Integer.valueOf(calendar.get(Calendar.DAY_OF_MONTH)), Integer.valueOf(calendar.get(Calendar.HOUR_OF_DAY)),
            Integer.valueOf(calendar.get(Calendar.MINUTE)), Integer.valueOf(calendar.get(Calendar.SECOND)),
            Integer.valueOf(calendar.get(Calendar.MILLISECOND)));
    }

    public static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean) return ((Boolean) value).booleanValue() ? "true" : "false";
        if (value instanceof Number) return value.toString();
        if (value instanceof String) return quote((String) value);
        if (value instanceof Map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(quote(String.valueOf(entry.getKey()))).append(':').append(json(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List) {
            StringBuilder out = new StringBuilder("[");
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) { if (i > 0) out.append(','); out.append(json(list.get(i))); }
            return out.append(']').toString();
        }
        return quote(String.valueOf(value));
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", Integer.valueOf(c)));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
