package com.turboio.addon;
import java.util.*;

/** ⑤ JVM-only coverage (no C oracle): chunk tiers + §7 bounded frame retry / drop isolation / breaker.
 *  A fake glasses answers with protocol-correct TDR1 replies; file terminal always follows. */
public final class TdpTierRetryTest {
    static int n;static void ok(boolean b,String why){n++;if(!b)throw new AssertionError("tier "+n+" "+why);}
    static byte[] frame(){byte[] f=new byte[Tdp.PIXELS];for(int i=0;i<f.length;i++)f[i]=(byte)i;return f;}
    static final class Rig{
        double now=1;byte[] packet;int sends;boolean refuse;final ArrayDeque<Runnable> posted=new ArrayDeque<>();
        final TdpSession s=new TdpSession("test",()->now,p->{if(refuse)return false;if(!Tdp.valid(p))throw new AssertionError("invalid packet");if(p.length>40+Tdp.chunkPixels())throw new AssertionError("over tier: "+p.length);packet=p;sends++;return true;},posted::add);
        void pump(){for(int i=0;i<8&&!posted.isEmpty();i++)posted.poll().run();}
        void respond(){byte[] p=packet;long rev=Tdp.u32(p,16);int op=p[5];Tdp.Reply r=new Tdp.Reply();
            r.request=Tdp.u32(p,12);r.sid=Tdp.u32(p,8);
            if(op==Tdp.QUERY){r.result=Tdp.CAPS;r.sid=7392;r.width=512;r.height=128;r.maxRect=16384;r.lease=120000;r.revision=1;r.flags=capsFlags;}
            else if(op==Tdp.KEEPALIVE){r.result=Tdp.ALIVE;r.revision=rev;}
            else if(op==Tdp.RECT||op==Tdp.COMMIT){r.result=Tdp.UI_SUBMITTED;r.revision=rev+1;}
            else{r.result=Tdp.STAGED;r.revision=rev;}
            ok(s.reply("test",15,Tdp.carrierEncode(r))==0,"reply accepted op="+op);s.fileDone(0);}
        int capsFlags;
        /** Fast-path drive: BEGIN/CHUNKs get only the file terminal; the COMMIT keeps its double ack. */
        void driveFast(){int k=0;while(s.busy()&&k<300){k++;int op=packet[5];
            if(op==Tdp.COMMIT)respond();else{s.fileDone(0);pump();}now+=0.05;}}
        void open(){s.setForeground(true);ok(s.query(),"query");respond();now+=0.16;s.tick();respond();ok(s.ready(),"ready");}
        int drive(){int k=0;while(s.busy()&&k<300){k++;respond();now+=0.05;pump();}return k;}
    }
    public static void main(String[] a){
        // Tier math: valid() follows the tier; packet cap = 40 + tier pixels. Default tier is 2K (H2 ruling).
        byte[] big=Tdp.chunk(7392,7,0,9,0,frame(),0,2008);
        ok(big.length==2048,"2K packet size");
        ok(Tdp.valid(big),"2K packet accepted at the default tier");
        Tdp.chunkTier(0);ok(!Tdp.valid(big),"2K packet rejected at the 512B compat tier");
        Tdp.chunkTier(4);ok(Tdp.chunkPixels()==16384,"16K tier = MAX_RECT");
        try{Tdp.chunkTier(5);ok(false,"tier range check");}catch(IllegalArgumentException expected){}
        Tdp.chunkTier(4);   // restore the 16K default for the rest
        // Default tier is 16K (payload clamped to 16344): 5 chunks.
        {Rig r=new Rig();r.open();ok(r.s.sendFrame(frame()),"frame");
         int chunks=0;while(r.s.busy()&&chunks<300){if(r.packet!=null&&r.packet[5]==Tdp.CHUNK)chunks++;r.respond();r.now+=0.05;r.pump();}
         ok(chunks==5&&r.s.completedChunks()==5,"5 chunks at default tier (16344-clamped)");ok(r.s.hasPixelBaseline(),"baseline set");}
        // 2K tier: 33 chunks, all packets within the tier.
        {Tdp.chunkTier(1);Rig r=new Rig();r.open();ok(r.s.sendFrame(frame()),"2K frame");
         int chunks=0;while(r.s.busy()&&chunks<100){if(r.packet!=null&&r.packet[5]==Tdp.CHUNK){chunks++;ok(r.packet.length<=2048,"within tier");}r.respond();r.now+=0.05;r.pump();}
         ok(chunks==33&&r.s.completedChunks()==33,"33 chunks at 2K tier");Tdp.chunkTier(4);}
        // §7: a mid-frame file failure triggers one bounded retry with a fresh tx, then completion.
        {Rig r=new Rig();r.open();ok(r.s.sendFrame(frame()),"frame for retry");r.respond();r.pump();
         r.respond();r.pump();int before=r.sends;ok(r.packet!=null,"in flight");
         r.s.fileDone(1);ok(r.s.frameActive()&&r.sends==before,"no inline resend from the ack callback");
         r.pump();ok(r.sends==before+1&&r.packet!=null&&r.packet[5]==Tdp.BEGIN,"retry restarts with BEGIN");
         r.drive();ok(!r.s.frameActive()&&r.s.hasPixelBaseline(),"frame completes after retry");}
        // Isolation: exhausting 3 attempts drops the frame; the session stays ready and the next push proceeds.
        {Rig r=new Rig();r.open();ok(r.s.sendFrame(frame()),"frame to drop");
         for(int i=0;i<3;i++){r.s.fileDone(1);r.pump();}
         ok(!r.s.frameActive()&&r.s.ready(),"frame dropped, session kept");
         ok(r.s.sendFrame(frame()),"next push proceeds");r.drive();ok(r.s.hasPixelBaseline(),"second frame delivered");}
        // Breaker: three consecutive drops refuse new frames until the cooldown elapses.
        {Rig r=new Rig();r.open();
         for(int f=0;f<3;f++){ok(r.s.sendFrame(frame()),"breaker frame "+f);for(int i=0;i<3;i++){r.s.fileDone(1);r.pump();}ok(!r.s.frameActive(),"dropped "+f);}
         ok(!r.s.sendFrame(frame()),"breaker refuses");
         r.now+=61;ok(r.s.sendFrame(frame()),"cooldown probe allowed");r.drive();ok(r.s.hasPixelBaseline(),"probe delivered");}
        // Protocol refusal stays fatal: NO_SESSION must not retry.
        {Rig r=new Rig();r.open();ok(r.s.sendFrame(frame()),"frame for NO_SESSION");
         byte[] p=r.packet;Tdp.Reply q=new Tdp.Reply();q.request=Tdp.u32(p,12);q.sid=0;q.result=Tdp.NO_SESSION;
         ok(r.s.reply("test",15,Tdp.carrierEncode(q))==1,"refusal");r.s.fileDone(0);r.pump();
         ok(!r.s.frameActive()&&!r.s.ready(),"NO_SESSION stays fail-stop");}
        // Fast path (V5): FAST2 CAPS → chunks settle on the file terminal alone; merged STAGED is accounting;
        // NAK rewinds to the high water; COMMIT keeps the real double ack.
        {Rig r=new Rig();r.capsFlags=Tdp.FEATURE_STREAM;r.open();
         ok(r.s.sendFrame(frame()),"fast frame");
         int guard=0;while(r.packet!=null&&r.packet[5]!=Tdp.COMMIT&&guard++<20){r.s.fileDone(0);r.pump();r.now+=0.05;}
         ok(r.packet!=null&&r.packet[5]==Tdp.COMMIT,"reached COMMIT without per-chunk replies");
         r.respond();ok(!r.s.busy()&&r.s.hasPixelBaseline(),"COMMIT double-acked, frame done");
         // NAK rewind on a fresh frame: file terminals only, then a NAK pulls back to the high water
         ok(r.s.sendFrame(frame()),"second fast frame");
         r.s.fileDone(0);r.pump();r.now+=0.05;   // BEGIN settles on the file terminal
         ok(r.packet!=null&&r.packet[5]==Tdp.CHUNK,"chunk 1 without a reply");
         long firstAt=Tdp.u32(r.packet,36);
         Tdp.Reply nak=new Tdp.Reply();nak.result=Tdp.NAK;nak.sid=7392;nak.request=0;nak.highwater=0;
         ok(r.s.reply("test",15,Tdp.carrierEncode(nak))==-1,"NAK is accounting, not a gate ack");
         r.pump();ok(r.packet!=null&&r.packet[5]==Tdp.CHUNK&&Tdp.u32(r.packet,36)==0,"NAK rewound to the high water");
         r.driveFast();ok(r.s.hasPixelBaseline(),"frame completes after the NAK rewind");}
        System.out.println("TdpTierRetryTest: "+n+" checks; tiers, bounded retry, isolation, breaker, fatal refusals, fast path");
    }
}
