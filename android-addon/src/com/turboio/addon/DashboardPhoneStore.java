package com.turboio.addon;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

/**
 * Persists dashboard-service phone config: origin/peer/backendDevice/autoApprove in SharedPreferences;
 * phone Token only in SecretStore ({@code dashboard_phone_token}). Never reads repo .state/*.token.
 * {@code autoApprove} defaults true (自动上镜：跳过每次人工批准).
 */
final class DashboardPhoneStore {
    static final String PREFS="turboio_dashboard_phone";
    static final String TOKEN_KEY="dashboard_phone_token";
    private DashboardPhoneStore(){}

    private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREFS,0);}

    /** Saved non-secret settings; empty map when incomplete/invalid. */
    static Map<String,String> settings(Context c){
        SharedPreferences p=prefs(c);
        String origin=DashboardPhone.origin(p.getString("origin",""));
        String peer=p.getString("peer","");
        String device=p.getString("backendDevice","");
        if(origin==null||peer==null||peer.isEmpty()||peer.length()>256||!DashboardPhone.deviceOk(device))
            return Collections.emptyMap();
        Map<String,String> m=new LinkedHashMap<>();
        m.put("origin",origin);m.put("peer",peer);m.put("backendDevice",device);
        return m;
    }

    static String token(Context c){
        try{String t=SecretStore.get(c,TOKEN_KEY);return DashboardPhone.tokenOk(t)?t:"";}
        catch(Exception e){return "";}
    }

    /**
     * Save after identity OK. {@code tokenOrEmpty}: non-empty replaces SecretStore; empty keeps existing.
     * Returns false if validation fails or token missing after keep. Does not change autoApprove.
     */
    static boolean save(Context c,String originIn,String tokenOrEmpty,String peer,String device){
        String origin=DashboardPhone.origin(originIn);
        if(origin==null||peer==null||peer.isEmpty()||peer.length()>256||!DashboardPhone.deviceOk(device))return false;
        String token=tokenOrEmpty==null?"":tokenOrEmpty.trim();
        try{
            if(!token.isEmpty()){
                if(!DashboardPhone.tokenOk(token))return false;
                SecretStore.put(c,TOKEN_KEY,token);
            }else if(!DashboardPhone.tokenOk(token(c)))return false;
            return prefs(c).edit().putString("origin",origin).putString("peer",peer).putString("backendDevice",device).commit();
        }catch(Exception e){return false;}
    }

    /** Default true when unset. Cleared with disconnect (falls back to default true next time). */
    static boolean autoApprove(Context c){return prefs(c).getBoolean("autoApprove",true);}

    static void setAutoApprove(Context c,boolean on){prefs(c).edit().putBoolean("autoApprove",on).commit();}

    static void disconnect(Context c){
        prefs(c).edit().clear().commit();
        try{SecretStore.put(c,TOKEN_KEY,"");}catch(Exception ignored){}
    }

    static boolean configured(Context c){return !settings(c).isEmpty()&&DashboardPhone.tokenOk(token(c));}
}
