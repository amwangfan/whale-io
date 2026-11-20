package com.turboio.addon;
import java.util.*;

/** Task ⑦ GreetingSession unit coverage (JVM, fake transport): forced QUERY handshake, three-branch STALE
 *  recovery with reply-carried sid/rev, ≥100ms/≥200ms step spacing, tx highwater across restarts,
 *  one-recovery-per-frame, drop isolation, BUSY packet resend. */
public final class GreetingSessionTest {
    static int n;static void ok(boolean b,String why){n++;if(!b)throw new AssertionError("greeting "+n+" "+why);}
    static byte[] frame(){byte[] f=new byte[Tdp.PIXELS];for(int i=0;i<f.length;i++)f[i]=(byte)(i*3);return f;}
    static Tdp.Reply reply(int result,long sid,long rev,long request){Tdp.Reply r=new Tdp.Reply();r.result=result;r.sid=sid;r.revision=rev;r.request=request;r.width=512;r.height=128;r.maxRect=16384;r.lease=120000;return r;}
    static final class Rig{
        double now=1;byte[] packet;int sends;final ArrayList<double[]> delays=new ArrayList<>();final ArrayDeque<Runnable> posted=new ArrayDeque<>();
        final long[] store={0};
        final GreetingSession s=new GreetingSession(()->now,p->{packet=p;sends++;return true;},(r,d)->{delays.add(new double[]{d});posted.add(r);},new GreetingSession.TxStore(){public long load(){return store[0];}public void save(long v){store[0]=v;}});
        void runPosted(){ArrayList<Runnable> q=new ArrayList<>(posted);posted.clear();for(Runnable r:q)r.run();}
        long req(){return Tdp.u32(packet,12);}
        void settleOk(int result,long sid,long rev){long q=req();s.fileDone(true);s.onReply(reply(result,sid,rev,q));}
        void driveFrame(int chunksExpected){
            int c=0;
            while(s.busy()&&c<chunksExpected+40){
                if(!posted.isEmpty()){runPosted();continue;}
                if(packet==null)break;
                int op=packet[5];
                if(op==Tdp.BEGIN){settleOk(Tdp.STAGED,GreetingSession.SID,s.base());}
                else if(op==Tdp.CHUNK){c++;settleOk(Tdp.STAGED,GreetingSession.SID,s.base());}
                else if(op==Tdp.COMMIT){settleOk(Tdp.UI_SUBMITTED,GreetingSession.SID,s.base()+1);}
                else throw new AssertionError("unexpected op "+op);
            }
            ok(c==chunksExpected,"chunks "+c+"=="+chunksExpected);
            ok(!s.busy()&&s.note().startsWith("已提交"),"delivered: "+s.note());
        }
        /** Fast-path drive: BEGIN/CHUNKs advance on the file terminal alone; the COMMIT keeps its double ack. */
        void driveFrameFast(){
            int guard=0;
            while(s.busy()&&guard++<80){
                if(!posted.isEmpty()){runPosted();continue;}
                if(packet==null)break;
                int op=packet[5];
                if(op==Tdp.BEGIN||op==Tdp.CHUNK){s.fileDone(true);runPosted();}
                else if(op==Tdp.COMMIT){settleOk(Tdp.UI_SUBMITTED,GreetingSession.SID,s.base()+1);}
                else throw new AssertionError("unexpected op "+op);
            }
            ok(!s.busy(),"fast frame done");
        }
    }
    public static void main(String[] a){
        // 1. forced QUERY handshake before any BEGIN (the old "optional" query was the root cause)
        {Rig r=new Rig();ok(r.s.push("dev",frame()),"push accepted");ok(r.packet!=null&&r.packet[5]==Tdp.QUERY,"first packet is QUERY");
         r.settleOk(Tdp.NO_SESSION,0,0);ok(!r.delays.isEmpty()&&r.delays.get(0)[0]>=0.1,"BEGIN spaced ≥100ms after the reply");r.runPosted();
         ok(r.packet!=null&&r.packet[5]==Tdp.BEGIN&&Tdp.u32(r.packet,16)==0,"cold start BEGIN base=0");
         r.driveFrame(5);
         // handshake cached: second push starts with BEGIN directly
         ok(r.s.push("dev",frame()),"second push");ok(r.packet[5]==Tdp.BEGIN,"no re-query while the link lives");r.driveFrame(5);}
        // 2. reconnect forces a fresh QUERY
        {Rig r=new Rig();r.s.push("dev",frame());r.settleOk(Tdp.NO_SESSION,0,0);r.runPosted();r.driveFrame(5);
         ok(r.s.push("dev2",frame()),"push after peer change");ok(r.packet[5]==Tdp.QUERY,"peer change forces QUERY");}
        // 3. STALE branch sid==0: cold-start rebase
        {Rig r=new Rig();r.s.push("dev",frame());r.settleOk(Tdp.NO_SESSION,0,0);r.runPosted();
         ok(r.packet[5]==Tdp.BEGIN,"BEGIN after handshake");long q=r.req();
         r.s.fileDone(true);r.s.onReply(reply(Tdp.STALE,0,0,q));r.runPosted();
         ok(r.packet[5]==Tdp.BEGIN&&Tdp.u32(r.packet,16)==0,"STALE sid=0 rebased to base=0");r.driveFrame(5);}
        // 4. STALE branch sid==SID: rebase to the reply-carried revision
        {Rig r=new Rig();r.s.push("dev",frame());r.settleOk(Tdp.NO_SESSION,0,0);r.runPosted();
         long q=r.req();r.s.fileDone(true);r.s.onReply(reply(Tdp.STALE,GreetingSession.SID,7,q));r.runPosted();
         ok(r.packet[5]==Tdp.BEGIN&&Tdp.u32(r.packet,16)==7,"STALE sid=mine rebased to rev=7");r.driveFrame(5);}
        // 5. STALE branch sid=other, 2026-11-16 ruling: NEVER close the occupier — notify once + deferred re-push
        {Rig r=new Rig();int[] hooks={0};r.s.hooks(()->hooks[0]++);
         r.s.push("dev",frame());r.settleOk(Tdp.NO_SESSION,0,0);r.runPosted();
         long q=r.req();r.s.fileDone(true);int before=r.sends;r.s.onReply(reply(Tdp.STALE,100002,3,q));
         ok(r.s.phase()==4&&r.sends==before,"no CLOSE is ever sent");
         ok(hooks[0]==1,"notification hook fired once");
         ok(r.delays.get(r.delays.size()-1)[0]>=GreetingSession.DEFER_GAP_S,"deferred with spacing");
         r.runPosted();ok(r.packet!=null&&r.packet[5]==Tdp.QUERY,"defer wake re-queries");
         r.settleOk(Tdp.NO_SESSION,0,0);r.runPosted();
         ok(r.packet[5]==Tdp.BEGIN&&Tdp.u32(r.packet,16)==0,"frame lands after the page frees");r.driveFrame(5);}
        // 5b. page never frees: bounded defers then drop; still no CLOSE
        {Rig r=new Rig();r.s.push("dev",frame());r.settleOk(Tdp.NO_SESSION,0,0);r.runPosted();
         long q=r.req();r.s.fileDone(true);r.s.onReply(reply(Tdp.STALE,100002,3,q));
         for(int i=1;i<=GreetingSession.MAX_DEFERS;i++){r.runPosted();ok(r.packet[5]==Tdp.QUERY,"defer wake "+i+" queries");r.settleOk(Tdp.CAPS,100002,3);}
         r.runPosted();ok(!r.s.busy()&&r.s.note().contains("眼镜一直忙"),"defers exhausted → drop");
         ok(r.s.push("dev",frame()),"next push proceeds");}
        // 5c. policy table: default for unregistered content is a quiet defer; CLOSE_REOPEN stays as a registered strategy
        {ok(PushPolicy.occupied("greeting")==PushPolicy.Occupied.NOTIFY_DEFER,"greeting policy");
         ok(PushPolicy.occupied("something-else")==PushPolicy.Occupied.DEFER_QUIET,"unregistered defaults to quiet defer");
         Rig r=new Rig();int[] hooks={0};r.s.hooks(()->hooks[0]++);r.s.policyOverride=PushPolicy.Occupied.DEFER_QUIET;
         r.s.push("dev",frame());r.settleOk(Tdp.NO_SESSION,0,0);r.runPosted();
         long q=r.req();r.s.fileDone(true);r.s.onReply(reply(Tdp.STALE,100002,3,q));
         ok(hooks[0]==0&&r.s.phase()==4,"quiet defer: no notification, still deferred");
         r.runPosted();ok(r.packet[5]==Tdp.QUERY,"quiet defer also re-queries");}
        // 5d. CLOSE_REOPEN (registered-but-unassigned legacy strategy) still works when explicitly selected
        {Rig r=new Rig();r.s.policyOverride=PushPolicy.Occupied.CLOSE_REOPEN;
         r.s.push("dev",frame());r.settleOk(Tdp.NO_SESSION,0,0);r.runPosted();
         long q=r.req();r.s.fileDone(true);r.s.onReply(reply(Tdp.STALE,100002,3,q));
         r.runPosted();ok(r.packet[5]==Tdp.CLOSE&&Tdp.u32(r.packet,8)==100002,"legacy close path intact");
         r.settleOk(Tdp.CLOSED,100002,3);r.runPosted();ok(r.packet[5]==Tdp.BEGIN,"legacy reopen intact");r.driveFrame(5);}
        // 6. bounded rebase per frame (case-B): STALE recovery works up to 3× per frame; the 4th drops it
        {Rig r=new Rig();r.s.push("dev",frame());r.settleOk(Tdp.NO_SESSION,0,0);r.runPosted();
         for(int i=1;i<=3;i++){long q=r.req();r.s.fileDone(true);r.s.onReply(reply(Tdp.STALE,GreetingSession.SID,7,q));r.runPosted();
             ok(r.packet!=null&&r.packet[5]==Tdp.BEGIN,"rebase #"+i+" restarts with BEGIN");
             ok(r.s.note().contains("正在重新推送（第 "+i+" 次重定基）"),"honest retry note #"+i);}
         long q=r.req();r.s.fileDone(true);r.s.onReply(reply(Tdp.STALE,GreetingSession.SID,7,q));
         ok(!r.s.busy()&&r.s.note().contains("恢复已达上限"),"4th STALE in one frame drops it");
         ok(r.s.push("dev",frame()),"next push proceeds");ok(r.packet!=null&&r.packet[5]==Tdp.BEGIN,"no re-query needed on the same link");r.driveFrame(5);}
        // 7. tx highwater survives a process restart (same store, new session)
        {Rig r=new Rig();r.s.push("dev",frame());r.settleOk(Tdp.NO_SESSION,0,0);r.runPosted();
         long tx1=Tdp.u32(r.packet,32);r.driveFrame(5);
         GreetingSession.TxStore shared=new GreetingSession.TxStore(){public long load(){return r.store[0];}public void save(long v){r.store[0]=v;}};
         GreetingSession s2=new GreetingSession(()->r.now,p->{r.packet=p;return true;},(rn,d)->r.posted.add(rn),shared);
         ok(s2.push("dev",frame()),"restart push");s2.fileDone(true);s2.onReply(reply(Tdp.NO_SESSION,0,0,Tdp.u32(r.packet,12)));r.runPosted();
         ok(Tdp.u32(r.packet,32)>tx1,"tx never rolls back across restarts");}
        // 8. transient file-lane failure restarts the frame (bounded); exhaustion drops it
        {Rig r=new Rig();r.s.push("dev",frame());r.settleOk(Tdp.NO_SESSION,0,0);r.runPosted();
         for(int i=0;i<2;i++){r.s.fileDone(false);r.runPosted();ok(r.packet!=null&&r.packet[5]==Tdp.BEGIN,"attempt restarts with BEGIN");}
         r.s.fileDone(false);ok(!r.s.busy()&&r.s.note().contains("本帧已丢弃"),"attempts exhausted → dropped");}
        // 9. BUSY on a packet resends the SAME packet after ≥300ms
        {Rig r=new Rig();r.s.push("dev",frame());r.settleOk(Tdp.NO_SESSION,0,0);r.runPosted();r.settleOk(Tdp.STAGED,GreetingSession.SID,0);
         byte[] p=r.packet;long q=r.req();r.s.onReply(reply(Tdp.BUSY,GreetingSession.SID,0,q));
         ok(r.delays.get(r.delays.size()-1)[0]>=0.3,"BUSY resend ≥300ms");r.runPosted();
         ok(r.packet==p,"same packet resent");r.settleOk(Tdp.STAGED,GreetingSession.SID,0);r.driveFrame(4);}
        // 10. F1-B × defer: disconnect inside a defer window keeps the parked frame; a wake on a dead link
        //     re-parks (never burns retries); reconnect re-handshakes with QUERY and delivers.
        {Rig r=new Rig();boolean[] link={true};r.s.linkProbe(()->link[0]);
         r.s.push("dev",frame());r.settleOk(Tdp.NO_SESSION,0,0);r.runPosted();
         long q=r.req();r.s.fileDone(true);r.s.onReply(reply(Tdp.STALE,100002,3,q));
         ok(r.s.phase()==4,"deferred");
         link[0]=false;r.s.disconnected();
         ok(r.s.busy(),"parked frame survives the disconnect");
         r.runPosted();ok(r.s.phase()==4&&r.s.retry.attempt()==1,"wake on a dead link re-parks without burning retries");
         link[0]=true;r.runPosted();ok(r.packet!=null&&r.packet[5]==Tdp.QUERY,"reconnect re-handshakes with QUERY");
         r.settleOk(Tdp.NO_SESSION,0,0);r.runPosted();r.driveFrame(5);}
        // 11. case-A truth: QUERY never answered (no page, no runtime). The file gate terminates UNCERTAIN →
        //     cold-start assumption → BEGIN(base=0). No reply-loop, no drop, no watchdog.
        {Rig r=new Rig();r.s.push("dev",frame());ok(r.packet[5]==Tdp.QUERY,"QUERY first");
         r.s.fileDone(false);   // gate timed out: file delivered, no TDR1
         ok(r.packet!=null&&r.packet[5]==Tdp.BEGIN&&Tdp.u32(r.packet,16)==0,"gate-terminated QUERY → cold-start BEGIN base=0");
         r.driveFrame(5);}
        // 12. TDP_VISIBLE (additive, request=0): after COMMIT the note waits for visibility; VISIBLE flips it.
        {Rig r=new Rig();r.s.push("dev",frame());r.settleOk(Tdp.NO_SESSION,0,0);r.runPosted();r.driveFrame(5);
         ok(r.s.note().contains("等待上屏确认"),"two-phase terminal after COMMIT");
         Tdp.Reply vis=new Tdp.Reply();vis.result=11;vis.request=0;vis.sid=GreetingSession.SID;vis.revision=1;
         r.s.onReply(vis);ok(r.s.note().startsWith("已上屏"),"VISIBLE closes the gap");
         // a foreign-sid VISIBLE must not touch a fresh frame's state
         r.s.push("dev",frame());   // cached session: straight to BEGIN, no re-query
         Tdp.Reply foreign=new Tdp.Reply();foreign.result=11;foreign.request=0;foreign.sid=100002;foreign.revision=0;
         String before=r.s.note();r.s.onReply(foreign);ok(r.s.note().equals(before),"foreign-sid VISIBLE ignored");
         r.driveFrame(5);}
        // 13. content attribution: a sticker push carries the sticker PushPolicy key and label through deferrals.
        {Rig r=new Rig();r.s.push("dev",frame(),"sticker","贴纸");ok(r.s.contentLabel().equals("贴纸"),"label carried");
         long q=r.req();r.s.fileDone(true);r.s.onReply(reply(Tdp.STALE,100002,0,q));   // occupied by another page
         ok(r.s.note().contains("贴纸已排队稍后送达"),"defer note names the sticker");
         ok(PushPolicy.occupied("sticker")==PushPolicy.Occupied.NOTIFY_DEFER,"sticker registered NOTIFY_DEFER");}
        // 16. negotiation-gated form byte: FORM_FIELD set → STATUS probe first (answer it with the class), then the
        //     13-byte BEGIN carries the form; no negotiation → plain 12-byte BEGIN (old firmware never sees 13B).
        {Rig r=new Rig();PushPolicy.foreground(PushPolicy.Foreground.NAVIGATION);
         r.s.push("dev",frame());Tdp.Reply caps=reply(Tdp.CAPS,GreetingSession.SID,0,r.req());caps.flags=Tdp.FEATURE_FORM;
         r.s.fileDone(true);r.s.onReply(caps);r.runPosted();
         ok(r.packet!=null&&r.packet[5]==10,"STATUS probe after the form negotiation");
         Tdp.Reply st=new Tdp.Reply();st.result=Tdp.ALIVE;st.sid=GreetingSession.SID;st.request=Tdp.u32(r.packet,12);st.highwater=1;   /* byte33 = navigation */
         r.s.fileDone(true);r.s.onReply(st);r.runPosted();
         ok(PushPolicy.foreground()==PushPolicy.Foreground.NAVIGATION,"foreground=navigation learned");
         ok(r.packet!=null&&r.packet[5]==Tdp.BEGIN&&r.packet.length==45&&Tdp.u32(r.packet,24)==13&&r.packet[44]==Tdp.FORM_BANNER,"negotiated: extended BEGIN carries the banner form");
         r.driveFrame(5);
         // no negotiation → plain 12-byte BEGIN (old firmware must never see the extended one)
         Rig r2=new Rig();r2.s.push("dev",frame());Tdp.Reply c2=reply(Tdp.NO_SESSION,0,0,r2.req());
         r2.s.fileDone(true);r2.s.onReply(c2);r2.runPosted();
         ok(r2.packet!=null&&r2.packet[5]==Tdp.BEGIN&&r2.packet.length==44&&Tdp.u32(r2.packet,24)==12,"unnegotiated: plain 12-byte BEGIN");
         PushPolicy.foreground(PushPolicy.Foreground.IDLE);r2.driveFrame(5);}
        // 14. fast path (V5): CAPS with FEATURE_STREAM → chunks advance on the file terminal alone; NAK rewinds
        //     to the high water (bounded); COMMIT keeps the real double ack; terminal note carries the fast marker.
        {Rig r=new Rig();int px=Tdp.chunkPixelsFor(16384);r.s.push("dev",frame());
         Tdp.Reply caps=reply(Tdp.CAPS,GreetingSession.SID,0,r.req());caps.flags=Tdp.FEATURE_STREAM;
         r.s.fileDone(true);r.s.onReply(caps);r.runPosted();
         ok(r.packet!=null&&r.packet[5]==Tdp.BEGIN,"BEGIN after fast CAPS");
         ok(Tdp.u32(r.packet,40)>=0,"BEGIN well-formed");
         r.s.fileDone(true);r.runPosted();
         ok(r.packet!=null&&r.packet[5]==Tdp.CHUNK,"chunks start without the BEGIN reply");
         r.s.fileDone(true);r.runPosted();
         ok(r.packet!=null&&r.packet[5]==Tdp.CHUNK,"chunk 2 advances without any reply");
         // NAK: rewind to the high water and resend from there
         Tdp.Reply nak=new Tdp.Reply();nak.result=Tdp.NAK;nak.sid=GreetingSession.SID;nak.request=Tdp.u32(r.packet,12);nak.highwater=px;
         r.s.onReply(nak);
         ok(r.packet!=null&&r.packet[5]==Tdp.CHUNK&&Tdp.u32(r.packet,36)==px,"NAK rewinds to the high water");
         while(r.packet!=null&&r.packet[5]==Tdp.CHUNK){r.s.fileDone(true);r.runPosted();}
         ok(r.packet!=null&&r.packet[5]==Tdp.COMMIT,"COMMIT after the chunks");
         // COMMIT keeps the real double ack even in fast mode
         r.s.fileDone(true);r.runPosted();
         ok(r.packet!=null&&r.packet[5]==Tdp.COMMIT,"COMMIT waits for its reply in fast mode");
         long commitReq=Tdp.u32(r.packet,12);
         Tdp.Reply done=reply(Tdp.UI_SUBMITTED,GreetingSession.SID,1,commitReq);r.s.onReply(done);
         ok(r.s.note().contains("已提交")&&r.s.note().contains("快路径"),"terminal note carries the fast marker");
         }
        // 15. fast path NAK bound: the 4th NAK in one frame falls back to a bounded whole-frame retry.
        {Rig r=new Rig();r.s.push("dev",frame());
         Tdp.Reply caps=reply(Tdp.CAPS,GreetingSession.SID,0,r.req());caps.flags=Tdp.FEATURE_STREAM;
         r.s.fileDone(true);r.s.onReply(caps);r.runPosted();r.s.fileDone(true);r.runPosted();
         for(int i=0;i<3;i++){Tdp.Reply nak=new Tdp.Reply();nak.result=Tdp.NAK;nak.sid=GreetingSession.SID;nak.request=Tdp.u32(r.packet,12);nak.highwater=0;r.s.onReply(nak);}
         Tdp.Reply nak4=new Tdp.Reply();nak4.result=Tdp.NAK;nak4.sid=GreetingSession.SID;nak4.request=Tdp.u32(r.packet,12);nak4.highwater=0;
         r.s.onReply(nak4);
         r.runPosted();ok(r.packet!=null&&r.packet[5]==Tdp.BEGIN,"NAK bound falls back to a bounded frame retry");
         r.driveFrameFast();}
        // 17. banner form (negotiated FORM + navigation foreground): the firmware shows a transient banner and
        //     by design sends NO VISIBLE (native_display_page.c:447-458) — the terminal says so honestly instead
        //     of parking at 等待上屏确认 forever (the 2026-11-19 calib push symptom).
        {Rig r=new Rig();PushPolicy.foreground(PushPolicy.Foreground.NAVIGATION);
         r.s.push("dev",frame());Tdp.Reply caps=reply(Tdp.CAPS,GreetingSession.SID,0,r.req());caps.flags=Tdp.FEATURE_FORM;
         r.s.fileDone(true);r.s.onReply(caps);r.runPosted();
         ok(r.packet!=null&&r.packet[5]==10,"STATUS probe after the form negotiation");
         Tdp.Reply st=new Tdp.Reply();st.result=Tdp.ALIVE;st.sid=GreetingSession.SID;st.request=Tdp.u32(r.packet,12);st.highwater=1;   /* byte33 = navigation */
         r.s.fileDone(true);r.s.onReply(st);r.runPosted();
         ok(r.packet!=null&&r.packet[5]==Tdp.BEGIN&&r.packet.length==45&&r.packet[44]==Tdp.FORM_BANNER,"banner form on the wire");
         r.driveFrame(5);
         ok(r.s.note().contains("横幅形态")&&!r.s.note().contains("等待上屏确认"),"banner terminal is honest: "+r.s.note());
         Tdp.Reply vis=new Tdp.Reply();vis.result=11;vis.request=0;vis.sid=GreetingSession.SID;vis.revision=1;
         String bn=r.s.note();r.s.onReply(vis);ok(r.s.note().equals(bn),"no VISIBLE wait on a banner frame");
         PushPolicy.foreground(PushPolicy.Foreground.IDLE);}
        // 18. ANIM_PLAY (ANIM-PLAY-611): EXACTLY 4-byte body {asset_id u16, loop u8, fps_cap u8} on the wire;
        //     the held control settles on its own reply — STAGED = accepted, refusals named by result code
        //     (BAD_PACKET=构造错 / NO_SESSION=无页 / BUSY=在传帧 / STALE=页换代), never silently absorbed.
        {Rig r=new Rig();r.s.push("dev",frame());
         Tdp.Reply caps=reply(Tdp.CAPS,GreetingSession.SID,0,r.req());caps.flags=Tdp.FEATURE_ANIM;
         r.s.fileDone(true);r.s.onReply(caps);r.runPosted();r.driveFrame(5);
         ok(r.s.animPlay(0,true,60),"animPlay accepted locally");
         ok(r.packet!=null&&r.packet[5]==Tdp.ANIM_PLAY&&r.packet.length==36&&Tdp.u32(r.packet,24)==4&&Tdp.u16(r.packet,32)==0&&r.packet[34]==1&&r.packet[35]==60,"ANIM_PLAY wire shape {u16,u8,u8}");
         r.settleOk(Tdp.STAGED,GreetingSession.SID,r.s.base());ok(r.s.note().contains("开播"),"STAGED surfaces as accepted: "+r.s.note());
         ok(r.s.animPlay(1,false,30),"second play");r.settleOk(Tdp.BAD_PACKET,GreetingSession.SID,r.s.base());ok(r.s.note().contains("构造错"),"BAD_PACKET named: "+r.s.note());
         ok(r.s.animPlay(1,false,30),"third play");Tdp.Reply ns18=reply(Tdp.NO_SESSION,GreetingSession.SID,r.s.base(),Tdp.u32(r.packet,12));ns18.flags=Tdp.FEATURE_STREAM|Tdp.FEATURE_FORM|Tdp.FEATURE_ANIM;r.s.fileDone(true);r.s.onReply(ns18);ok(r.s.note().contains("无页"),"NO_SESSION named: "+r.s.note());   /* fixed firmware: even this refusal NO_SESSION carries 0x0d (pageless) — capabilities survive (11-20 ruling) */
         ok(r.s.animPlay(1,false,30),"fourth play");r.settleOk(Tdp.BUSY,GreetingSession.SID,r.s.base());ok(r.s.note().contains("在传帧"),"BUSY named: "+r.s.note());
         ok(r.s.animPlay(1,false,30),"fifth play");r.settleOk(Tdp.STALE,GreetingSession.SID,r.s.base());ok(r.s.note().contains("页换代"),"STALE named: "+r.s.note());}
        // 19. test-entry form pin (2026-11-19 ruling): a pinned PAGE push ignores the navigation foreground
        //     (test-surface determinism — a calibration frame never collapses into a banner); the pin only
        //     rides when FORM_FIELD was negotiated.
        {Rig r=new Rig();PushPolicy.foreground(PushPolicy.Foreground.NAVIGATION);
         r.s.push("dev",frame(),"sticker","贴纸",PushPolicy.Form.PAGE);
         Tdp.Reply caps=reply(Tdp.CAPS,GreetingSession.SID,0,r.req());caps.flags=Tdp.FEATURE_FORM;
         r.s.fileDone(true);r.s.onReply(caps);r.runPosted();
         ok(r.packet!=null&&r.packet[5]==10,"STATUS probe still runs");
         Tdp.Reply st=new Tdp.Reply();st.result=Tdp.ALIVE;st.sid=GreetingSession.SID;st.request=Tdp.u32(r.packet,12);st.highwater=1;   /* navigation */
         r.s.fileDone(true);r.s.onReply(st);r.runPosted();
         ok(PushPolicy.foreground()==PushPolicy.Foreground.NAVIGATION,"foreground=navigation learned");
         ok(r.packet!=null&&r.packet[5]==Tdp.BEGIN&&r.packet.length==45&&r.packet[44]==Tdp.FORM_PAGE,"pinned PAGE beats the navigation foreground");
         r.driveFrame(5);
         ok(r.s.note().contains("等待上屏确认"),"pinned page keeps the two-phase terminal: "+r.s.note());
         Tdp.Reply vis=new Tdp.Reply();vis.result=11;vis.request=0;vis.sid=GreetingSession.SID;vis.revision=1;
         r.s.onReply(vis);ok(r.s.note().startsWith("已上屏"),"VISIBLE closes the pinned page frame");
         PushPolicy.foreground(PushPolicy.Foreground.IDLE);}
        // 20. 2026-11-20 ruling: the pageless NO_SESSION carries the byte32 capability offer (0x0d) — a cold
        //     no-page establish negotiates without ever seeing a CAPS: FORM_FIELD → STATUS probe → 13B BEGIN;
        //     STREAM → fast chunk gates; ANIM → animPlay admitted. flags=0 NO_SESSION keeps the classic path
        //     (covered by case 16's r2). In-session replies (STAGED/NAK/ALIVE) are never re-read.
        {Rig r=new Rig();PushPolicy.foreground(PushPolicy.Foreground.NAVIGATION);
         r.s.push("dev",frame());
         Tdp.Reply ns=reply(Tdp.NO_SESSION,0,0,r.req());ns.flags=Tdp.FEATURE_STREAM|Tdp.FEATURE_FORM|Tdp.FEATURE_ANIM;
         r.s.fileDone(true);r.s.onReply(ns);r.runPosted();
         ok(r.packet!=null&&r.packet[5]==10,"STATUS probe after NO_SESSION-carried form negotiation");
         Tdp.Reply st=new Tdp.Reply();st.result=Tdp.ALIVE;st.sid=GreetingSession.SID;st.request=Tdp.u32(r.packet,12);st.highwater=1;   /* navigation */
         r.s.fileDone(true);r.s.onReply(st);r.runPosted();
         ok(r.packet!=null&&r.packet[5]==Tdp.BEGIN&&r.packet.length==45&&Tdp.u32(r.packet,24)==13&&r.packet[44]==Tdp.FORM_BANNER,"NO_SESSION-negotiated: 13B BEGIN carries the form");
         r.driveFrameFast();
         ok(r.s.animPlay(1,false,30),"animPlay admitted on NO_SESSION-carried ANIM");
         ok(r.packet!=null&&r.packet[5]==Tdp.ANIM_PLAY&&Tdp.u32(r.packet,24)==4,"ANIM_PLAY 4B wire shape");
         r.settleOk(Tdp.STAGED,GreetingSession.SID,r.s.base());ok(r.s.note().contains("开播"),"STAGED accepted: "+r.s.note());
         PushPolicy.foreground(PushPolicy.Foreground.IDLE);}
        // 21. nb1124 (2026-11-20 Tob): a cold-start animPlay never refuses outright when the gate is merely
        //     un-negotiated — it fires a one-shot QUERY probe and settles the queued play on the reply.
        //     CAPS flags=ANIM → the held ANIM_PLAY goes out (4B wire shape); NO_SESSION flags=0 → honest
        //     refusal, nothing more on the wire; busy → immediate false (unchanged); a probe whose
        //     QUERY never settles → honest refusal, no null-frame BEGIN.
        {Rig r=new Rig();
         ok(r.s.animPlay(1,false,30),"cold animPlay queues + probes");
         ok(r.packet!=null&&r.packet[5]==Tdp.QUERY,"probe QUERY on the wire");
         ok(r.s.note().contains("协商中"),"note says negotiating: "+r.s.note());
         Tdp.Reply caps=reply(Tdp.CAPS,GreetingSession.SID,0,r.req());caps.flags=Tdp.FEATURE_ANIM;
         r.s.fileDone(true);r.s.onReply(caps);
         ok(r.packet!=null&&r.packet[5]==Tdp.ANIM_PLAY&&Tdp.u32(r.packet,24)==4,"queued play fires after the probe");
         r.settleOk(Tdp.STAGED,GreetingSession.SID,r.s.base());ok(r.s.note().contains("开播"),"queued play accepted: "+r.s.note());
         Rig r2=new Rig();ok(r2.s.animPlay(1,false,30),"queued for the refusal case");
         Tdp.Reply ns=reply(Tdp.NO_SESSION,0,0,r2.req());   /* flags=0: no ANIM_LOCAL offer */
         int sendsBefore=r2.sends;r2.s.fileDone(true);r2.s.onReply(ns);
         ok(r2.sends==sendsBefore,"refusal sends nothing more");
         ok(r2.s.note().contains("未受理")&&r2.s.note().contains("ANIM_LOCAL"),"honest refusal: "+r2.s.note());
         Rig r3=new Rig();r3.s.push("dev",frame());ok(!r3.s.animPlay(1,false,30),"busy refuses immediately (unchanged)");
         Rig r4=new Rig();r4.s.animPlay(0,true,60);r4.s.fileDone(false);
         ok(r4.s.phase()==0&&r4.s.note().contains("无应答"),"probe timeout refuses honestly, session idle, no null-frame BEGIN");}
        System.out.println("GreetingSessionTest: "+n+" checks; forced QUERY, three-branch STALE, spacing, highwater, isolation, BUSY resend, F1-B defer+disconnect, silent-QUERY watchdog, TDP_VISIBLE, banner terminal, content attribution, fast path, ANIM_PLAY wire+refusals, form pin, NO_SESSION-carried capabilities, anim probe");
}
}