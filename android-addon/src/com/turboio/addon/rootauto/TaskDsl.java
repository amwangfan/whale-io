package com.turboio.addon.rootauto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Minimal JSON task parse/validate (no Android, no org.json).
 * Rejects unknown step types; marks irreversible types for ConfirmGate.
 */
public final class TaskDsl {
    public static final Set<String> KNOWN_TYPES;
    public static final Set<String> IRREVERSIBLE_TYPES;

    static {
        Set<String> known = new LinkedHashSet<String>();
        known.add("open_app");
        known.add("wait");
        known.add("read_text");
        known.add("tap");
        known.add("scroll");
        known.add("extract");
        known.add("type_text");
        known.add("pay");
        known.add("send_message");
        known.add("delete");
        known.add("transfer");
        known.add("order");
        KNOWN_TYPES = Collections.unmodifiableSet(known);

        Set<String> irr = new LinkedHashSet<String>();
        irr.add("pay");
        irr.add("send_message");
        irr.add("delete");
        irr.add("transfer");
        irr.add("order");
        IRREVERSIBLE_TYPES = Collections.unmodifiableSet(irr);
    }

    public final String id;
    public final boolean confirmRequired;
    public final List<Step> steps;

    public TaskDsl(String id, boolean confirmRequired, List<Step> steps) {
        this.id = id;
        this.confirmRequired = confirmRequired;
        this.steps = Collections.unmodifiableList(new ArrayList<Step>(steps));
    }

    public static final class Step {
        public final String type;
        public final boolean irreversibleFlag;
        public final String raw;

        public Step(String type, boolean irreversibleFlag, String raw) {
            this.type = type;
            this.irreversibleFlag = irreversibleFlag;
            this.raw = raw;
        }

        public boolean isIrreversible() {
            if (IRREVERSIBLE_TYPES.contains(type)) return true;
            return irreversibleFlag;
        }
    }

    /** True if confirmRequired or any step is irreversible. */
    public boolean needsConfirm() {
        if (confirmRequired) return true;
        for (Step s : steps) if (s.isIrreversible()) return true;
        return false;
    }

    public static TaskDsl parse(String json) {
        if (json == null) throw new IllegalArgumentException("task json null");
        String s = json.trim();
        if (s.length() == 0 || s.charAt(0) != '{') throw new IllegalArgumentException("task not object");

        String id = extractString(s, "id");
        if (id == null || id.trim().length() == 0) throw new IllegalArgumentException("task id required");

        Boolean conf = extractBoolean(s, "confirmRequired");
        boolean confirmRequired = conf != null && conf.booleanValue();

        String stepsArr = extractArray(s, "steps");
        if (stepsArr == null) throw new IllegalArgumentException("steps required");
        List<String> objs = splitObjects(stepsArr);
        if (objs.isEmpty()) throw new IllegalArgumentException("steps empty");

        List<Step> steps = new ArrayList<Step>();
        for (String obj : objs) {
            String type = extractString(obj, "type");
            if (type == null || type.length() == 0) throw new IllegalArgumentException("step type required");
            type = type.toLowerCase(Locale.ROOT);
            if (!KNOWN_TYPES.contains(type)) throw new IllegalArgumentException("unknown step type: " + type);
            Boolean irr = extractBoolean(obj, "irreversible");
            boolean flag = irr != null && irr.booleanValue();
            steps.add(new Step(type, flag, obj));
        }
        return new TaskDsl(id, confirmRequired, steps);
    }

    // --- tiny JSON helpers (subset; sufficient for offline skeleton) ---

    static String extractString(String json, String key) {
        String pat = "\"" + key + "\"";
        int i = indexOfKey(json, pat);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + pat.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = findClosingQuote(json, q1 + 1);
        if (q2 < 0) return null;
        return unescape(json.substring(q1 + 1, q2));
    }

    static Boolean extractBoolean(String json, String key) {
        String pat = "\"" + key + "\"";
        int i = indexOfKey(json, pat);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + pat.length());
        if (colon < 0) return null;
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (json.startsWith("true", j)) return Boolean.TRUE;
        if (json.startsWith("false", j)) return Boolean.FALSE;
        return null;
    }

    static String extractArray(String json, String key) {
        String pat = "\"" + key + "\"";
        int i = indexOfKey(json, pat);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + pat.length());
        if (colon < 0) return null;
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (j >= json.length() || json.charAt(j) != '[') return null;
        int depth = 0;
        for (int k = j; k < json.length(); k++) {
            char c = json.charAt(k);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return json.substring(j + 1, k);
            }
        }
        return null;
    }

    static List<String> splitObjects(String arrayInner) {
        List<String> out = new ArrayList<String>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < arrayInner.length(); i++) {
            char c = arrayInner.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(arrayInner.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return out;
    }

    private static int indexOfKey(String json, String pat) {
        int from = 0;
        while (true) {
            int i = json.indexOf(pat, from);
            if (i < 0) return -1;
            // crude: accept first match (tasks are small)
            return i;
        }
    }

    private static int findClosingQuote(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '"') return i;
        }
        return -1;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
