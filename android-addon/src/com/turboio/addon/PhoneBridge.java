package com.turboio.addon;

/**
 * Phone↔glasses bridge façade (design skeleton).
 * Wires to existing {@link BackgroundWork}, {@link MusicBridge}, {@link AlwaysOnAudio},
 * {@link NotificationRuntime}, {@link CardTransport} without new Services or host APK.
 * Methods that need Android stay as interfaces; offline policy helpers are pure.
 */
public final class PhoneBridge {
    private PhoneBridge(){}

    /** BackgroundWork job keys — keep in sync with phone-bridge-plan.md */
    public static final String JOB_MUSIC="音乐显示";
    public static final String JOB_NAV="导航";
    public static final String JOB_NOTIFY="主动通知";
    public static final String JOB_AUDIO="音频会话";

    public static final int TITLE_MAX=64;
    public static final int BODY_MAX=280;

    /** Outbound short audio / TTS session (implementation plugs MusicBridge or CloudTts later). */
    public interface Audio {
        /** Start a bounded outbound session; false if transfer busy or not connected. */
        boolean openOutbound(String reason);
        void closeOutbound();
        /** List official always-on dumps (AlwaysOnAudio); empty if no host path. */
        java.util.List<?> listAlwaysOn();
    }

    /** Active notify → dashboard card path (approve then CardTransport.publish).
     * Server-backed ReminderPayload / CardDocument v1 goes through {@link DashboardPhone} +
     * {@link DashboardPhoneUI}; do not rebuild title/body via draftPayload for those jobs. */
    public interface Notify {
        /** Build a draft map; null if rejected by {@link #validate}. */
        java.util.Map<String,Object> draft(String title,String body);
        /** After user approve: publish to glasses. */
        void publishApproved(java.util.Map<String,Object> doc,boolean top);
    }

    /** Offline validation for active-notify drafts (no Android). */
    public static String validate(String title,String body){
        if(title==null||title.trim().isEmpty())return "标题为空";
        if(title.length()>TITLE_MAX)return "标题过长";
        if(body!=null&&body.length()>BODY_MAX)return "正文过长";
        return null;
    }

    /** Clip for CardTransport-bound text fields. */
    public static String clip(String s,int max){
        if(s==null)return "";
        String t=s.trim();
        return t.length()<=max?t:t.substring(0,max);
    }

    /** Dashboard / reminder path — must match assistant pipeline template. */
    public static final String CARD_ID_ASSISTANT="turbo_ui_card_assistant";
    /** Optional local-only draft (not from dashboard-service); do not use for ReminderPayload. */
    public static final String CARD_ID_LOCAL="turbo_ui_card_phone_notify";

    /**
     * Pure draft for dashboard-originated cards. Host CardCodec.install still required at publish.
     * Default id = {@link #CARD_ID_ASSISTANT}.
     */
    public static java.util.Map<String,Object> draftPayload(String title,String body){
        return draftPayload(title,body,CARD_ID_ASSISTANT,"assistant");
    }

    /** Local-only channel; separate id so it never collides with assistant template installs. */
    public static java.util.Map<String,Object> draftLocalPayload(String title,String body){
        return draftPayload(title,body,CARD_ID_LOCAL,"phone_bridge");
    }

    public static java.util.Map<String,Object> draftPayload(String title,String body,String cardId,String channel){
        String err=validate(title,body);
        if(err!=null)throw new IllegalArgumentException(err);
        if(cardId==null||!cardId.matches("turbo_ui_card_[a-z0-9_]{1,34}"))throw new IllegalArgumentException("bad card id");
        java.util.Map<String,Object> doc=new java.util.LinkedHashMap<>();
        doc.put("id",cardId);
        doc.put("title",clip(title,TITLE_MAX));
        doc.put("body",clip(body==null?"":body,BODY_MAX));
        doc.put("channel",channel==null?"":channel);
        return doc;
    }
}
