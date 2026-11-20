package com.turboio.addon;

import java.util.*;

/** Offline DashboardPhone policy: origin/token/path/validJob/block/hash/pickAuto/409 — no real HTTP. */
public final class DashboardPhoneTest {
    private static int n;
    private static void check(boolean c,String m){if(!c)throw new AssertionError(m);n++;}
    private static String rep(char c,int n){char[] a=new char[n];java.util.Arrays.fill(a,c);return new String(a);}

    private static Map<String,Object> textDoc(){
        Map<String,Object> c=new TreeMap<>();
        c.put("id","t1");c.put("kind","text");c.put("x",12);c.put("y",12);c.put("w",220);c.put("h",22);
        c.put("text","hello");c.put("font",18);c.put("align","left");
        Map<String,Object> doc=new TreeMap<>();
        doc.put("schema",1);doc.put("id",DashboardPhone.ASSISTANT_CARD);doc.put("name","便笺");
        doc.put("components",new ArrayList<>(Collections.singletonList(c)));
        return doc;
    }

    private static Map<String,Object> job(Map<String,Object> doc,String device,String state,long expires){
        String hash=DashboardPhone.digest(doc);
        Map<String,Object> j=new LinkedHashMap<>();
        j.put("id","0123456789abcdef0123456789abcdef");
        j.put("hash",hash);j.put("device",device);j.put("state",state);
        j.put("revision",1);j.put("expires",expires);j.put("card",doc.get("id"));j.put("document",doc);
        return j;
    }

    public static void main(String[] args)throws Exception{
        // origin
        check(DashboardPhone.origin("https://api.example.com")!=null,"https origin");
        check(DashboardPhone.origin("https://api.example.com:8443").equals("https://api.example.com:8443"),"custom port");
        check(DashboardPhone.origin("https://api.example.com/")!=null,"trailing slash ok");
        check(DashboardPhone.origin("http://api.example.com")==null,"no http");
        check(DashboardPhone.origin("https://user:pass@api.example.com")==null,"no userInfo");
        check(DashboardPhone.origin("https://your-core-host.example.com/turbo/").equals("https://your-core-host.example.com/turbo"),"turbo prefix");
        check(DashboardPhone.DEFAULT_ORIGIN.equals("https://your-core-host.example.com/turbo")
            &&DashboardPhone.DEFAULT_ORIGIN.equals(DashboardPhone.origin(DashboardPhone.DEFAULT_ORIGIN)),"DEFAULT_ORIGIN");
        check(DashboardPhone.origin("https://api.example.com/turbo")!=null,"path prefix ok");
        check(DashboardPhone.origin("https://api.example.com/v1/jobs")==null,"no nested api path");
        check(DashboardPhone.origin("https://api.example.com/../x")==null,"no dot segments");
        check(DashboardPhone.origin("https://api.example.com?x=1")==null,"no query");
        check(DashboardPhone.origin("https://api.example.com#x")==null,"no fragment");
        check(DashboardPhone.origin("https://api.example.com:0")==null,"port 0 bad");
        check(DashboardPhone.origin("https://api.example.com:65536")==null,"port high bad");
        check(DashboardPhone.origin(null)==null&&DashboardPhone.origin("")==null,"empty");

        // token / device / path
        check(DashboardPhone.tokenOk(rep('a',32)),"token 32");
        check(!DashboardPhone.tokenOk(rep('a',31)),"token short");
        check(!DashboardPhone.tokenOk(rep('a',129)),"token long");
        check(!DashboardPhone.tokenOk("bad token!!"),"token charset");
        check(DashboardPhone.deviceOk("fanwang_io"),"default device");
        check(!DashboardPhone.deviceOk(""),"empty device");
        check(DashboardPhone.pathOk("/v1/identity"),"path identity");
        check(DashboardPhone.pathOk("/v1/jobs"),"path jobs");
        check(DashboardPhone.pathOk("/v1/jobs/0123456789abcdef0123456789abcdef"),"path job id");
        check(DashboardPhone.pathOk("/v1/phone/jobs/0123456789abcdef0123456789abcdef/approve"),"path approve");
        check(DashboardPhone.pathOk("/v1/phone/jobs/0123456789abcdef0123456789abcdef/receipt"),"path receipt");
        check(!DashboardPhone.pathOk("/v1/cards"),"path cards denied");
        check(!DashboardPhone.pathOk("/v1/jobs/xyz"),"path bad id");

        // digest + validJob
        Map<String,Object> doc=textDoc();
        String hash=DashboardPhone.digest(doc);
        check(hash!=null&&hash.matches("^[a-f0-9]{64}$"),"digest hex");
        long future=System.currentTimeMillis()/1000L+3600;
        Map<String,Object> ok=job(doc,"fanwang_io","awaiting_phone_approval",future);
        check(DashboardPhone.validJob(ok,"fanwang_io"),"valid awaiting");
        check(!DashboardPhone.validJob(ok,"other_device"),"device mismatch");
        Map<String,Object> wrongState=new LinkedHashMap<>(ok);wrongState.put("state","sending");
        check(!DashboardPhone.validJob(wrongState,"fanwang_io"),"not awaiting");
        Map<String,Object> expired=new LinkedHashMap<>(ok);expired.put("expires",1L);
        check(!DashboardPhone.validJob(expired,"fanwang_io",100.0,DashboardPhone.STUB_ICONS),"expired");
        Map<String,Object> badHash=new LinkedHashMap<>(ok);badHash.put("hash",rep('0',64));
        check(!DashboardPhone.validJob(badHash,"fanwang_io"),"hash mismatch");
        Map<String,Object> wrongCard=new LinkedHashMap<>(ok);
        Map<String,Object> other=textDoc();other.put("id","turbo_ui_card_other");
        wrongCard.put("document",other);wrongCard.put("card","turbo_ui_card_other");
        wrongCard.put("hash",DashboardPhone.digest(other));
        // digest may fail encode if id ok — turbo_ui_card_other encodes; but ASSISTANT_CARD required
        check(DashboardPhone.digest(other)!=null,"other card encodes");
        check(!DashboardPhone.validJob(wrongCard,"fanwang_io"),"assistant id required");

        // block sending / outcome_unknown
        List<Object> list=new ArrayList<>();
        list.add(job(doc,"fanwang_io","awaiting_phone_approval",future));
        check(DashboardPhone.blockReason(list,"fanwang_io")==null,"no block");
        list.add(job(doc,"fanwang_io","sending",future));
        check(DashboardPhone.blockReason(list,"fanwang_io")!=null,"block sending");
        List<Object> unknown=Collections.singletonList(job(doc,"fanwang_io","outcome_unknown",future));
        check(DashboardPhone.blockReason(unknown,"fanwang_io")!=null,"block unknown");
        check(DashboardPhone.blockReason(unknown,"other")==null,"other device no block");

        // poll interval
        check(DashboardPhone.pollIntervalMs(Collections.singletonList(ok),"fanwang_io")==DashboardPhone.POLL_AWAITING_MS,"poll awaiting");
        check(DashboardPhone.pollIntervalMs(Collections.emptyList(),"fanwang_io")==DashboardPhone.POLL_IDLE_MS,"poll idle");

        // receipt mapping — never invent success from empty/uncertain
        check("device_accepted".equals(DashboardPhone.receiptFromStatus("眼镜已确认并回读卡片，其他卡片保留").get("state")),"receipt ok");
        check("failed".equals(DashboardPhone.receiptFromStatus("发送失败，结果未确认；未自动重发").get("state"))
            ||"outcome_unknown".equals(DashboardPhone.receiptFromStatus("发送失败，结果未确认；未自动重发").get("state")),"receipt fail-ish");
        check("outcome_unknown".equals(DashboardPhone.receiptFromStatus("20秒无回执，修改结果未知；请核对眼镜，未自动重发").get("state")),"receipt timeout");
        check(!"device_accepted".equals(DashboardPhone.receiptFromStatus("草稿校验失败，未发送").get("state")),"no fake accept");
        check(!"device_accepted".equals(DashboardPhone.receiptFromStatus("").get("state")),"empty not accepted");

        // pickAutoJobId + 409 conflict helper (auto-approve session policy)
        Set<String> attempted=new LinkedHashSet<>();
        check("0123456789abcdef0123456789abcdef".equals(DashboardPhone.pickAutoJobId(Collections.singletonList(ok),"fanwang_io",attempted)),"pick awaiting");
        check(DashboardPhone.pickAutoJobId(list,"fanwang_io",attempted)==null,"pick blocked by sending");
        check(DashboardPhone.pickAutoJobId(Collections.singletonList(ok),"fanwang_io",attempted)!=null,"pick before attempt");
        attempted.add("0123456789abcdef0123456789abcdef");
        check(DashboardPhone.pickAutoJobId(Collections.singletonList(ok),"fanwang_io",attempted)==null,"pick skips attempted");
        check(DashboardPhone.pickAutoJobId(Collections.emptyList(),"fanwang_io",attempted)==null,"pick empty");
        check(DashboardPhone.httpConflictAlreadyApproved("HTTP 409"),"409");
        check(DashboardPhone.httpConflictAlreadyApproved("HTTP 409 Conflict"),"409 conflict");
        check(!DashboardPhone.httpConflictAlreadyApproved("HTTP 500"),"not 500");
        check(!DashboardPhone.httpConflictAlreadyApproved(null)&&!DashboardPhone.httpConflictAlreadyApproved(""),"empty not 409");

        // mock transport: path + non-200 style error surfaces as exception from request wrapper
        DashboardPhone.Transport old=DashboardPhone.transport;
        try{
            DashboardPhone.transport=(url,headers,body,cap)->{
                check(url.startsWith("https://api.example.com/v1/identity"),"url path");
                check(headers.get("Authorization").startsWith("Bearer "),"bearer");
                return "{\"role\":\"phone\",\"device\":\"fanwang_io\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            };
            Object id=DashboardPhone.request("https://api.example.com",rep('a',32),"/v1/identity","GET",null);
            check(id instanceof Map&&"phone".equals(BoundedJson.map(id).get("role")),"identity mock");
            try{
                DashboardPhone.request("http://bad",rep('a',32),"/v1/identity","GET",null);
                check(false,"http should fail");
            }catch(Exception e){check(true,"http refused");}
            try{
                DashboardPhone.request("https://api.example.com",rep('a',32),"/v1/cards","GET",null);
                check(false,"path should fail");
            }catch(Exception e){check(true,"path refused");}
        }finally{DashboardPhone.transport=old;}

        System.out.println("DashboardPhoneTest: "+n+" checks; origin/token/validJob/block/hash/pickAuto/409 offline OK");
    }
}
