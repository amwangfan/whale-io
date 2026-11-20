package com.turboio.addon;

import java.net.URI;

/** whale-core base URL rules. HTTPS only (a path prefix such as /turbo is allowed, so a reverse proxy can mount the
 * service under a sub-path). Plain http is accepted for loopback hosts only, and only when a unit test asks for it:
 * nothing in the UI sets that flag. */
final class WhaleEndpoint {
    static final String DEFAULT_BASE = "https://your-core-host.example.com/turbo";
    static final String INGEST = "/v1/rayneo/ingest", QUERY = "/v1/rayneo/query", CONTROL = "/v1/rayneo/control";
    /** Model-tool query: 4 s connect / 6 s read. Control and connection check: 8 s / 15 s. */
    static final int QUERY_CONNECT_MS = 4000, QUERY_READ_MS = 6000, CONTROL_CONNECT_MS = 8000, CONTROL_READ_MS = 15000;
    private WhaleEndpoint() {}

    static boolean loopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host);
    }

    static boolean validBase(String value, boolean allowLoopbackHttp) {
        try {
            URI uri = new URI(value == null ? "" : value.trim());
            String scheme = uri.getScheme();
            boolean https = "https".equals(scheme);
            if (!https && !(allowLoopbackHttp && "http".equals(scheme) && loopback(uri.getHost()))) return false;
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) return false;
            if (uri.getRawPath() != null && (uri.getRawPath().contains("..") || uri.getRawPath().contains("//"))) return false;
            return value.length() <= 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    static String normalize(String value) {
        String v = value == null ? "" : value.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    static String join(String base, String path) {
        return normalize(base) + path;
    }
}
