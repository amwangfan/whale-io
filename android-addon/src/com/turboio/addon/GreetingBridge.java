package com.turboio.addon;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.*;

/** F2 proactive greeting (GREETING_SID auto-open) — Android shell over GreetingSession (pure core).
 *  Phone pushes one 512×128 grayscale frame to the fixed GREETING_SID; the glasses auto-open the display page
 *  on it (firmware greeting-v1). One file AND its matching TDR1 reply must both land before the next packet.
 *  Session recovery (forced QUERY, three-branch STALE, tx highwater) lives in GreetingSession.
 *  Evidence: rayneo-tvp2c/PHONE-APP-INTERFACE.md §3, tmp/tvp2-611/work/GREETING-STALE-611.md. */
final class GreetingBridge {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static volatile SharedPreferences prefs;private static volatile long memoryTx;
    private static GreetingSession session;
    private static String peer="";
    private static boolean installed;
    private static Runnable changed;
    private GreetingBridge(){}
    static void listen(Runnable r){changed=r;wire();}
    static String status(){return session==null?"未推送":session.note();}
    static boolean busy(){return session!=null&&session.busy();}

    /** The greeting drives its own shared TransferGate via NativeTransfer.send but owns no TdpSession, so nothing
     *  else acks that gate (DisplayBridge only acks the 显示测试 session): ack the in-flight packet on every
     *  matching TDR1 reply; success replies map to 0 so the gate settles CONFIRMED. */
    private static void wire(){
        if(installed)return;installed=true;
        session=new GreetingSession(()->SystemClock.elapsedRealtime()/1000.0,
            p->{long req=Tdp.u32(p,12);
                android.util.Log.i("TurboIOTransfer","greeting op="+p[5]+" req="+req+(p.length>=40&&(p[5]>=Tdp.BEGIN)?" tx="+Tdp.u32(p,32):"")+(p[5]==Tdp.CHUNK?" at="+Tdp.u32(p,36):""));
                boolean ok=NativeTransfer.send(Tdp.FILE,p,req,GreetingSession.SID,5000,(state,result)->MAIN.post(()->{
                    GreetingSession s=session;if(s==null)return;
                    s.fileDone(state==TransferGate.State.CONFIRMED||state==TransferGate.State.REJECTED);}));
                /* Fast path: bulk gates self-ack — merged replies are accounting, and an unacked gate would settle
                 *  UNCERTAIN (the quarantine trap). The gate still proves the file terminal before CONFIRMED. */
                GreetingSession s0=session;
                if(ok&&s0!=null&&s0.fastMode()&&(p[5]==Tdp.BEGIN||p[5]==Tdp.CHUNK)){String dev=NativeTransfer.connected();if(!dev.isEmpty())NativeTransfer.ack(dev,req,GreetingSession.SID,0);}
                if(!ok){GreetingSession s=session;if(s!=null)s.lastSendRefusal=NativeTransfer.status();}
                return ok;},
            (r,delayS)->MAIN.postDelayed(r,(long)(delayS*1000)),
            new GreetingSession.TxStore(){
                public long load(){SharedPreferences p=prefs();return p==null?memoryTx:p.getLong("txHighwater",0);}
                public void save(long v){memoryTx=v;SharedPreferences p=prefs();if(p!=null)p.edit().putLong("txHighwater",v).apply();}
            });
        session.listen(()->{GreetingSession s=session;if(s!=null){android.util.Log.i("TurboIOTransfer","greeting: "+s.note());export(s);}Runnable r=changed;if(r!=null)try{r.run();}catch(RuntimeException ignored){}});
        session.hooks(()->notifyOccupied());
        session.linkProbe(()->{String d=NativeTransfer.connected();return d!=null&&!d.isEmpty();});
        NativeTransfer.receive(GreetingBridge::message);
    }
    /** Set by TestEntry on each accepted test trigger; surfaces in the status export as the test-origin marker. */
    static volatile String lastTestOrigin="";
    /** Diagnostics: export the greeting session state on change (throttled), like the OTA guard status file.
     *  The throttle only collapses REPEATED identical states — any real state change exports immediately, so the
     *  TDP_VISIBLE follow-up (~4 ms after UI_SUBMITTED) is never swallowed (2026-11-17 V4 live evidence). */
    private static long lastExportAt;private static String lastExport="";
    private static void export(GreetingSession s){
        long now=SystemClock.elapsedRealtime();
        String text="ANDROID · GREETING-01\n内容："+s.contentLabel()+"\n"+s.note()+"\n通道："+NativeTransfer.status()+(lastTestOrigin.isEmpty()?"":"\ntest-origin："+lastTestOrigin)+"\n";
        if(text.equals(lastExport))return;lastExport=text;lastExportAt=now;
        try{Object c=Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);if(!(c instanceof Context))return;
            java.io.File root=((Context)c).getApplicationContext().getExternalFilesDir(null);if(root==null)return;
            java.io.File tmp=new java.io.File(root,"greeting-status.tmp"),dest=new java.io.File(root,"greeting-status.txt");
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(tmp)){out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
            tmp.renameTo(dest);
        }catch(Throwable ignored){}
    }
    /** 2026-11-16 ruling: the occupier's page is never closed; tell the user the greeting is queued instead. */
    private static void notifyOccupied(){
        try{Object c=Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);if(!(c instanceof Context))return;
            Context app=((Context)c).getApplicationContext();
            android.app.NotificationManager nm=app.getSystemService(android.app.NotificationManager.class);if(nm==null)return;
            String ch="turboio_greeting";
            nm.createNotificationChannel(new android.app.NotificationChannel(ch,"问候推送",android.app.NotificationManager.IMPORTANCE_LOW));
            nm.notify(7392,new android.app.Notification.Builder(app,ch)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("问候稍后送达")
                .setContentText("眼镜正在显示其他内容；问候已排队，稍后自动重推。")
                .setAutoCancel(true).build());
        }catch(Throwable ignored){}
    }
    private static SharedPreferences prefs(){
        if(prefs!=null)return prefs;
        // ActivityThread is a hidden API (not in the compile stub): reach it by reflection, like OfficialOtaBridge.
        try{Object c=Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);if(c instanceof Context)prefs=((Context)c).getApplicationContext().getSharedPreferences("turboio_greeting_v1",0);}catch(Throwable ignored){}
        return prefs;
    }

    /** Push a 512×128 grayscale frame to the greeting page (auto-opens on the glasses). */
    static void push(byte[] gray){push(gray,"greeting","问候",null);}
    /** Play a glasses-local animation via the greeting channel's session (ANIM_LOCAL negotiated). Test-entry path. */
    static boolean animPlay(long assetId,boolean loop,int fps){wire();GreetingSession s=session;if(s==null)return false;
        if(peer.isEmpty()){String d=NativeTransfer.connected();if(d!=null&&!d.isEmpty())peer=d;}   /* nb1124: cold start — adopt the connected peer so the negotiation probe's replies route back */
        return s.animPlay(assetId,loop,fps);}
    /** Sticker push (2026-11-17): same channel/session, separate PushPolicy key and user-facing label. */
    static void push(byte[] gray,String content,String label){push(gray,content,label,null);}
    /** formPin (2026-11-19 ruling): TestEntry pins the form for test-surface determinism; null = PushPolicy auto. */
    static void push(byte[] gray,String content,String label,PushPolicy.Form formPin){
        wire();
        String device=NativeTransfer.connected();
        if(device==null||device.isEmpty()){session.push(null,gray,content,label,formPin);return;}   // let the core publish the refusal note
        peer=device;
        session.push(device,gray,content,label,formPin);
    }
    private static void message(String from,byte[] bytes){
        GreetingSession s=session;if(s==null)return;
        Tdp.Reply r=Tdp.carrierDecode(15,bytes);if(r==null)return;
        /* Replies for the occupier-close/QUERY steps carry the OCCUPIER's sid (that is the point of branch 3),
         * so there is no sid filter here: the core matches replies by request id only. The TransferGate ack must
         * use the session the gate was opened with (always GREETING_SID in this channel) — a QUERY's own sid is 0
         * and its reply (NO_SESSION) carries sid 0, which would never match a SID-scoped gate (case-A collision). */
        long inflight=s.inFlightRequest();
        if(r.request!=0&&r.request==inflight)NativeTransfer.ack(from,r.request,GreetingSession.SID,(r.result==Tdp.STAGED||r.result==Tdp.UI_SUBMITTED)?0:r.result);
        if(!peer.isEmpty()&&peer.equals(from))s.onReply(r);
    }
}
