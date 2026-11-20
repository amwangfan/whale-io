package com.turboio.addon;

/** whale-core request body builders (/v1/chat, /v1/agent, /v1/qq/reply). Pure java.lang: hand-built JSON so the
 *  request shape is unit-testable without org.json or Android stubs. Contract: docs/handoff-whale-core-glasses-chat.md —
 *  text-only payloads; audio never leaves the device through here. */
public final class WhalePayload {
    private WhalePayload() {}

    /** /v1/chat: {text, mode:"quick"|"companion", sessionId?}. mode normalizes to companion unless exactly "quick". */
    public static String chatBody(String text, String mode, String sessionId) {
        StringBuilder s = new StringBuilder("{\"text\":\"").append(esc(text)).append("\",\"mode\":\"")
                .append("quick".equals(mode) ? "quick" : "companion").append('"');
        if (sessionId != null && !sessionId.isEmpty()) s.append(",\"sessionId\":\"").append(esc(sessionId)).append('"');
        return s.append('}').toString();
    }

    /** /v1/agent: {text?, sessionId?, confirmId?} — text omitted when blank (pure confirm re-run), ids omitted when empty. */
    public static String agentBody(String text, String sessionId, String confirmId) {
        StringBuilder s = new StringBuilder("{");
        boolean any = false;
        if (text != null && !text.trim().isEmpty()) { s.append("\"text\":\"").append(esc(text)).append('"'); any = true; }
        if (sessionId != null && !sessionId.isEmpty()) { if (any) s.append(','); s.append("\"sessionId\":\"").append(esc(sessionId)).append('"'); any = true; }
        if (confirmId != null && !confirmId.isEmpty()) { if (any) s.append(','); s.append("\"confirmId\":\"").append(esc(confirmId)).append('"'); }
        return s.append('}').toString();
    }

    /** /v1/qq/reply: {text}. */
    public static String qqBody(String text) {
        return "{\"text\":\"" + esc(text) + "\"}";
    }

    /** JSON string escaping: quotes/backslash; C0 controls become space (newline/tab/carriage-return via short forms). */
    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder o = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': o.append("\\\""); break;
                case '\\': o.append("\\\\"); break;
                case '\n': o.append("\\n"); break;
                case '\r': o.append("\\r"); break;
                case '\t': o.append("\\t"); break;
                default:
                    if (c < 0x20) o.append(' ');
                    else o.append(c);
            }
        }
        return o.toString();
    }
}
