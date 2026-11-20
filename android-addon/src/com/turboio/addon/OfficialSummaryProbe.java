package com.turboio.addon;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Key-names-only probe of the official app's local Hive API cache (hive_ce, read-only via CeHive). Purpose: find out
 * whether and under which JSON field names the official 1.0.20 cache stores event summaries, WITHOUT reading or exporting
 * any content. The report contains: URL host + path shape (digits and long ids replaced), query PARAMETER NAMES, entry
 * counts, and for the newest entry per route the nested JSON key names with value TYPES and length buckets. It never
 * contains a string, number or list element value, nor the names of non-API cache keys. Summary upload itself is a
 * placeholder (WhaleConfig.summaryOn) and is not implemented. */
final class OfficialSummaryProbe {
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,39}"), NUM = Pattern.compile("(?<=/)[0-9]+(?=/|$)"), ID = Pattern.compile("(?<=/)[0-9a-fA-F-]{16,}(?=/|$)");
    private static final int MAX_ROUTES = 80, MAX_SHAPE = 6000;
    private OfficialSummaryProbe() {}

    static String report(byte[] cache) {
        Map<Object, Object> hive = CeHive.read(cache);
        LinkedHashMap<String, int[]> counts = new LinkedHashMap<String, int[]>();
        LinkedHashMap<String, Map<?, ?>> newest = new LinkedHashMap<String, Map<?, ?>>();
        int other = 0;
        for (Map.Entry<Object, Object> e : hive.entrySet()) {
            String route = route(e.getKey());
            if (route == null) { other++; continue; }
            int[] c = counts.get(route); if (c == null) { if (counts.size() >= MAX_ROUTES) continue; c = new int[1]; counts.put(route, c); }
            c[0]++;
            if (e.getValue() instanceof Map) {
                Map<?, ?> v = (Map<?, ?>) e.getValue(), prev = newest.get(route);
                if (prev == null || text(v.get("createdAt")).compareTo(text(prev.get("createdAt"))) > 0) newest.put(route, v);
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("# Turbo IO 官方缓存键名探针（只含键名/类型/长度档位，不含任何值）\n");
        out.append("api_routes=").append(counts.size()).append(" other_cache_keys=").append(other).append(" (其它缓存键只计数，不列名)\n\n");
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            out.append("## ").append(e.getKey()).append("  ×").append(e.getValue()[0]).append('\n');
            Map<?, ?> v = newest.get(e.getKey());
            if (v != null) { StringBuilder shape = new StringBuilder(); shape(v, 0, shape); if (shape.length() > MAX_SHAPE) { shape.setLength(MAX_SHAPE); shape.append("…"); } out.append(shape).append("\n\n"); }
        }
        return out.toString();
    }

    private static String text(Object o) { return o instanceof String ? (String) o : ""; }

    /** host + path shape + query parameter names; null for anything that is not an https rayneo.cn URL. */
    static String route(Object key) {
        if (!(key instanceof String)) return null;
        try {
            URI u = new URI((String) key);
            String host = u.getHost();
            if (!"https".equals(u.getScheme()) || host == null || !(host.equals("rayneo.cn") || host.endsWith(".rayneo.cn"))) return null;   // other hosts are only counted
            String path = u.getPath() == null ? "" : u.getPath();
            path = ID.matcher(NUM.matcher(path).replaceAll("{n}")).replaceAll("{id}");
            StringBuilder q = new StringBuilder();
            if (u.getRawQuery() != null) for (String pair : u.getRawQuery().split("&")) { String name = pair.split("=", 2)[0]; q.append(q.length() == 0 ? "?" : "&").append(KEY.matcher(name).matches() ? name : "<param>"); }
            return host + path + q;
        } catch (Exception e) { return null; }
    }

    static void shape(Object v, int depth, StringBuilder out) {
        if (out.length() > MAX_SHAPE) return;
        if (depth > 7) { out.append('…'); return; }
        if (v == null) out.append("null");
        else if (v instanceof Map) {
            out.append('{'); int n = 0;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (++n > 40) { out.append(",…"); break; }
                if (n > 1) out.append(',');
                String k = String.valueOf(e.getKey()); out.append(KEY.matcher(k).matches() ? k : "<key>").append(':'); shape(e.getValue(), depth + 1, out);
            }
            out.append('}');
        } else if (v instanceof List) { List<?> l = (List<?>) v; out.append('[').append(l.size()).append('×'); if (!l.isEmpty()) shape(l.get(0), depth + 1, out); out.append(']'); }
        else if (v instanceof String) { int n = ((String) v).length(); out.append(n == 0 ? "str(0)" : n < 100 ? "str(<100)" : n < 1000 ? "str(<1000)" : "str(>=1000)"); }
        else if (v instanceof Number) out.append("num");
        else if (v instanceof Boolean) out.append("bool");
        else if (v instanceof byte[]) out.append("bytes");
        else out.append("?");
    }
}
