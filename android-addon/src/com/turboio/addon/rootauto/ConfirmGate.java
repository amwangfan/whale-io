package com.turboio.addon.rootauto;

/**
 * Policy: irreversible tasks blocked until confirm token present;
 * reversible may auto-run in mock.
 */
public final class ConfirmGate {
    private ConfirmGate() {}

    public enum Decision { ALLOW, BLOCK }

    public static final class Verdict {
        public final Decision decision;
        public final String reason;

        public Verdict(Decision decision, String reason) {
            this.decision = decision;
            this.reason = reason;
        }

        public boolean allowed() { return decision == Decision.ALLOW; }
    }

    /**
     * @param task parsed DSL
     * @param confirmToken optional; non-empty grants irreversible when task needs confirm
     */
    public static Verdict evaluate(TaskDsl task, String confirmToken) {
        if (task == null) return new Verdict(Decision.BLOCK, "task null");
        if (!task.needsConfirm()) return new Verdict(Decision.ALLOW, "reversible");
        if (confirmToken != null && confirmToken.trim().length() > 0) {
            return new Verdict(Decision.ALLOW, "confirm token present");
        }
        return new Verdict(Decision.BLOCK, "CONFIRM_REQUIRED");
    }
}
