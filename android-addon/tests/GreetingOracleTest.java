package com.turboio.addon;
import java.io.*;import java.util.*;

/** Task ⑦ co-test: GreetingSession over the wire against the REAL 611 display_runtime (+ greeting auto-open
 *  model). Happy path, occupier close-and-reopen, and mechanism-B revision desync (glasses reboot mid-link). */
public final class GreetingOracleTest {
    static int n;static void ok(boolean b,String why){n++;if(!b)throw new AssertionError("oracle "+n+" "+why);}
    static String oracle;
    static final class Glasses implements Closeable{
        final Process p;final DataOutputStream out;final DataInputStream in;long crc,revision;
        Glasses(String preSid)throws IOException{p=new ProcessBuilder(oracle,preSid).start();out=new DataOutputStream(p.getOutputStream());in=new DataInputStream(p.getInputStream());}
        static void le(DataOutputStream o,long v)throws IOException{for(int i=0;i<4;i++)o.write((int)(v>>(8*i))&255);}
        long rle()throws IOException{long v=0;for(int i=0;i<4;i++)v|=(long)in.readUnsignedByte()<<(8*i);return v;}
        Tdp.Reply handle(byte[] packet,double now)throws IOException{le(out,(long)(now*1000));le(out,packet.length);out.write(packet);out.flush();byte[] raw=new byte[40];in.readFully(raw);crc=rle();revision=rle();return Tdp.replyDecode(raw);}
        /** Fast mode: BEGIN/CHUNK get no reply (merged model) — write the packet, read nothing. */
        void sendOnly(byte[] packet,double now)throws IOException{le(out,(long)(now*1000));le(out,packet.length);out.write(packet);out.flush();}
        /** Opportunistic drain for fast mode: read one reply frame only if the glasses emitted one (NAK). */
        Tdp.Reply poll()throws IOException{if(in.available()<48)return null;byte[] raw=new byte[40];in.readFully(raw);crc=rle();revision=rle();return Tdp.replyDecode(raw);}
        /** Read the unsolicited extra frame the runtime emits right after a COMMIT (the TDP_VISIBLE event). */
        Tdp.Reply readExtra()throws IOException{byte[] raw=new byte[40];in.readFully(raw);crc=rle();revision=rle();return Tdp.replyDecode(raw);}
        public void close(){p.destroy();}
    }
    static final class Rig{
        double now=1;byte[] packet;final ArrayDeque<Runnable> posted=new ArrayDeque<>();final ArrayDeque<Double> gaps=new ArrayDeque<>();
        final ArrayList<byte[]> history=new ArrayList<>();final long[] store={0};final boolean[] link={true};
        boolean skipVisible;   /* banner frames: the firmware sends no VISIBLE by design — never drain for one */
        final GreetingSession s=new GreetingSession(()->now,p->{packet=p;history.add(p);return true;},(r,d)->{posted.add(r);gaps.add(d);},new GreetingSession.TxStore(){public long load(){return store[0];}public void save(long v){store[0]=v;}});
        {s.linkProbe(()->link[0]);}
        /** Fire the OLDEST scheduled step per call, at its scheduled time (a wake's own reschedule is a later call —
         *  mirrors real time passing between wakes; a drain-whole-queue pump would fire them all at once). */
        void pump(){if(posted.isEmpty())return;Double g=gaps.poll();Runnable r=posted.poll();if(g!=null)now+=g;r.run();}
        void drive(Glasses g,byte[] frame)throws IOException{drive(g,frame,0);}
        /** Fast-path drive: bulk packets get only the file terminal (the oracle never replies to them);
         *  QUERY/COMMIT/CLOSE keep their replies. loseOneCommit drops the COMMIT's reply once (T4). */
        void driveFast(Glasses g,byte[] frame,boolean loseOneCommit)throws IOException{
            int steps=0;boolean lost=false;
            while(s.busy()&&steps<400){steps++;
                if(packet!=null){byte[] inFlight=packet;int op=inFlight[5];
                    if(op==Tdp.BEGIN||op==Tdp.CHUNK){g.sendOnly(inFlight,now);s.fileDone(true);Tdp.Reply q0=g.poll();if(q0!=null)s.onReply(q0);}
                    else{Tdp.Reply q=g.handle(inFlight,now);s.fileDone(true);
                        if(op==Tdp.COMMIT&&loseOneCommit&&!lost){lost=true;q=null;}   /* merged reply physically lost */
                        if(q!=null)s.onReply(q);
                        if(op==Tdp.COMMIT){Tdp.Reply vis=g.readExtra();if(vis!=null)s.onReply(vis);}
                        if(q==null&&op==Tdp.COMMIT){now+=5;s.fileDone(false);}   /* the gate's 5 s UNCERTAIN terminal */}
                    now+=0.05;
                    if(packet==inFlight)packet=null;continue;}
                pump();}
            ok(steps<400,"fast drive converges");}
        /** replyLossUntil: while now < it, TDR1 replies are dropped before reaching the phone (BLE loss window). */
        void drive(Glasses g,byte[] frame,double replyLossUntil)throws IOException{
            int steps=0;
            while(s.busy()&&steps<400){steps++;
                if(packet!=null){   /* answer the in-flight packet before any scheduled wake — replies land in ms */
                    byte[] inFlight=packet;
                    Tdp.Reply r=g.handle(inFlight,now);if(r!=null&&now<replyLossUntil)r=null;   /* reply physically lost */
                    if(r==null){now+=5;s.fileDone(false);now+=0.05;}
                    else{s.fileDone(true);s.onReply(r);now+=0.05;
                        if(inFlight[5]==Tdp.COMMIT&&!skipVisible){Tdp.Reply vis=g.readExtra();if(vis!=null)s.onReply(vis);}}   /* drain the additive VISIBLE event */
                    if(packet==inFlight)packet=null;   /* consumed; only a freshly sent packet stays */
                    continue;}
                pump();}
            ok(steps<400,"drive converges");
            ok(!s.busy(),"frame done");ok(s.note().startsWith("已提交")||s.note().startsWith("已上屏"),"delivered: "+s.note());
            ok(g.crc==Tdp.crc(frame,0,frame.length),"glasses show the exact pixels");
        }
    }
    static byte[] frame(int mul){byte[] f=new byte[Tdp.PIXELS];for(int i=0;i<f.length;i++)f[i]=(byte)(i*mul);return f;}
    static void put32(byte[] p,int at,long v){for(int i=0;i<4;i++)p[at+i]=(byte)(v>>(8*i));}   /* crafting malformed packets */
    public static void main(String[] a)throws Exception{
        oracle=a[0];
        // 1. cold glasses (no page): forced QUERY → NO_SESSION → BEGIN base=0 → frame lands pixel-exact;
        //    the additive TDP_VISIBLE event (drained by drive) flips the terminal note to 已上屏.
        try(Glasses g=new Glasses("0")){Rig r=new Rig();byte[] f=frame(1);
            ok(r.s.push("dev",f),"push");ok(r.packet[5]==Tdp.QUERY,"forced QUERY first");
            r.drive(g,f);
            ok(r.s.note().startsWith("已上屏"),"VISIBLE extra flipped the terminal note: "+r.s.note());}
        // 2. same link, second push: no re-query; glasses now at revision=1 → BEGIN(0) STALEs, the reply-carried
        //    revision rebases, and the frame still lands (mechanism B recovery over the real runtime).
        try(Glasses g=new Glasses("0")){Rig r=new Rig();byte[] f=frame(1);r.s.push("dev",f);r.drive(g,f);
            byte[] f2=frame(2);ok(r.s.push("dev",f2),"second push");ok(r.packet[5]==Tdp.BEGIN,"cached session, straight to BEGIN");
            r.drive(g,f2);ok(g.revision==2,"revision advanced");}
        // 3. mechanism B (glasses reboot): the link drop forces a fresh QUERY.
        {   Rig r=new Rig();
            try(Glasses g1=new Glasses("0")){byte[] f=frame(1);r.s.push("dev",f);r.drive(g1,f);}
            r.s.disconnected();
            try(Glasses g2=new Glasses("0")){byte[] f=frame(3);ok(r.s.push("dev",f),"push after reboot");
                ok(r.packet[5]==Tdp.QUERY,"reboot forces re-handshake");
                r.drive(g2,f);}
        }
        // 4a. occupier never frees (2026-11-16 ruling): defer×5 → drop; NOT ONE close packet ever left the phone.
        try(Glasses g=new Glasses("100002")){Rig r=new Rig();byte[] f=frame(4);
            r.s.push("dev",f);
            int steps=0;while(r.s.busy()&&steps<60){steps++;
                if(r.packet!=null){byte[] inFlight=r.packet;
                    Tdp.Reply q=g.handle(inFlight,r.now);r.s.fileDone(true);r.s.onReply(q);r.now+=0.05;
                    if(r.packet==inFlight)r.packet=null;continue;}
                r.pump();}
            ok(!r.s.busy()&&r.s.note().contains("眼镜一直忙"),"defers exhausted → drop");
            ok(r.history.stream().noneMatch(p->p[5]==Tdp.CLOSE),"no CLOSE ever sent");
            ok(r.history.stream().noneMatch(p->p[5]==Tdp.BEGIN),"no BEGIN while occupied");}
        // 4b. occupier frees at 30 s (user leaves the test page): the queued greeting lands pixel-exact.
        try(Glasses g=new Glasses("100002@30000")){Rig r=new Rig();byte[] f=frame(5);
            r.s.push("dev",f);r.drive(g,f);
            ok(r.history.stream().noneMatch(p->p[5]==Tdp.CLOSE),"still no CLOSE");
            ok(r.history.stream().anyMatch(p->p[5]==Tdp.QUERY),"deferred re-queries happened");}
        // 5. F1-B × defer: occupier page, then the link drops >3 s inside the defer window — the firmware retires
        //    the page; the parked frame survives, re-handshakes on reconnect (QUERY→NO_SESSION→base 0), delivers.
        try(Glasses g=new Glasses("100002i")){Rig r=new Rig();byte[] f=frame(6);
            r.s.push("dev",f);
            Tdp.Reply q=g.handle(r.packet,r.now);r.s.fileDone(true);r.s.onReply(q);   // QUERY → CAPS(100002) → defer
            ok(r.s.phase()==4,"parked in the defer window");
            r.link[0]=false;r.s.disconnected();                                      // link drops mid-window
            r.pump();                                                                // wake on the dead link → re-park
            ok(r.s.phase()==4,"re-parked without burning retries");
            r.link[0]=true;                                                          // reconnect (>3 s of silence: page retired)
            r.drive(g,f);
            ok(r.history.stream().noneMatch(p->p[5]==Tdp.CLOSE),"still no CLOSE");
            ok(r.history.stream().anyMatch(p->p[5]==Tdp.QUERY),"re-handshake QUERY sent");}
        // 6. F1-B cold restart: greeting page retired after a 3 s+ silent gap; a fresh push on the SAME link hits
        //    STALE (page reopened at revision 0) and the reply-carried rebase completes the delivery.
        try(Glasses g=new Glasses("0i")){Rig r=new Rig();byte[] f=frame(7);
            r.s.push("dev",f);r.drive(g,f);
            r.now+=4;   // link silent >3 s → firmware retires the greeting page (oracle model)
            byte[] f2=frame(8);ok(r.s.push("dev",f2),"push after silent retire");
            r.drive(g,f2);
            long begins=r.history.stream().filter(p->p[5]==Tdp.BEGIN).count();
            ok(begins>=3,"second push needed a rebase retry (STALE→BEGIN)");}
        // 7. case-A truth: cold glasses, no page, no runtime → QUERY is never answered ('q' model). The file gate
        //    terminates UNCERTAIN → cold-start assumption → BEGIN(base=0) auto-opens and the frame lands.
        try(Glasses g=new Glasses("0q")){Rig r=new Rig();byte[] f=frame(9);
            r.s.push("dev",f);
            Tdp.Reply q=g.handle(r.packet,r.now);ok(q==null,"QUERY unanswered (no page, no runtime)");
            r.s.fileDone(false);   // gate UNCERTAIN: file delivered, no TDR1
            ok(r.packet!=null&&r.packet[5]==Tdp.BEGIN&&Tdp.u32(r.packet,16)==0,"gate-terminated QUERY → cold-start BEGIN base=0");
            r.drive(g,f);}
        // 8. reply-loss window on a live session: replies dropped until T; the gate times out (5 s) and the bounded
        //    frame retry takes over — delivery after the window, no new dead end.
        try(Glasses g=new Glasses("0")){Rig r=new Rig();byte[] f=frame(10);
            r.s.push("dev",f);r.drive(g,f);
            byte[] f2=frame(11);ok(r.s.push("dev",f2),"second push");
            r.drive(g,f2,r.now+8);
            ok(r.history.stream().filter(p->p[5]==Tdp.BEGIN).count()>=2,"frame retried with a fresh BEGIN after lost replies");}
        // 9. mid-frame STALE (case B evidence): inject STALE at chunk 5 — bounded rebase restarts the whole frame
        //    against the real runtime and the frame still lands pixel-exact.
        try(Glasses g=new Glasses("0")){Rig r=new Rig();byte[] f=frame(12);
            r.s.push("dev",f);
            int steps=0,chunkN=0;
            while(r.s.busy()&&steps<600){steps++;
                if(r.packet!=null){byte[] inFlight=r.packet;Tdp.Reply q=g.handle(inFlight,r.now);
                    if(inFlight[5]==Tdp.CHUNK&&++chunkN==2){   /* simulate the firmware moving revision mid-frame */
                        Tdp.Reply st=new Tdp.Reply();st.result=Tdp.STALE;st.sid=GreetingSession.SID;st.revision=1;st.request=Tdp.u32(inFlight,12);q=st;}
                    r.s.fileDone(true);r.s.onReply(q);r.now+=0.05;
                    if(r.packet==inFlight)r.packet=null;continue;}
                r.pump();}
            ok(!r.s.busy(),"frame completes after a mid-frame STALE rebase");
            ok(r.s.note().startsWith("已提交"),"delivered: "+r.s.note());
            ok(g.crc==Tdp.crc(f,0,f.length),"pixel-exact after mid-frame rebase");
            ok(r.history.stream().filter(p->p[5]==Tdp.BEGIN).count()>=2,"whole-frame restart happened");
            // self-heal interaction: after the LAST rebase BEGIN, every later packet must carry its tx — an old-tx
            // chunk leaking past the new BEGIN would eat STALE from the runtime's self-heal reset.
            int lastBegin=-1;for(int i=0;i<r.history.size();i++)if(r.history.get(i)[5]==Tdp.BEGIN)lastBegin=i;
            long newTx=Tdp.u32(r.history.get(lastBegin),32);
            boolean leak=false;
            for(int i=lastBegin+1;i<r.history.size();i++){byte[] p=r.history.get(i);if(p[5]>=Tdp.BEGIN&&Tdp.u32(p,32)!=newTx)leak=true;}
            ok(!leak,"no old-tx packet after the last rebase BEGIN (tx "+newTx+")");}
        // 10. V5 fast path T1: FAST2 CAPS → merged replies; frame lands pixel-exact; terminal carries 快路径.
        try(Glasses g=new Glasses("0f")){Rig r=new Rig();byte[] f=frame(30);
            r.s.push("dev",f);r.drive(g,f);r.s.disconnected();byte[] f2=frame(31);r.s.push("dev",f2);Tdp.Reply q=g.handle(r.packet,r.now);ok(q!=null&&q.result==Tdp.CAPS&&(q.flags&Tdp.FEATURE_STREAM)!=0,"FAST2 negotiated on the second push");
            r.s.fileDone(true);r.s.onReply(q);r.now+=0.05;r.pump();
            r.driveFast(g,f2,false);
            ok(r.s.note().startsWith("已上屏"),"fast path delivered+visible: "+r.s.note());
            ok(g.crc==Tdp.crc(f2,0,f2.length),"pixel-exact over merged replies");}
        // 11. V5 fast path T2: chunk 2 dropped on the wire → NAK highwater → bounded rewind → pixel-exact.
        try(Glasses g=new Glasses("0fk2")){Rig r=new Rig();byte[] f=frame(31);
            r.s.push("dev",f);r.drive(g,f);r.s.disconnected();byte[] f2=frame(32);r.s.push("dev",f2);
            Tdp.Reply q=g.handle(r.packet,r.now);r.s.fileDone(true);r.s.onReply(q);r.now+=0.05;r.pump();
            r.driveFast(g,f2,false);
            ok(r.s.note().startsWith("已上屏"),"delivered after NAK rewind: "+r.s.note());
            ok(g.crc==Tdp.crc(f2,0,f2.length),"pixel-exact after the NAK rewind");}
        // 12. V5 fast path T4: the COMMIT's reply is lost → bounded repush with the SAME commit request → the
        //     runtime's (request,digest) idempotency answers UI_SUBMITTED without a re-render (revision unchanged).
        try(Glasses g=new Glasses("0f")){Rig r=new Rig();byte[] f=frame(32);
            r.s.push("dev",f);r.drive(g,f);r.s.disconnected();byte[] f2=frame(33);r.s.push("dev",f2);
            Tdp.Reply q=g.handle(r.packet,r.now);r.s.fileDone(true);r.s.onReply(q);r.now+=0.05;r.pump();
            r.driveFast(g,f2,true);
            ok(!r.s.busy(),"frame completes after the repush");
            ok(g.revision==2,"idempotent: second push committed exactly once more (revision "+g.revision+")");
            ok(g.crc==Tdp.crc(f2,0,f2.length),"pixel-exact after the idempotent repush");}
        // 13. PushPolicy v2 co-test: FORM_FIELD negotiated + foreground=navigation → STATUS probe → BEGIN 13B
        //     with byte44=FORM_BANNER (the banner form).
        try(Glasses g=new Glasses("0FS1")){Rig r=new Rig();byte[] f=frame(33);
            PushPolicy.foreground(PushPolicy.Foreground.IDLE);
            /* page closed at the start: QUERY → NO_SESSION → plain 12B page BEGIN (VISIBLE drained). Only the
             *  second frame negotiates FORM and goes banner — banners send no VISIBLE by design, never drain. */
            r.s.push("dev",f);r.drive(g,f);r.skipVisible=true;r.s.disconnected();r.s.push("dev",f);
            Tdp.Reply q=g.handle(r.packet,r.now);ok(q!=null&&q.result==Tdp.CAPS&&(q.flags&Tdp.FEATURE_FORM)!=0,"FORM_FIELD negotiated");
            r.s.fileDone(true);r.s.onReply(q);r.now+=0.05;
            ok(r.packet!=null&&r.packet[5]==10,"STATUS probe sent after the form negotiation");
            Tdp.Reply st=g.handle(r.packet,r.now);ok(st!=null&&st.result==Tdp.ALIVE,"STATUS answered");
            r.s.fileDone(true);r.s.onReply(st);r.now+=0.05;
            ok(PushPolicy.foreground()==PushPolicy.Foreground.NAVIGATION,"foreground=navigation learned");
            r.pump();
            ok(r.packet!=null&&r.packet[5]==Tdp.BEGIN&&r.packet.length==45&&Tdp.u32(r.packet,24)==13&&r.packet[44]==Tdp.FORM_BANNER,"13B BEGIN carries banner form");
            r.drive(g,f);
            ok(r.s.note().contains("横幅形态")&&!r.s.note().contains("等待上屏确认"),"banner terminal is honest: "+r.s.note());
            Tdp.Reply probe=g.handle(Tdp.status(GreetingSession.SID,777,r.s.base()),r.now);   /* the pipe is FIFO: a leaked VISIBLE extra would surface here */
            ok(probe!=null&&probe.result==Tdp.ALIVE&&probe.request==777,"banner commit emitted no VISIBLE extra");}
        // 14. idle foreground → page form (byte44=0).
        try(Glasses g=new Glasses("0FS0")){Rig r=new Rig();byte[] f=frame(34);
            PushPolicy.foreground(PushPolicy.Foreground.IDLE);
            r.s.push("dev",f);r.drive(g,f);r.s.disconnected();r.s.push("dev",f);
            Tdp.Reply q=g.handle(r.packet,r.now);r.s.fileDone(true);r.s.onReply(q);r.now+=0.05;
            Tdp.Reply st=g.handle(r.packet,r.now);r.s.fileDone(true);r.s.onReply(st);r.now+=0.05;
            ok(PushPolicy.foreground()==PushPolicy.Foreground.IDLE,"foreground=idle");
            r.pump();
            ok(r.packet!=null&&r.packet[5]==Tdp.BEGIN&&r.packet.length==45&&r.packet[44]==Tdp.FORM_PAGE,"13B BEGIN carries page form");
            r.drive(g,f);}
        // 15. reverse gate (legacy 'L': pre-feature firmware) → FORM_FIELD NOT negotiated → the session never
        //     sends STATUS and the BEGIN stays 12B. (Current 611 runtime advertises FORM unconditionally.)
        try(Glasses g=new Glasses("0S1L")){Rig r=new Rig();byte[] f=frame(35);
            r.s.push("dev",f);r.drive(g,f);r.s.disconnected();r.s.push("dev",f);
            Tdp.Reply q=g.handle(r.packet,r.now);r.s.fileDone(true);r.s.onReply(q);r.now+=0.05;r.pump();r.pump();
            ok(r.packet!=null&&r.packet[5]==Tdp.BEGIN&&r.packet.length==44&&Tdp.u32(r.packet,24)==12,"unnegotiated: plain 12-byte BEGIN, no STATUS probe");
            r.drive(g,f);}
        // 16. reverse misuse (legacy 'L'): a 13-byte BEGIN to old firmware is rejected outright (fixed-length gate).
        try(Glasses g=new Glasses("7392L")){Tdp.Reply q=g.handle(Tdp.beginForm(7392,7,0,9,0,Tdp.FORM_BANNER),1);
            ok(q!=null&&q.result==Tdp.BAD_PACKET,"old firmware rejects a 13-byte BEGIN outright");}
        // 17. ANIM_PLAY vs the real runtime gate (ANIM-PLAY-611, the 2026-11-19 defect): the FIXED 4-byte body
        //     {asset_id u16, loop u8, fps_cap u8} is accepted (STAGED, page alive); the legacy 8-byte shape and a
        //     0-length body are rejected (BAD_PACKET); the session surfaces acceptance/refusal by result code.
        try(Glasses g=new Glasses("0")){Rig r=new Rig();byte[] f=frame(37);
            r.s.push("dev",f);r.drive(g,f);   // cold start: QUERY → NO_SESSION — no CAPS seen yet
            r.s.disconnected();r.s.push("dev",f);   // re-handshake on the now-open page: CAPS advertises ANIM_LOCAL
            Tdp.Reply caps=g.handle(r.packet,r.now);ok(caps!=null&&caps.result==Tdp.CAPS&&(caps.flags&Tdp.FEATURE_ANIM)!=0,"ANIM_LOCAL advertised");
            r.s.fileDone(true);r.s.onReply(caps);r.now+=0.05;
            Tdp.Reply stup=g.handle(r.packet,r.now);r.s.fileDone(true);r.s.onReply(stup);r.now+=0.05;   // FORM bit set → STATUS probe; this oracle model has no formF branch → BAD_PACKET → default foreground
            r.drive(g,f);   // page-form frame lands (fast session + per-packet replies absorbed as merged accounting)
            ok(r.s.animPlay(0,true,60),"animPlay accepted locally");
            byte[] ap=r.packet;
            ok(ap!=null&&ap[5]==Tdp.ANIM_PLAY&&ap.length==36&&Tdp.u32(ap,24)==4&&Tdp.u16(ap,32)==0&&ap[34]==1&&ap[35]==60,"fixed wire shape: 36 B, length=4, {u16,u8,u8}");
            Tdp.Reply q=g.handle(ap,r.now);r.s.fileDone(true);
            ok(q!=null&&q.result==Tdp.STAGED,"compliant ANIM_PLAY accepted by the real runtime");
            r.s.onReply(q);ok(r.s.note().contains("开播"),"acceptance surfaced: "+r.s.note());
            ok(g.crc==Tdp.crc(f,0,f.length),"page still alive with the same pixels");
            byte[] bad8=new byte[40];System.arraycopy(ap,0,bad8,0,32);put32(bad8,24,8);   // the legacy defect: length=8
            put32(bad8,32,0);bad8[34]=1;bad8[35]=60;put32(bad8,28,Tdp.crc(bad8,32,8));    // legacy body: asset u32 + loop + fps + 2 pad
            Tdp.Reply q2=g.handle(bad8,r.now);ok(q2!=null&&q2.result==Tdp.BAD_PACKET,"legacy 8-byte ANIM_PLAY rejected (BAD_PACKET)");
            byte[] bad0=new byte[32];System.arraycopy(ap,0,bad0,0,32);put32(bad0,24,0);put32(bad0,28,0);   // length=0, crc(∅)=0
            Tdp.Reply q3=g.handle(bad0,r.now);ok(q3!=null&&q3.result==Tdp.BAD_PACKET,"0-length ANIM_PLAY rejected (BAD_PACKET)");}
        // 18. test-entry form pin end-to-end (2026-11-19 ruling): FORM negotiated + STATUS says navigation, yet
        //     the pinned TestEntry push goes out PAGE — the frame takes the display page and the VISIBLE extra
        //     closes it (no banner auto-dismiss on the test surface).
        try(Glasses g=new Glasses("0FS1")){Rig r=new Rig();byte[] f=frame(38);
            PushPolicy.foreground(PushPolicy.Foreground.IDLE);
            r.s.push("dev",f);r.drive(g,f);r.s.disconnected();
            r.s.push("dev",f,"sticker","贴纸",PushPolicy.Form.PAGE);
            Tdp.Reply q=g.handle(r.packet,r.now);r.s.fileDone(true);r.s.onReply(q);r.now+=0.05;   // CAPS (FORM)
            Tdp.Reply st=g.handle(r.packet,r.now);r.s.fileDone(true);r.s.onReply(st);r.now+=0.05; // STATUS → navigation
            ok(PushPolicy.foreground()==PushPolicy.Foreground.NAVIGATION,"foreground=navigation");
            r.pump();
            ok(r.packet!=null&&r.packet[5]==Tdp.BEGIN&&r.packet.length==45&&r.packet[44]==Tdp.FORM_PAGE,"pinned PAGE on the wire despite navigation");
            r.drive(g,f);
            ok(r.s.note().startsWith("已上屏"),"pinned page frame closes visible: "+r.s.note());}
        System.out.println("GreetingOracleTest: "+n+" checks; happy, cached, reboot, occupier defer, F1-B disconnect-retire, F1-B silent-retire, silent-QUERY, reply-loss, mid-frame STALE, fast path, NAK rewind, idempotent repush, banner no-VISIBLE, ANIM_PLAY gate, form pin — pixel-exact vs the 611 runtime");
    }
}
