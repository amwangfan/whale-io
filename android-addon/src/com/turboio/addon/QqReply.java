package com.turboio.addon;

/** QQ quick-reply voice command parser (pure logic, no Android deps — testable on JVM).
 *  "回复一下，说…" / "回复他，说…" / "回个消息，说…" → the spoken payload, else null. */
final class QqReply {
    private QqReply() {}

    /** Returns the message to send, or null when the utterance is not a quick-reply command. */
    static String message(String q) {
        if (q == null) return null;
        String t = q.trim();
        if (!(t.startsWith("回复") || t.startsWith("回他") || t.startsWith("回她") || t.startsWith("回它") || t.startsWith("回个"))) return null;
        int i = t.indexOf('说');
        if (i < 0 || i >= t.length() - 1) return null;
        String m = t.substring(i + 1).replaceAll("^[\\s，,。.!！？：:]+|[\\s，,。.!！？：]+$", "");
        return m.isEmpty() || m.length() > 4000 ? null : m;
    }

    /** Spoken confirmation for a held (two-phase) agent action; returns "confirm" or null. */
    static String confirm(String q) {
        if (q == null) return null;
        String t = q.trim().replaceAll("[\\s，,。.!！？]+$", "");
        return (t.equals("确认") || t.equals("执行") || t.equals("好") || t.equals("好的") || t.equals("是的") || t.equals("对") || t.equals("可以")) ? "confirm" : null;
    }
}
