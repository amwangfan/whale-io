package com.nova.fakecall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

/**
 * Intercept system Dialer outgoing calls to exact number "1".
 * Cancels cellular placeCall, then routes to CallProvider PhoneAccount
 * so com.android.dialer InCallServiceImpl shows the native in-call UI.
 *
 * Re-entrancy: our own TelecomManager.placeCall also broadcasts
 * NEW_OUTGOING_CALL — skip that one via sAllowOurPlaceCall.
 *
 * When Nova AI is already the selected PhoneAccountHandle (defaultOutgoing
 * or Dialer pre-selected), let the call through — do not abort/re-place.
 */
public class OutgoingCallReceiver extends BroadcastReceiver {
    private static final String TAG = "NovaFakeCall";

    /** Set true just before our placeCall so we do not cancel ourselves. */
    static volatile boolean sAllowOurPlaceCall = false;

    private static final String[] EMERGENCY = {
            "112", "911", "110", "119", "120", "122", "999", "000", "08", "118"
    };

    static String normalize(String number) {
        if (number == null) return null;
        String n = number.trim();
        n = n.replace(" ", "").replace("-", "").replace("(", "").replace(")", "");
        return n;
    }

    static boolean isEmergency(String n) {
        if (n == null || n.length() == 0) return false;
        for (String e : EMERGENCY) {
            if (e.equals(n)) return true;
        }
        return false;
    }

    /** Exact "1" only (never emergency / other numbers). */
    static boolean isAiShortcut(String number) {
        String n = normalize(number);
        if (n == null) return false;
        if (isEmergency(n)) return false;
        if (n.startsWith("+")) return false;
        return "1".equals(n);
    }

    private static boolean isAlreadyAiAccount(Intent intent) {
        if (intent == null) return false;
        try {
            PhoneAccountHandle h = intent.getParcelableExtra(
                    TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE);
            if (CallAccountHelper.isAiOutHandle(h)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_NEW_OUTGOING_CALL.equals(intent.getAction())) return;

        String number = getResultData();
        if (number == null) {
            number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
        }
        Log.i(TAG, "NEW_OUTGOING_CALL number=" + number
                + " allowOur=" + sAllowOurPlaceCall
                + " alreadyAi=" + isAlreadyAiAccount(intent));

        if (sAllowOurPlaceCall) {
            // Let our CallProvider placeCall through untouched.
            sAllowOurPlaceCall = false;
            Log.i(TAG, "allowing our CallProvider placeCall for tel:1");
            return;
        }

        if (!isAiShortcut(number)) {
            return;
        }

        // Dialer already pinned Nova AI (e.g. defaultOutgoing) — do not cancel.
        if (isAlreadyAiAccount(intent)) {
            Log.i(TAG, "tel:1 already on Nova AI handle; pass through (no abort)");
            return;
        }

        // Cancel the pending cellular / default-account call.
        setResultData(null);
        try {
            abortBroadcast();
        } catch (Throwable t) {
            Log.w(TAG, "abortBroadcast", t);
        }

        final Context app = context.getApplicationContext();
        // Defer placeCall so Telecom finishes tearing down the aborted attempt.
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    CallAccountHelper.placeOutgoingAi(app);
                } catch (Throwable t) {
                    Log.e(TAG, "placeOutgoingAi failed", t);
                }
            }
        }, 350);
        Log.i(TAG, "aborted tel:1 cellular; scheduling CallProvider placeCall → AI");
    }
}
