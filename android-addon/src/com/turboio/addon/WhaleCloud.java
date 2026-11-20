package com.turboio.addon;
import android.content.Context;
import java.util.*;
import org.json.*;

/** whale-core cloud brain endpoints (dashboard-service /v1/chat, /v1/qq/reply), phone Bearer auth.
 *  Contract: docs/handoff-whale-core-glasses-chat.md. Reuses the dashboard origin + phone token. */
final class WhaleCloud {
    interface ChatDone { void done(String reply, String sessionId, String error); }
    interface ReplyDone { void done(boolean ok, String error); }
    /** whale-core glasses HTTP request body cap is 64 KiB (no chunked); keep the request under it. */
    static final int REQUEST_BODY_CAP=60000;

    /** Agent turn result: reply + optional two-phase pending confirmation + action summaries. */
    static final class AgentResult {
        final String reply, sessionId, error, confirmId, pendingKind, pendingDescription, pendingExact;
        final java.util.List<String> actions;
        AgentResult(String reply, String sessionId, String error, String confirmId, String pendingKind, String pendingDescription, String pendingExact, java.util.List<String> actions) {
            this.reply=reply; this.sessionId=sessionId; this.error=error; this.confirmId=confirmId;
            this.pendingKind=pendingKind; this.pendingDescription=pendingDescription; this.pendingExact=pendingExact; this.actions=actions;
        }
        boolean pending(){ return confirmId!=null && !confirmId.isEmpty(); }
    }
    interface AgentDone { void done(AgentResult r); }

    /** Agentic turn: chat + tasks + gated shell/self-modify. confirmId re-runs a held (two-phase) action. */
    static void agent(Context c,String text,String sessionId,String confirmId,AgentDone done){
        String origin=origin(c),token=DashboardPhoneStore.token(c);
        if(origin.isEmpty()||token.isEmpty()){done.done(new AgentResult(null,sessionId,"未配置云端大脑",null,null,null,null,null));return;}
        try{
            byte[] payload=WhalePayload.agentBody(text,sessionId,confirmId).getBytes("UTF-8");
            if(payload.length>REQUEST_BODY_CAP){done.done(new AgentResult(null,sessionId,"内容超出云端上限",null,null,null,null,null));return;}
            byte[] raw=DashboardPhone.transport.exchange(origin+"/v1/agent",headers(token),new String(payload,"UTF-8"),131072);
            JSONObject r=new JSONObject(new String(raw,"UTF-8"));
            String sid=r.optString("sessionId",sessionId);
            String reply=r.optString("reply",null);
            java.util.List<String> actions=new java.util.ArrayList<>();
            JSONArray acts=r.optJSONArray("actions");
            if(acts!=null)for(int i=0;i<acts.length();i++){JSONObject a=acts.optJSONObject(i);if(a!=null)actions.add(a.optString("summary","")+"["+a.optString("status","")+"]");}
            JSONObject pnd=r.optJSONObject("pending");
            String cid=pnd==null?null:pnd.optString("confirmId",null);
            String pk=pnd==null?null:pnd.optString("kind",null);
            String pd=pnd==null?null:pnd.optString("description",null);
            String pe=pnd==null?null:pnd.optString("exact",null);
            String err=r.optString("error",null);
            done.done(new AgentResult(reply,sid,err==null||err.isEmpty()?null:err,cid,pk,pd,pe,actions));
        }catch(Exception e){done.done(new AgentResult(null,sessionId,"云端连接失败",null,null,null,null,null));}
    }

    static boolean configured(Context c){ return DashboardPhoneStore.configured(c); }
    static String origin(Context c){ Map<String,String> s=DashboardPhoneStore.settings(c); return s.isEmpty()?"":s.get("origin"); }

    /** mode: "companion"（陪伴式，走记忆库）| "quick"（快速精简，不写记忆）。 */
    static void chat(Context c,String text,String mode,String sessionId,ChatDone done){
        String origin=origin(c),token=DashboardPhoneStore.token(c);
        if(origin.isEmpty()||token.isEmpty()){done.done(null,sessionId,"未配置云端大脑");return;}
        if(text==null||text.trim().isEmpty()){done.done(null,sessionId,"空内容");return;}
        try{
            byte[] payload=WhalePayload.chatBody(text,mode,sessionId).getBytes("UTF-8");
            if(payload.length>REQUEST_BODY_CAP){done.done(null,sessionId,"内容超出云端上限");return;}
            byte[] raw=DashboardPhone.transport.exchange(origin+"/v1/chat",headers(token),new String(payload,"UTF-8"),65536);
            JSONObject r=new JSONObject(new String(raw,"UTF-8"));
            String reply=r.optString("reply",null);String err=r.optString("error",null);
            String sid=r.optString("sessionId",sessionId);
            if(reply==null||reply.isEmpty())done.done(null,sid,err==null||err.isEmpty()?"云端无回复":err);
            else done.done(reply,sid,null);
        }catch(Exception e){done.done(null,sessionId,"云端连接失败");}
    }

    static void qqReply(Context c,String text,ReplyDone done){
        String origin=origin(c),token=DashboardPhoneStore.token(c);
        if(origin.isEmpty()||token.isEmpty()){done.done(false,"未配置云端大脑");return;}
        if(text==null||text.trim().isEmpty()){done.done(false,"空内容");return;}
        try{
            byte[] raw=DashboardPhone.transport.exchange(origin+"/v1/qq/reply",headers(token),WhalePayload.qqBody(text),16384);
            JSONObject r=new JSONObject(new String(raw,"UTF-8"));
            if(r.optBoolean("ok"))done.done(true,null);
            else done.done(false,r.optString("error","发送未确认"));
        }catch(Exception e){done.done(false,"云端连接失败");}
    }

    private static Map<String,String> headers(String token){
        Map<String,String> h=new LinkedHashMap<>();h.put("Authorization","Bearer "+token);h.put("Content-Type","application/json");return h;
    }
}
