package com.turboio.addon;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** One JSON POST to whale-core. Never throws: every failure becomes a Result with a content-free error class.
 * The bearer token is passed in per call and is never logged, echoed or stored here. No redirects. */
final class WhaleHttp {
    static final int MAX_RESPONSE = 1 << 20, MAX_ERROR_BODY = 4096;
    private WhaleHttp() {}

    static final class Result {
        final int status;            // HTTP status, 0 when no response
        final String body;           // 2xx body (<=1 MiB); error bodies are reduced to serverCode/retryAfterS
        final String errorClass;     // null when 2xx; offline | timeout | io | http
        final String serverCode;     // allow-listed "error" identifier of a non-2xx answer, else ""
        final int retryAfterS;       // from Retry-After header or body retry_after_s; 0 when absent
        Result(int status, String body, String errorClass, String serverCode, int retryAfterS) {
            this.status = status; this.body = body == null ? "" : body; this.errorClass = errorClass;
            this.serverCode = serverCode == null ? "" : serverCode; this.retryAfterS = retryAfterS;
        }
        boolean ok() { return errorClass == null && status >= 200 && status < 300; }
        boolean auth() { return status == 401 || status == 403; }
        /** "HTTP 401 unauthorized" style label for the UI; empty for success. */
        String label() {
            if (ok()) return "";
            if (status == 0) return errorClass == null ? "io" : errorClass;
            return RayneoAuthDiagnostic.code(status, serverCode);
        }
    }

    static Result post(String url, String token, String json, int connectMs, int readMs, boolean allowLoopbackHttp) {
        HttpURLConnection connection = null;
        try {
            URL u = new URL(url);
            boolean https = "https".equals(u.getProtocol());
            if (!https && !(allowLoopbackHttp && "http".equals(u.getProtocol()) && WhaleEndpoint.loopback(u.getHost()))) return new Result(0, "", "io", "", 0);
            connection = (HttpURLConnection) u.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectMs);
            connection.setReadTimeout(readMs);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            // Buffered body (<= 256 KiB, Content-Length set by the platform): streaming mode hides the error body of a 401 on some JVMs.
            try (OutputStream stream = connection.getOutputStream()) { stream.write(bytes); }
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = connection.getInputStream()) {
                    if (in != null) {
                        byte[] block = new byte[8192]; int n;
                        while ((n = in.read(block)) != -1) {
                            if (out.size() + n > MAX_RESPONSE) return new Result(code, "", "io", "", 0);
                            out.write(block, 0, n);
                        }
                    }
                }
                return new Result(code, out.toString("UTF-8"), null, "", 0);
            }
            String serverCode = ""; int retry = headerSeconds(connection.getHeaderField("Retry-After"));
            try (InputStream in = connection.getErrorStream()) {
                if (in != null) {
                    Object parsed = BoundedJson.readLarge(RayneoAuthDiagnostic.readBounded(in).getBytes(StandardCharsets.UTF_8), MAX_ERROR_BODY, 64);
                    if (parsed instanceof Map) {
                        Object error = ((Map<?, ?>) parsed).get("error");
                        if (error instanceof Map) error = ((Map<?, ?>) error).get("code");
                        if (error instanceof String && ((String) error).matches("[a-z_]{1,32}")) serverCode = (String) error;
                        Object after = ((Map<?, ?>) parsed).get("retry_after_s");
                        if (after instanceof Number && retry == 0) retry = (int) Math.max(0, Math.min(86400, ((Number) after).longValue()));
                    }
                }
            } catch (Exception ignored) { /* unparseable error body: status alone decides */ }
            return new Result(code, "", "http", serverCode, retry);
        } catch (SocketTimeoutException e) {
            return new Result(0, "", "timeout", "", 0);
        } catch (UnknownHostException | ConnectException e) {
            return new Result(0, "", "offline", "", 0);
        } catch (Exception e) {
            return new Result(0, "", "io", "", 0);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static int headerSeconds(String value) {
        try { return value == null ? 0 : (int) Math.max(0, Math.min(86400, Long.parseLong(value.trim()))); } catch (Exception e) { return 0; }
    }
}
