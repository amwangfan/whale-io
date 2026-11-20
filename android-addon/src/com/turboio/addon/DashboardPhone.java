package com.turboio.addon;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.turboio.addon.BoundedJson.*;

/**
 * dashboard-service phone bridge (contract v0): origin/token/path policy, validJob, block sending/
 * outcome_unknown, pickAutoJobId for silent auto-approve, and bounded HTTPS exchange.
 * Pure logic — no Android. Align EditorBackend.m. Auto path still uses approve API; client skips UI confirm.
 */
public final class DashboardPhone {
    private DashboardPhone(){}

    public static final String DEFAULT_DEVICE="fanwang_io";
    /** Prefill for 云端数据源; settings may override. No trailing slash. Token never baked in. */
    public static final String DEFAULT_ORIGIN="https://your-core-host.example.com/turbo";
    public static final String ASSISTANT_CARD="turbo_ui_card_assistant";
    public static final int RESPONSE_CAP=131072;
    public static final int BODY_CAP=8192;
    /** Foreground poll: idle 30s; faster while any awaiting_phone_approval. */
    public static final int POLL_IDLE_MS=30000;
    public static final int POLL_AWAITING_MS=7000;

    static final String PATH=
        "^/v1/(identity|jobs(/[a-f0-9]{32})?|phone/jobs/[a-f0-9]{32}/(approve|receipt))$";
    static final String TOKEN_RE="^[A-Za-z0-9_-]{32,128}$";
    static final String DEVICE_RE="^[A-Za-z0-9_-]{1,64}$";
    static final String ID_RE="^[a-f0-9]{32}$";
    static final String HASH_RE="^[a-f0-9]{64}$";

    /** Stub icons for offline encode checks (zeros); device publish uses CardIcons via CardTransport. */
    public static final CardCodec.Icons STUB_ICONS=(i,s)->new byte[s*s/8];

    /** Injectable HTTP for tests; production uses BoundedHTTP. */
    public interface Transport{byte[] exchange(String url,Map<String,String> headers,String body,int cap)throws Exception;}
    public static Transport transport=(url,headers,body,cap)->BoundedHTTP.request(url,headers,body,cap);

    /** Normalize HTTPS base: optional path prefix (e.g. /turbo); no query/fragment/userInfo; port default or 1–65535.
     * Trailing slash stripped so base+"/v1/…" joins cleanly. Null if invalid. */
    public static String origin(String input){
        if(input==null||input.length()>512)return null;
        try{
            URI u=new URI(input.trim());
            if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getHost().isEmpty()
                ||u.getUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null)return null;
            String path=u.getPath();
            if(path==null||path.isEmpty()||"/".equals(path))path="";
            else{
                if(path.endsWith("/"))path=path.substring(0,path.length()-1);
                if(!path.matches("^/[A-Za-z0-9_-]{1,32}$"))return null;
            }
            int port=u.getPort();
            if(port!=-1&&(port<1||port>65535))return null;
            return new URI("https",null,u.getHost(),port,path.isEmpty()?null:path,null,null).toASCIIString();
        }catch(Exception e){return null;}
    }

    public static boolean tokenOk(String t){return t!=null&&t.matches(TOKEN_RE);}
    public static boolean deviceOk(String d){return d!=null&&d.matches(DEVICE_RE);}
    public static boolean pathOk(String p){return p!=null&&p.matches(PATH);}

    public static String digest(Map<String,Object> doc){
        try{
            if(doc==null)return null;
            CardCodec.encode(doc,STUB_ICONS);
            byte[] raw=canonical(doc);
            if(raw.length>6000)return null;
            byte[] h=MessageDigest.getInstance("SHA-256").digest(raw);
            StringBuilder s=new StringBuilder(64);
            for(byte v:h)s.append(String.format(Locale.ROOT,"%02x",v&255));
            return s.toString();
        }catch(Exception e){return null;}
    }

    private static boolean whole(Object o,long lo,long hi){
        if(!(o instanceof Long||o instanceof Integer))return false;
        long n=((Number)o).longValue();
        return n>=lo&&n<=hi;
    }

    /**
     * Job is phone-approvable: id/hash/device/state/revision/expires/document/card/hash match.
     * Requires document.id == turbo_ui_card_assistant and CardCodec.encode success.
     */
    public static boolean validJob(Object raw,String device){return validJob(raw,device,System.currentTimeMillis()/1000.0,STUB_ICONS);}
    public static boolean validJob(Object raw,String device,double nowSec,CardCodec.Icons icons){
        if(!(raw instanceof Map)||!deviceOk(device))return false;
        Map<String,Object> j=map(raw);
        Object id=j.get("id"),hash=j.get("hash"),dev=j.get("device"),state=j.get("state"),card=j.get("card");
        if(!(id instanceof String)||!((String)id).matches(ID_RE))return false;
        if(!(hash instanceof String)||!((String)hash).matches(HASH_RE))return false;
        if(!device.equals(dev)||!"awaiting_phone_approval".equals(state))return false;
        if(!whole(j.get("revision"),1,2147483647L))return false;
        Object exp=j.get("expires");
        if(!(exp instanceof Number))return false;
        double expires=((Number)exp).doubleValue();
        if(!Double.isFinite(expires)||expires<=nowSec)return false;
        Object docObj=j.get("document");
        if(!(docObj instanceof Map))return false;
        Map<String,Object> doc=map(docObj);
        try{CardCodec.encode(doc,icons==null?STUB_ICONS:icons);}catch(RuntimeException e){return false;}
        if(!ASSISTANT_CARD.equals(doc.get("id"))||!ASSISTANT_CARD.equals(card)||!card.equals(doc.get("id")))return false;
        String dig=digest(doc);
        return dig!=null&&dig.equals(hash);
    }

    /** If list contains sending/outcome_unknown for this device, return user-facing block reason; else null. */
    public static String blockReason(List<?> jobs,String device){
        if(jobs==null||!deviceOk(device))return "任务列表无效";
        for(Object o:jobs){
            if(!(o instanceof Map))continue;
            Map<String,Object> j=map(o);
            if(!device.equals(j.get("device")))continue;
            Object st=j.get("state");
            if("sending".equals(st))return "已有任务发送中，请先核对眼镜并完成回执；不要重复批准。";
            if("outcome_unknown".equals(st))return "已有任务结果未知，请先核对眼镜并完成回执；不要盲重试。";
        }
        return null;
    }

    /** Prefer awaiting interval when any awaiting_phone_approval for device; else idle. */
    public static int pollIntervalMs(List<?> jobs,String device){
        if(jobs!=null&&deviceOk(device))for(Object o:jobs){
            if(!(o instanceof Map))continue;
            Map<String,Object> j=map(o);
            if(device.equals(j.get("device"))&&"awaiting_phone_approval".equals(j.get("state")))return POLL_AWAITING_MS;
        }
        return POLL_IDLE_MS;
    }

    /** Map CardTransport status string to honest receipt state/code. Never invent device_accepted. */
    public static Map<String,String> receiptFromStatus(String status){
        Map<String,String> r=new LinkedHashMap<>();
        String s=status==null?"":status;
        if(s.contains("已确认并回读")||s.contains("已置顶并回读")||s.contains("本卡已移除")){
            r.put("state","device_accepted");r.put("code","card_transport_ok");return r;
        }
        if(s.contains("结果未知")||s.contains("结果未确认")||s.contains("无回执")||s.contains("超时")){
            r.put("state","outcome_unknown");r.put("code","card_transport_timeout");return r;
        }
        if(s.contains("发送失败")||s.contains("拒绝")||s.contains("校验失败")||s.contains("未发送")
            ||s.contains("请先连接")||s.contains("连接已变化")||s.contains("不符")){
            r.put("state","failed");r.put("code","card_transport_failed");return r;
        }
        r.put("state","outcome_unknown");r.put("code","card_transport_uncertain");
        return r;
    }

    /**
     * First awaiting_phone_approval job id for device that is not in {@code attempted}.
     * Null if blocked by sending/outcome_unknown, list invalid, or none left.
     */
    public static String pickAutoJobId(List<?> jobs,String device,Set<String> attempted){
        if(jobs==null||!deviceOk(device))return null;
        if(blockReason(jobs,device)!=null)return null;
        for(Object o:jobs){
            if(!(o instanceof Map))continue;
            Map<String,Object> j=map(o);
            if(!device.equals(j.get("device"))||!"awaiting_phone_approval".equals(j.get("state")))continue;
            Object id=j.get("id");
            if(!(id instanceof String)||!((String)id).matches(ID_RE))continue;
            if(attempted!=null&&attempted.contains(id))continue;
            return (String)id;
        }
        return null;
    }

    /** Server 409 on approve means already processed — freeze; do not re-approve or invent device_accepted. */
    public static boolean httpConflictAlreadyApproved(String message){
        if(message==null)return false;
        return message.contains("HTTP 409")||message.contains("409 Conflict");
    }

    public static Object request(String originIn,String token,String path,String method,Map<String,Object> body)throws Exception{
        String origin=origin(originIn);
        if(origin==null||!tokenOk(token)||!pathOk(path)||!("GET".equals(method)||"POST".equals(method)))
            throw new IOException("请配置 HTTPS 服务和本机专用 phone Token。");
        if("POST".equals(method)&&(body==null||!(body instanceof Map)))
            throw new IOException("请求格式错误，未发送。");
        String payload=null;
        if(body!=null){
            byte[] raw=canonical(body);
            if(raw.length>BODY_CAP)throw new IOException("请求超限，未发送。");
            payload=new String(raw,StandardCharsets.UTF_8);
        }
        Map<String,String> headers=new LinkedHashMap<>();
        headers.put("Authorization","Bearer "+token);
        headers.put("Accept","application/json");
        byte[] resp=transport.exchange(origin+path,headers,payload,RESPONSE_CAP);
        Object value=BoundedJson.read(resp);
        if(!(value instanceof Map)&&!(value instanceof List))throw new IOException("服务返回无效 JSON。");
        return value;
    }
}
