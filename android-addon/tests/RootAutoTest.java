package com.turboio.addon.rootauto;

/** Offline rootauto checks — no Android / device / SSH. */
public final class RootAutoTest {
    private static int n;

    private static void check(boolean c, String m) {
        if (!c) throw new AssertionError(m + " (after " + n + " ok)");
        n++;
    }

    public static void main(String[] args) {
        // parse ok
        String readTask = "{"
                + "\"id\":\"t1\","
                + "\"confirmRequired\":false,"
                + "\"steps\":["
                + "{\"type\":\"open_app\",\"package\":\"com.example.app\"},"
                + "{\"type\":\"wait\",\"ms\":100},"
                + "{\"type\":\"read_text\",\"match\":\"状态\",\"label\":\"status\"},"
                + "{\"type\":\"extract\",\"regex\":\"\\\\d+\",\"label\":\"num\"}"
                + "]}";
        TaskDsl ok = TaskDsl.parse(readTask);
        check("t1".equals(ok.id), "id");
        check(!ok.needsConfirm(), "read-only no confirm");
        check(ok.steps.size() == 4, "4 steps");

        // parse fail: unknown type
        boolean rejected = false;
        try {
            TaskDsl.parse("{\"id\":\"x\",\"steps\":[{\"type\":\"explode\"}]}");
        } catch (IllegalArgumentException e) {
            rejected = e.getMessage() != null && e.getMessage().contains("unknown");
        }
        check(rejected, "reject unknown step");

        // confirm gate blocks pay/send without token
        String payTask = "{\"id\":\"pay1\",\"steps\":[{\"type\":\"pay\"}]}";
        TaskDsl pay = TaskDsl.parse(payTask);
        check(pay.needsConfirm(), "pay needs confirm");
        ConfirmGate.Verdict blocked = ConfirmGate.evaluate(pay, null);
        check(!blocked.allowed(), "block pay no token");
        check("CONFIRM_REQUIRED".equals(blocked.reason), "reason confirm");

        String sendTask = "{\"id\":\"s1\",\"confirmRequired\":true,\"steps\":[{\"type\":\"send_message\"}]}";
        TaskDsl send = TaskDsl.parse(sendTask);
        check(!ConfirmGate.evaluate(send, "").allowed(), "block send empty token");
        check(ConfirmGate.evaluate(send, "tok-1").allowed(), "allow send with token");

        // mock executes read-only
        ExecutorMock mock = new ExecutorMock();
        ExecutorMock.Result r = mock.runTask(ok, null);
        check(r.ok(), "mock ok");
        check(r.intendedActions.size() == 4, "4 intended");
        check(r.extracts.containsKey("status"), "status extract");

        // confirm blocks via mock
        ExecutorMock.Result blockedRun = mock.runTask(pay, null);
        check("CONFIRM_REQUIRED".equals(blockedRun.code), "mock blocks pay");

        // idempotency key required
        boolean noKey = false;
        try {
            CloudCommand.parse("{\"id\":\"c1\",\"task\":" + readTask + "}");
        } catch (IllegalArgumentException e) {
            noKey = e.getMessage() != null && e.getMessage().contains("idempotencyKey");
        }
        check(noKey, "idempotencyKey required");

        String cmdJson = "{"
                + "\"id\":\"c2\","
                + "\"idempotencyKey\":\"ik-1\","
                + "\"task\":" + readTask
                + "}";
        CloudCommand cmd = CloudCommand.parse(cmdJson);
        check("ik-1".equals(cmd.idempotencyKey), "key parsed");
        ExecutorMock.Result first = mock.run(cmd);
        check(first.ok(), "first run ok");
        ExecutorMock.Result second = mock.run(cmd);
        check("DUPLICATE".equals(second.code), "idempotent duplicate");

        // pay with token via envelope
        String payCmd = "{"
                + "\"id\":\"c3\","
                + "\"idempotencyKey\":\"ik-pay\","
                + "\"confirmToken\":\"user-ok\","
                + "\"task\":" + payTask
                + "}";
        ExecutorMock.Result paid = mock.run(CloudCommand.parse(payCmd));
        check(paid.ok(), "pay with token ok");
        check(paid.intendedActions.get(0).contains("pay"), "pay recorded");

        // --- ShellSession mock: cancel / continue / OUTCOME_UNKNOWN ---
        ExecutorMock shell = new ExecutorMock();

        // live continueRead: no new command side effect
        ExecutorMock.Result started = shell.startShell("sess-live", "ik-shell-live", "dumpsys activity");
        check(started.ok(), "startShell ok");
        check(shell.shellSideEffects().size() == 1, "one side effect after start");
        ExecutorMock.Result cont = shell.continueRead("sess-live");
        check("CONTINUE".equals(cont.code), "continueRead live CONTINUE");
        check(cont.ok(), "continueRead ok()");
        check(shell.shellSideEffects().size() == 1, "continue adds no shell side effect");
        check(shell.getSession("sess-live").continueCount == 1, "continueCount 1");

        // markUnknown then same idempotencyKey → no second side-effect
        ExecutorMock.Result unk = shell.markUnknown("ik-shell-live");
        check(ExecutorMock.OUTCOME_UNKNOWN.equals(unk.code), "markUnknown OUTCOME_UNKNOWN");
        int sideBefore = shell.shellSideEffects().size();
        ExecutorMock.Result retryStart = shell.startShell("sess-retry", "ik-shell-live", "dumpsys activity");
        check(ExecutorMock.OUTCOME_UNKNOWN.equals(retryStart.code)
                        || ExecutorMock.DUPLICATE.equals(retryStart.code),
                "retry after unknown blocked");
        check(shell.shellSideEffects().size() == sideBefore, "no second side effect after unknown");
        // CloudCommand.run with same key also blocked
        String shellish = "{"
                + "\"id\":\"c-unk\","
                + "\"idempotencyKey\":\"ik-shell-live\","
                + "\"task\":" + readTask
                + "}";
        ExecutorMock.Result retryRun = shell.run(CloudCommand.parse(shellish));
        check(ExecutorMock.OUTCOME_UNKNOWN.equals(retryRun.code)
                        || ExecutorMock.DUPLICATE.equals(retryRun.code),
                "run after unknown blocked");
        check(shell.shellSideEffects().size() == sideBefore, "run after unknown no new shell SE");

        // cancel → terminal CANCELLED; continueRead after cancel fails
        ExecutorMock shell2 = new ExecutorMock();
        check(shell2.startShell("sess-c", "ik-cancel", "id").ok(), "start for cancel");
        ExecutorMock.Result cancelled = shell2.cancel("sess-c");
        check(ExecutorMock.CANCELLED.equals(cancelled.code), "cancel CANCELLED");
        check("CANCELLED".equals(shell2.getSession("sess-c").state), "session state CANCELLED");
        ExecutorMock.Result afterCancel = shell2.continueRead("sess-c");
        check(ExecutorMock.SESSION_DEAD.equals(afterCancel.code), "continue after cancel fails");
        check(!afterCancel.ok(), "continue after cancel not ok");

        // fresh key still works after unrelated cancel path
        ExecutorMock.Result fresh = shell2.startShell("sess-fresh", "ik-fresh", "getprop");
        check(fresh.ok(), "fresh key start ok");
        check(shell2.shellSideEffects().size() == 2, "two starts across keys");

        System.out.println("RootAutoTest: " + n + " checks; rootauto skeleton offline OK");
    }
}
