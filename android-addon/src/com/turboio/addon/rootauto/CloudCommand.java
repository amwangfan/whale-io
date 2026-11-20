package com.turboio.addon.rootauto;

/**
 * Inbound cloud command envelope: id, idempotencyKey (required), task, optional confirmToken.
 */
public final class CloudCommand {
    public final String id;
    public final String idempotencyKey;
    public final TaskDsl task;
    public final String confirmToken;

    public CloudCommand(String id, String idempotencyKey, TaskDsl task, String confirmToken) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.task = task;
        this.confirmToken = confirmToken;
    }

    public static CloudCommand parse(String json) {
        if (json == null) throw new IllegalArgumentException("command json null");
        String s = json.trim();
        if (s.length() == 0 || s.charAt(0) != '{') throw new IllegalArgumentException("command not object");

        String id = TaskDsl.extractString(s, "id");
        if (id == null || id.trim().length() == 0) throw new IllegalArgumentException("id required");

        String key = TaskDsl.extractString(s, "idempotencyKey");
        if (key == null || key.trim().length() == 0) {
            throw new IllegalArgumentException("idempotencyKey required");
        }

        String token = TaskDsl.extractString(s, "confirmToken");
        // token may be absent or JSON null → treat as null
        if (token != null && (token.equals("null") || token.trim().length() == 0)) token = null;

        // Extract nested "task" object
        String taskJson = extractObject(s, "task");
        if (taskJson == null) throw new IllegalArgumentException("task required");
        TaskDsl task = TaskDsl.parse(taskJson);

        return new CloudCommand(id, key, task, token);
    }

    /** Extract a nested JSON object value for key. */
    static String extractObject(String json, String key) {
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + pat.length());
        if (colon < 0) return null;
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (j >= json.length() || json.charAt(j) != '{') return null;
        int depth = 0;
        for (int k = j; k < json.length(); k++) {
            char c = json.charAt(k);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return json.substring(j, k + 1);
            }
        }
        return null;
    }
}
