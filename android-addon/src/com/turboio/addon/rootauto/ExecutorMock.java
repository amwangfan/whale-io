package com.turboio.addon.rootauto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dry-run executor: records intended actions without touching the device.
 * Real Accessibility / UiAutomator / root shell drivers are TODO stubs only.
 *
 * ShellSession mock mirrors Core action_status semantics for Mobile Shell
 * cancel/continue: unknown outcome must never auto re-create side effects.
 * See android-addon/docs/SHELL_CANCEL_CONTINUE.md.
 */
public final class ExecutorMock {

    /** TODO: real AccessibilityService driver — not implemented in v0 skeleton. */
    public interface AccessibilityDriver {
        void openApp(String packageName);
        String readText(String match);
        void tap(String selector);
        void scroll(String direction, int times);
    }

    /**
     * TODO: optional root shell (uiautomator dump / dumpsys) — not implemented.
     * Future continuous shell (stubs OK until real driver):
     * - cancel(sessionId): kill process + emit terminal receipt (e.g. CANCELLED)
     * - continueRead(sessionId): same-session read-more, no new side-effecting command
     * Timeout without cancel may leave pending/unknown; Core must not re-dispatch.
     */
    public interface RootShellDriver {
        String dumpsys(String service);
        String uiautomatorDump();
        // Future continuous shell — stubs only, no real process:
        // Result cancel(String sessionId);
        // Result continueRead(String sessionId);
    }

    /** Result codes used by dry-run + ShellSession mock. */
    public static final String OK = "OK";
    public static final String CONTINUE = "CONTINUE";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN";
    public static final String CANCELLED = "CANCELLED";
    public static final String CONFIRM_REQUIRED = "CONFIRM_REQUIRED";
    public static final String PARSE_ERROR = "PARSE_ERROR";
    public static final String SESSION_DEAD = "SESSION_DEAD";

    public static final class Result {
        public final String code;
        public final String message;
        public final List<String> intendedActions;
        public final Map<String, String> extracts;

        public Result(String code, String message, List<String> intendedActions, Map<String, String> extracts) {
            this.code = code;
            this.message = message;
            this.intendedActions = Collections.unmodifiableList(new ArrayList<String>(intendedActions));
            this.extracts = Collections.unmodifiableMap(new LinkedHashMap<String, String>(extracts));
        }

        public boolean ok() { return OK.equals(code) || CONTINUE.equals(code); }
    }

    /**
     * In-memory continuous-shell session mock (no real process).
     * startShell records one side-effecting command; continueRead does not.
     */
    public static final class ShellSession {
        public final String sessionId;
        public final String idempotencyKey;
        public final String command;
        /** RUNNING | UNKNOWN | CANCELLED | COMPLETED */
        public String state;
        public final List<String> commandsStarted = new ArrayList<String>();
        public int continueCount;

        ShellSession(String sessionId, String idempotencyKey, String command) {
            this.sessionId = sessionId;
            this.idempotencyKey = idempotencyKey;
            this.command = command;
            this.state = "RUNNING";
            this.commandsStarted.add(command);
        }

        public boolean alive() {
            return "RUNNING".equals(state) || "UNKNOWN".equals(state);
        }
    }

    private final Map<String, Result> idempotencyCache = new LinkedHashMap<String, Result>();
    private final Map<String, ShellSession> sessionsById = new LinkedHashMap<String, ShellSession>();
    private final Map<String, String> sessionIdByKey = new LinkedHashMap<String, String>();
    /** All shell start side effects (command strings) in order — for tests. */
    private final List<String> shellSideEffects = new ArrayList<String>();

    public List<String> shellSideEffects() {
        return Collections.unmodifiableList(new ArrayList<String>(shellSideEffects));
    }

    public ShellSession getSession(String sessionId) {
        return sessionsById.get(sessionId);
    }

    public Result cachedResult(String idempotencyKey) {
        return idempotencyCache.get(idempotencyKey);
    }

    /**
     * Execute under ConfirmGate; uses idempotencyKey from command when provided via run(CloudCommand).
     * Cached OUTCOME_UNKNOWN / other non-CONFIRM/PARSE results block retry (no new side effects).
     */
    public Result run(CloudCommand cmd) {
        if (cmd == null) return fail(PARSE_ERROR, "command null");
        if (cmd.idempotencyKey == null || cmd.idempotencyKey.trim().length() == 0) {
            return fail(PARSE_ERROR, "idempotencyKey required");
        }
        Result blocked = replayOrUnknown(cmd.idempotencyKey);
        if (blocked != null) return blocked;
        Result r = runTask(cmd.task, cmd.confirmToken);
        if (!CONFIRM_REQUIRED.equals(r.code) && !PARSE_ERROR.equals(r.code)) {
            idempotencyCache.put(cmd.idempotencyKey, r);
        }
        return r;
    }

    public Result runTask(TaskDsl task, String confirmToken) {
        if (task == null) return fail(PARSE_ERROR, "task null");
        ConfirmGate.Verdict v = ConfirmGate.evaluate(task, confirmToken);
        if (!v.allowed()) {
            return fail(CONFIRM_REQUIRED, v.reason);
        }

        List<String> actions = new ArrayList<String>();
        Map<String, String> extracts = new LinkedHashMap<String, String>();
        for (TaskDsl.Step step : task.steps) {
            String type = step.type;
            if ("open_app".equals(type)) {
                String pkg = TaskDsl.extractString(step.raw, "package");
                actions.add("open_app:" + (pkg == null ? "?" : pkg));
            } else if ("wait".equals(type)) {
                actions.add("wait");
            } else if ("read_text".equals(type)) {
                String label = TaskDsl.extractString(step.raw, "label");
                actions.add("read_text:" + (label == null ? "_" : label));
                if (label != null) extracts.put(label, "[mock-text]");
            } else if ("tap".equals(type)) {
                actions.add("tap");
            } else if ("scroll".equals(type)) {
                actions.add("scroll");
            } else if ("extract".equals(type)) {
                String label = TaskDsl.extractString(step.raw, "label");
                actions.add("extract:" + (label == null ? "_" : label));
                if (label != null) extracts.put(label, "[mock-extract]");
            } else if ("type_text".equals(type)) {
                actions.add("type_text");
            } else if (TaskDsl.IRREVERSIBLE_TYPES.contains(type)) {
                actions.add(type + ":confirmed");
            } else {
                return fail(PARSE_ERROR, "unhandled type " + type);
            }
        }
        return new Result(OK, "dry-run complete", actions, extracts);
    }

    /**
     * Record intended shell start (one side-effecting command).
     * Same idempotencyKey with a prior non-CONFIRM/PARSE result → DUPLICATE or OUTCOME_UNKNOWN;
     * does not re-run the side effect.
     */
    public Result startShell(String sessionId, String idempotencyKey, String command) {
        if (sessionId == null || sessionId.trim().length() == 0) {
            return fail(PARSE_ERROR, "sessionId required");
        }
        if (idempotencyKey == null || idempotencyKey.trim().length() == 0) {
            return fail(PARSE_ERROR, "idempotencyKey required");
        }
        if (command == null || command.trim().length() == 0) {
            return fail(PARSE_ERROR, "command required");
        }
        Result blocked = replayOrUnknown(idempotencyKey);
        if (blocked != null) return blocked;
        if (sessionsById.containsKey(sessionId)) {
            return fail(PARSE_ERROR, "sessionId already exists");
        }

        ShellSession s = new ShellSession(sessionId, idempotencyKey, command);
        sessionsById.put(sessionId, s);
        sessionIdByKey.put(idempotencyKey, sessionId);
        shellSideEffects.add(command);

        List<String> actions = new ArrayList<String>();
        actions.add("shell_start:" + command);
        Map<String, String> extracts = new LinkedHashMap<String, String>();
        extracts.put("sessionId", sessionId);
        extracts.put("state", s.state);
        Result r = new Result(OK, "shell started (mock)", actions, extracts);
        idempotencyCache.put(idempotencyKey, r);
        return r;
    }

    /**
     * Simulate timeout with process possibly still running: store OUTCOME_UNKNOWN for the key.
     * Subsequent run/startShell with the same key must not re-execute side effects.
     */
    public Result markUnknown(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.trim().length() == 0) {
            return fail(PARSE_ERROR, "idempotencyKey required");
        }
        String sid = sessionIdByKey.get(idempotencyKey);
        ShellSession s = sid == null ? null : sessionsById.get(sid);
        List<String> actions = new ArrayList<String>();
        Map<String, String> extracts = new LinkedHashMap<String, String>();
        if (s != null) {
            s.state = "UNKNOWN";
            actions.add("shell_unknown:" + sid);
            extracts.put("sessionId", sid);
            extracts.put("state", s.state);
        } else {
            actions.add("outcome_unknown:" + idempotencyKey);
        }
        Result r = new Result(OUTCOME_UNKNOWN,
                "timeout: outcome unknown; do not auto re-dispatch", actions, extracts);
        idempotencyCache.put(idempotencyKey, r);
        return r;
    }

    /**
     * Kill/mark session cancelled; returns terminal CANCELLED and clears “still running”
     * so Core can see terminal status via action_status. Emits terminal receipt semantics
     * (mock: caches CANCELLED under the session's idempotency key).
     */
    public Result cancel(String sessionId) {
        if (sessionId == null || sessionId.trim().length() == 0) {
            return fail(PARSE_ERROR, "sessionId required");
        }
        ShellSession s = sessionsById.get(sessionId);
        if (s == null) {
            return fail(SESSION_DEAD, "session not found");
        }
        s.state = "CANCELLED";
        List<String> actions = new ArrayList<String>();
        actions.add("shell_cancel:" + sessionId);
        Map<String, String> extracts = new LinkedHashMap<String, String>();
        extracts.put("sessionId", sessionId);
        extracts.put("state", s.state);
        Result r = new Result(CANCELLED,
                "session cancelled; terminal receipt (mock kill)", actions, extracts);
        // Terminal: overwrite prior OK / OUTCOME_UNKNOWN so status is terminal.
        idempotencyCache.put(s.idempotencyKey, r);
        return r;
    }

    /**
     * Same-session continue read: only if session still alive/running (or unknown-but-alive).
     * Returns CONTINUE with no new side-effecting command. Cancelled / closed → SESSION_DEAD.
     * Note: after markUnknown the session is still “possibly running”; continueRead is allowed
     * until cancel closes it. After cancel, continue fails.
     */
    public Result continueRead(String sessionId) {
        if (sessionId == null || sessionId.trim().length() == 0) {
            return fail(PARSE_ERROR, "sessionId required");
        }
        ShellSession s = sessionsById.get(sessionId);
        if (s == null) {
            return fail(SESSION_DEAD, "session not found");
        }
        if ("CANCELLED".equals(s.state) || "COMPLETED".equals(s.state)) {
            return fail(SESSION_DEAD, "session closed: " + s.state);
        }
        // RUNNING or UNKNOWN (process may still be up): read-more, no new command side effect.
        s.continueCount++;
        List<String> actions = new ArrayList<String>();
        actions.add("shell_continue:" + sessionId + "#" + s.continueCount);
        Map<String, String> extracts = new LinkedHashMap<String, String>();
        extracts.put("sessionId", sessionId);
        extracts.put("state", s.state);
        extracts.put("chunk", "[mock-stdout-more]");
        // Do NOT append to shellSideEffects — continue is not a new command.
        return new Result(CONTINUE, "continue read (no new side effect)", actions, extracts);
    }

    /**
     * If key already has a non-CONFIRM/PARSE cached result, return replay without side effects.
     * OUTCOME_UNKNOWN is returned as-is (still unknown); other codes as DUPLICATE.
     */
    private Result replayOrUnknown(String idempotencyKey) {
        Result cached = idempotencyCache.get(idempotencyKey);
        if (cached == null) return null;
        if (CONFIRM_REQUIRED.equals(cached.code) || PARSE_ERROR.equals(cached.code)) {
            return null; // safe to retry these
        }
        if (OUTCOME_UNKNOWN.equals(cached.code)) {
            return new Result(OUTCOME_UNKNOWN, "idempotent: still unknown; no re-dispatch",
                    cached.intendedActions, cached.extracts);
        }
        return new Result(DUPLICATE, "idempotent replay", cached.intendedActions, cached.extracts);
    }

    private static Result fail(String code, String msg) {
        return new Result(code, msg, Collections.<String>emptyList(), Collections.<String, String>emptyMap());
    }
}
