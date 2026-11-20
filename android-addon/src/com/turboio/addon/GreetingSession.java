package com.turboio.addon;
import java.security.SecureRandom;
import java.util.Locale;

/** Greeting channel (F2) session core — pure state machine, no Android/I/O (the shell is GreetingBridge).
 *  2026-11-16 task ⑦ (GREETING-STALE-611): forced QUERY handshake on connect/reconnect, one bounded STALE
 *  recovery per frame reading the TDR1-carried sid/revision (three branches), tx highwater persisted across
 *  process restarts. Per-packet double-ack discipline unchanged; fatal refusals elsewhere stay fatal.
 *  Session steps are spaced ≥100 ms (firmware reply throttle) and a CLOSE settles ≥200 ms before the next
 *  BEGIN; spacing is scheduled through the Delayer. Chunk pumping follows the channel's established inline
 *  double-ack pattern (the shell invokes onReply from an already-posted main-thread callback). */
final class GreetingSession {
    interface Clock{double now();}
    interface Sender{boolean send(byte[] packet);}      // false = refused before submission
    interface Delayer{void post(Runnable r,double delayS);}
    interface TxStore{long load();void save(long v);}

    static final long SID=0x47525400L;                  /* "GRT\0" */
    static final double STEP_GAP_S=0.1,CLOSE_SETTLE_S=0.2,BUSY_RESEND_S=0.3;
    static final double DEFER_GAP_S=10;static final int MAX_DEFERS=5;
    private static final int IDLE=0,QUERYING=1,SENDING=2,CLOSING=3,WAITING=4,STATUSING=5;
    private static final int DO_NOTHING=0,DO_BEGIN=1,DO_CLOSE=2,DO_QUERY=3;

    private final Clock clock;private final Sender sender;private final Delayer delayer;private final TxStore store;
    private final SecureRandom random=new SecureRandom();
    final FrameRetry retry=new FrameRetry();
    private Runnable changed;
    private String peer="",note="未推送";
    private int phase=IDLE;private boolean foreground=true,needHandshake=true;
    private byte[] frame,packet;private int offset;private long tx=-1,base;private int busyRetries;
    private boolean fileAck,replyAck,visiblePending;private int staleCount;
    /** Fast path (TDP-FASTXFER-611): CAPS byte32 bit0 = streaming merged replies. On fast frames, chunks advance
     *  on the file terminal alone (the shell self-acks chunk gates — quarantine trap §1.1), merged STAGED updates
     *  the high-water mark, NAK rewinds to the high water (bounded), COMMIT keeps the real double-ack. */
    private boolean fast;private long highwater;private int nakCount;private long commitReq;
    private long capsMaxPx;   /* runtime-advertised max payload (CAPS maxRect); 0 = unknown → net cap applies */
    private boolean formField;   /* CAPS bit3: the firmware reads the BEGIN form byte (PushPolicy v2) */
    private boolean animLocal;   /* CAPS bit2: the firmware can play glasses-local animations (ANIM_LOCAL) */
    private int formSent=Tdp.FORM_PAGE;   /* the form the current frame's BEGIN carried (banner ⇒ no VISIBLE by design) */
    private PushPolicy.Form formPin;      /* test-entry determinism (2026-11-19 ruling): pinned form, null = PushPolicy auto */
    static final int MAX_NAK_PER_FRAME=3;
    private double lastActivity;
    /** Bounded STALE rebases per frame (case-B evidence 2026-11-17: a frame can legitimately meet more than one
     *  when the firmware revision moves mid-frame). Each rebase restarts the whole frame — staged chunks are gone
     *  by protocol, so resume-from-N is impossible and never attempted. */
    static final int MAX_STALE_PER_FRAME=3;
    private int pendingAction=DO_NOTHING;private long pendingSid,pendingRev;
    private Tdp.Reply queryReply;
    /** 2026-11-16 user ruling: when the page is occupied, never close the user's page — defer per PushPolicy. */
    interface Hooks{void occupiedDefer();}
    /** Optional link probe (wired by the shell to NativeTransfer.connected); a wake on a dead link re-parks. */
    interface LinkProbe{boolean connected();}
    private Hooks hooks;private int deferCount;PushPolicy.Occupied policyOverride;   // package-visible for tests
    private LinkProbe linkProbe;
    private int chunks;private double framePushedAt;private int retriesAtStart;
    /** The shell fills this with the transport's refusal reason right before reporting a refused send. */
    String lastSendRefusal;
    void hooks(Hooks h){hooks=h;}
    void linkProbe(LinkProbe p){linkProbe=p;}
    private PushPolicy.Occupied occupiedPolicy(){return policyOverride!=null?policyOverride:PushPolicy.occupied(content);}
    /** Content attribution (2026-11-17 sticker ruling): greeting and sticker share this channel/session; the
     *  PushPolicy key and the user-facing label differ so deferral/notes/logs never mix the two up. */
    private String content="greeting",label="问候";

    GreetingSession(Clock clock,Sender sender,Delayer delayer,TxStore store){
        if(clock==null||sender==null||delayer==null||store==null)throw new IllegalArgumentException();
        this.clock=clock;this.sender=sender;this.delayer=delayer;this.store=store;
    }
    private double now(){return clock.now();}
    void listen(Runnable r){changed=r;}
    String note(){return note;}
    boolean busy(){return phase!=IDLE;}
    private void publish(String s){note=s;if(changed!=null)changed.run();}

    /** Push one 512×128 frame to the greeting page. Handshake first when the link is (re)established. */
    boolean push(String device,byte[] gray){return push(device,gray,"greeting","问候");}
    boolean push(String device,byte[] gray,String content,String label){return push(device,gray,content,label,null);}
    /** formPin (2026-11-19 ruling): TestEntry pushes pin the form for test-surface determinism (a calibration
     *  frame must never collapse into a 6 s banner); production pushes pass null and keep PushPolicy v2. The pin
     *  rides only when FORM_FIELD was negotiated — old firmware always gets the plain 12-byte page BEGIN. */
    boolean push(String device,byte[] gray,String content,String label,PushPolicy.Form formPin){
        if(device==null||device.isEmpty()){publish("眼镜未连接");return false;}
        if(gray==null||gray.length!=Tdp.PIXELS){publish("画面尺寸不对");return false;}
        if(phase!=IDLE){publish("上一帧还在推，等它结束");return false;}
        if(!device.equals(peer)){peer=device;needHandshake=true;retry.reconnect();}
        if(!retry.begin(now())){publish("连续丢帧已达上限，判定链路异常；重连眼镜或稍后再推");return false;}
        this.content=content;this.label=label;this.formPin=formPin;
        frame=gray.clone();staleCount=0;deferCount=0;framePushedAt=now();chunks=0;retriesAtStart=retry.totalRetried();visiblePending=false;
        fast=false;highwater=0;nakCount=0;commitReq=nextReq();
        if(needHandshake){publish("建立"+label+"会话：先查询…");sendQuery();}
        else beginFrame();
        return true;
    }
    /** Peer lost: the next push re-handshakes, always. An in-flight frame is dropped (unknown result), but a
     *  PARKED (deferred) frame survives: F1-B retires the glasses page after ~3 s of disconnect, so on reconnect
     *  the wake must re-handshake (QUERY) rather than resume blindly. */
    void disconnected(){
        needHandshake=true;
        long aa=animAsset;animAsset=-1;
        if(phase==WAITING){pendingAction=DO_QUERY;pendingSid=0;pendingRev=0;publish("连接已失效；问候仍在排队，重连后重新握手送达");}
        else if(phase!=IDLE){phase=IDLE;frame=null;packet=null;pendingAction=DO_NOTHING;publish("连接已失效；重新推送前会先重新查询");}
        if(aa>=0)publish("动画点播未受理：连接失效，协商中断");
    }

    private long nextReq(){return (random.nextInt()&0x3fffffffL)+1;}
    private long nextTx(){long t=Math.max(tx,store.load())+1;tx=t;store.save(t);return t;}
    private void beginFrame(){phase=SENDING;offset=0;busyRetries=0;
        /* PushPolicy v2: the BEGIN form byte rides only after the FORM_FIELD capability negotiated — old firmware
         *  rejects a 13-byte BEGIN outright, so the plain 12-byte BEGIN goes out otherwise (byte-identical). */
        int form=formField?PushPolicy.formWire(formPin!=null?formPin:PushPolicy.decide()):Tdp.FORM_PAGE;formSent=form;
        sendStep(formField?Tdp.beginForm(SID,nextReq(),base,nextTx(),Tdp.crc(frame,0,frame.length),form)
                          :Tdp.begin(SID,nextReq(),base,nextTx(),Tdp.crc(frame,0,frame.length)));}
    private void sendQuery(){phase=QUERYING;queryReply=null;sendStep(Tdp.query(nextReq()));}
    /* No QUERY watchdog: the file gate's own terminal drives the cold-start assumption (fileDone(false) during
     * QUERYING → BEGIN base=0). A 2 s watchdog would BEGIN into the QUERY's still-open gate — the case-A
     * collision found on the live device 2026-11-17. */
    private void closeOccupier(long sid,long rev){phase=CLOSING;sendStep(Tdp.control(Tdp.CLOSE,sid,nextReq(),rev));}
    /** Schedule a session step after the protocol spacing — never inline from an acknowledgement callback. */
    private void schedule(int action,double delayS,long sid,long rev){
        phase=WAITING;pendingAction=action;pendingSid=sid;pendingRev=rev;
        delayer.post(()->{if(phase!=WAITING||pendingAction==DO_NOTHING)return;
            int a=pendingAction;pendingAction=DO_NOTHING;
            // F1-B interaction: a wake on a still-dead link re-parks (bounded by the defer budget), never burns retries.
            if(linkProbe!=null&&!linkProbe.connected()){if(deferCount>=MAX_DEFERS){dropFrame("重连超时，问候未送达");return;}deferCount++;schedule(DO_QUERY,DEFER_GAP_S,0,0);return;}
            if(a==DO_BEGIN){if(frame!=null)beginFrame();}else if(a==DO_CLOSE)closeOccupier(pendingSid,pendingRev);else if(a==DO_QUERY){if(frame!=null)sendQuery();}},delayS);
    }
    /** Occupied page, per the content's PushPolicy: CLOSE_REOPEN (legacy) or defer-with-notify / quietly. */
    private void occupied(long sid,long rev){
        if(occupiedPolicy()==PushPolicy.Occupied.CLOSE_REOPEN){publish("显示页被占用，按协议关闭后重开…");schedule(DO_CLOSE,STEP_GAP_S,sid,rev);return;}
        defer();
    }
    private void defer(){
        if(deferCount>=MAX_DEFERS){dropFrame("眼镜一直忙（显示页被占用），请稍后重推");return;}
        deferCount++;
        if(deferCount==1&&occupiedPolicy()==PushPolicy.Occupied.NOTIFY_DEFER&&hooks!=null)hooks.occupiedDefer();
        publish("眼镜在忙，"+label+"已排队稍后送达（"+deferCount+"/"+MAX_DEFERS+"）…");
        schedule(DO_QUERY,DEFER_GAP_S,0,0);
    }
    private void sendStep(byte[] p){
        if(p==null){dropFrame("包构造失败");return;}
        packet=p;fileAck=false;replyAck=false;lastActivity=now();
        boolean ok;try{ok=sender.send(p);}catch(RuntimeException e){ok=false;}
        if(!ok){packet=null;transientFailure("包未发出");}
    }
    /** One packet settles on BOTH the file terminal and the matching TDR1 reply — except in fast mode, where
     *  BEGIN/CHUNK advance on the file terminal alone (merged replies are accounting, not per-chunk gates) and
     *  only the COMMIT keeps the real double ack (its UI_SUBMITTED is the frame verdict). */
    private void advance(){
        if(packet==null||!fileAck)return;
        int op=packet[5];
        boolean needReply = !fast || phase!=SENDING || op==Tdp.COMMIT;
        if(needReply&&!replyAck)return;
        packet=null;
        switch(phase){
            case QUERYING: Tdp.Reply r=queryReply;queryReply=null;
                if(r==null){disconnected();return;}
                if(r.result==Tdp.NO_SESSION){   /* byte32 capabilities already refreshed in onReply (11-20 ruling) */
                    needHandshake=false;base=0;
                    if(frame==null){phase=IDLE;settleQueuedAnim();return;}   /* nb1124: negotiation-only probe (anim gate) */
                    if(formField){phase=STATUSING;sendStep(Tdp.status(SID,nextReq(),base));return;}   /* learn the foreground class before the form byte — same as the CAPS path */
                    schedule(DO_BEGIN,STEP_GAP_S,0,0);return;}
                if(r.result==Tdp.CAPS){
                    needHandshake=false;fast=(r.flags&Tdp.FEATURE_STREAM)!=0;capsMaxPx=r.maxRect;formField=(r.flags&Tdp.FEATURE_FORM)!=0;animLocal=(r.flags&Tdp.FEATURE_ANIM)!=0;
                    android.util.Log.i("TurboIOTransfer","greeting caps flags="+r.flags+" maxRect="+capsMaxPx+" fast="+fast+" form="+formField+" anim="+animLocal);
                    if(r.sid==SID){base=r.revision;
                        if(frame==null){phase=IDLE;settleQueuedAnim();return;}   /* nb1124 probe settle */
                        if(formField){phase=STATUSING;sendStep(Tdp.status(SID,nextReq(),base));return;}   /* learn the foreground class before choosing the form */
                        schedule(DO_BEGIN,STEP_GAP_S,0,0);return;}
                    if(frame==null){phase=IDLE;settleQueuedAnim();return;}   /* nb1124 probe: page occupied — capabilities known, skip the defer loop */
                    occupied(r.sid,r.revision);return;
                }
                dropFrame("查询回执异常");return;
            case CLOSING: publish("占用页已关，问候页重开…");schedule(DO_BEGIN,CLOSE_SETTLE_S,0,0);return;
            case SENDING: if(offset<Tdp.PIXELS){
                    int len=(int)Math.min(Tdp.chunkPixelsFor(capsMaxPx),Tdp.PIXELS-offset);
                    sendStep(Tdp.chunk(SID,nextReq(),base,tx,offset,frame,offset,len));offset+=len;chunks++;
                }else sendStep(Tdp.finish(Tdp.COMMIT,SID,commitReq,base,tx));
                return;
            default: return;
        }
    }

    /** §7 + ⑦: one bounded STALE recovery per frame, driven by the reply-carried sid/revision — never blind. */
    private void staleRecovery(Tdp.Reply r){
        if(staleCount>=MAX_STALE_PER_FRAME){dropFrame("会话失步，恢复已达上限仍 STALE");return;}
        staleCount++;packet=null;
        String again="正在重新推送（第 "+staleCount+" 次重定基）…";
        if(r.sid==0){base=0;publish("眼镜冷启动，重定基重发；"+again);schedule(DO_BEGIN,STEP_GAP_S,0,0);return;}
        if(r.sid==SID){base=r.revision;publish("会话失步，按回执重定基（rev="+r.revision+"）；"+again);schedule(DO_BEGIN,STEP_GAP_S,0,0);return;}
        occupied(r.sid,r.revision);
    }
    private void transientFailure(String cause){
        if(frame==null){phase=IDLE;publish(cause);return;}
        if(!foreground||!retry.retry()){dropFrame(cause);return;}
        if(lastSendRefusal!=null){cause=withRefusal(cause);}publish("第"+retry.attempt()+"次自主重试（"+cause+"）…");schedule(needHandshake?DO_QUERY:DO_BEGIN,STEP_GAP_S,0,0);
    }
    private String withRefusal(String c){String r=c+"（通道："+lastSendRefusal+"）";lastSendRefusal=null;return r;}
    private void dropFrame(String cause){
        phase=IDLE;frame=null;packet=null;pendingAction=DO_NOTHING;retry.drop(now());
        if(lastSendRefusal!=null)cause=withRefusal(cause);
        publish("本帧已丢弃（"+cause+"，不影响下一次推送）。累计丢帧 "+retry.totalDropped()+(retry.open()?"；连续丢帧过多，暂停推送直至重连":""));
    }

    /** File-lane terminal for the in-flight packet (true = CONFIRMED/REJECTED, false = failed/unknown). */
    void fileDone(boolean ok){
        if(packet==null)return;
        if(!ok){
            /* A QUERY that never settles (cold glasses have no runtime to answer it) is not a failure:
             * assume cold start and go straight to BEGIN — the STALE branch catches a wrong base. */
            if(phase==QUERYING){needHandshake=false;base=0;packet=null;
                if(frame==null){phase=IDLE;long a=animAsset;animAsset=-1;if(a>=0)publish("动画点播未受理：能力查询无应答");return;}   /* nb1124 probe timeout: never BEGIN a null frame */
                beginFrame();return;}
            if(phase==STATUSING){packet=null;schedule(DO_BEGIN,STEP_GAP_S,0,0);return;}   /* STATUS probe lost: default foreground, carry on */
            transientFailure("文件通道失败");return;
        }
        fileAck=true;advance();
    }
    /** A TDR1 reply for the greeting channel (already decoded; matched to the in-flight request here).
     *  TDP_VISIBLE is an unsolicited event (request=0, sid=current) sent right after UI_SUBMITTED when the frame
     *  actually took the screen — it closes the "submitted ≠ visible" gap. Older firmware never sends it; the
     *  terminal note then honestly stays at 已提交. */
    void onReply(Tdp.Reply r){
        if(r==null)return;
        /* 2026-11-20 ruling (Tob): the firmware advertises its byte32 capability bits on the pageless
         *  handshake carriers — CAPS and NO_SESSION alike (0x0d on the fixed batch; native_display_page.c
         *  pageless_emit/pageless_offer). A cold no-page establish (QUERY→NO_SESSION→BEGIN) never sees a
         *  CAPS, so CAPS-only reading never negotiated. In-session replies (STAGED/NAK/ALIVE/VISIBLE…)
         *  carry byte32=0 and are deliberately NOT read here — reading them would disengage a negotiated
         *  fast path mid-frame. Session semantics unchanged; maxRect stays CAPS-read. */
        if(r.result==Tdp.CAPS||r.result==Tdp.NO_SESSION){fast=(r.flags&Tdp.FEATURE_STREAM)!=0;formField=(r.flags&Tdp.FEATURE_FORM)!=0;animLocal=(r.flags&Tdp.FEATURE_ANIM)!=0;}
        if(r.request==0){
            if(r.result==Tdp.VISIBLE&&r.sid==SID&&visiblePending){visiblePending=false;int total=(int)Math.ceil(Tdp.PIXELS/(double)Tdp.chunkPixelsFor(capsMaxPx));publish("已上屏 ✓（"+total+" 块 · "+String.format(Locale.ROOT,"%.1f",Math.max(0,now()-framePushedAt))+" 秒）");}
            return;
        }
        if(packet==null)return;
        /* Fast mode: merged replies lag the sends, so match by session (sid) not request id; highwater is the
         *  accounting. Classic mode keeps request-id matching. */
        boolean ours = (fast&&phase==SENDING) ? r.sid==SID : r.request==Tdp.u32(packet,12);
        if(!ours)return;
        if(phase==WAITING&&(packet[5]==Tdp.ANIM_PLAY||packet[5]==Tdp.CLOSE)){
            /* Held control packet (animPlay / the 30 s test-cadence close): settle on its own reply instead of
             *  the 1 s park timer, and name a refusal by its result code (ANIM-PLAY-611 ruling) — the 2026-11-19
             *  defect surfaced as a silent BAD_PACKET while the phone reported only "sent". */
            int op=packet[5];packet=null;phase=IDLE;
            if(op==Tdp.ANIM_PLAY){if(r.result==Tdp.STAGED)publish("动画点播已受理：眼镜本地开播");else publish("动画点播未受理："+Tdp.resultText(r.result));}
            return;
        }
        switch(r.result){
            case Tdp.CAPS: case Tdp.NO_SESSION:
                if(phase==QUERYING){queryReply=r;replyAck=true;advance();}
                else if(phase==SENDING&&r.result==Tdp.NO_SESSION)dropFrame("眼镜"+label+"页未就绪（固件需 TVP2C-611）");
                return;
            case Tdp.CLOSED:
                if(phase==CLOSING){replyAck=true;advance();}return;
            case Tdp.STAGED:
                if(fast&&phase==SENDING){highwater=Math.max(highwater,r.highwater);return;}   /* merged accounting only */
                if(phase==SENDING){replyAck=true;advance();}return;
            case Tdp.ALIVE:
                /* STATUSING: the foreground-category answer (byte33 = the class, per-op semantics). */
                if(phase==STATUSING){PushPolicy.foreground(PushPolicy.foregroundOf(r.highwater&0xff));packet=null;schedule(DO_BEGIN,STEP_GAP_S,0,0);return;}
                return;
            case Tdp.NAK:
                /* Bounded high-water retransmit (TDP-FASTXFER-611): rewind to the firmware's frame_received and
                 * continue; KEEPALIVE first if the page lease has been idle ~60 s (120 s lease guard). */
                if(phase==SENDING&&fast){
                    if(nakCount>=MAX_NAK_PER_FRAME){transientFailure("NAK 补缺超限");return;}
                    nakCount++;offset=(int)Math.min(r.highwater,Tdp.PIXELS);packet=null;
                    publish("缺块补传中（回退至高水位，第 "+nakCount+" 次）…");
                    if(now()-lastActivity>60){lastActivity=now();sendStep(Tdp.control(Tdp.KEEPALIVE,SID,nextReq(),base));return;}
                    int len=(int)Math.min(Tdp.chunkPixelsFor(capsMaxPx),Tdp.PIXELS-offset);
                    sendStep(Tdp.chunk(SID,nextReq(),base,tx,offset,frame,offset,len));offset+=len;
                }return;
            case Tdp.UI_SUBMITTED:
                if(phase==SENDING){phase=IDLE;frame=null;packet=null;base=r.revision;retry.success();
                    int total=(int)Math.ceil(Tdp.PIXELS/(double)Tdp.chunkPixelsFor(capsMaxPx));
                    if(formSent==Tdp.FORM_BANNER){visiblePending=false;
                        /* Banner form (firmware native_display_page.c:447-458): a transient top strip — no screen
                         *  hold, no VISIBLE by design. Waiting for it would park the note at 已提交 forever
                         *  (the 2026-11-19 calib symptom); say what actually happens instead. */
                        publish(String.format(Locale.ROOT,"已提交到眼镜（%d 块 · %.1f 秒 · 自主重试 %d 次%s）；横幅形态：顶部短显约 6 秒自动消失（横幅按约无上屏回执）",total,Math.max(0,now()-framePushedAt),retry.totalRetried()-retriesAtStart,fast?" · 快路径":""));}
                    else{visiblePending=true;
                        publish(String.format(Locale.ROOT,"已提交到眼镜（%d 块 · %.1f 秒 · 自主重试 %d 次%s）；等待上屏确认（老固件无此信号则停在这里）",total,Math.max(0,now()-framePushedAt),retry.totalRetried()-retriesAtStart,fast?" · 快路径":""));}}return;
            case Tdp.BAD_PACKET:
                if(phase==STATUSING){packet=null;schedule(DO_BEGIN,STEP_GAP_S,0,0);return;}   /* old firmware: no STATUS; default foreground */
                /* The firmware guard rejected the packet outright — a construction error (BAD_PACKET), e.g. a
                 * payload over the wire cap. Never retry the same bytes — drop the frame; big-tier scans drop a tier. */
                if(phase==SENDING)dropFrame("眼镜拒收："+Tdp.resultText(r.result)+"；若在用大档位请在显示测试页切回低档位");
                return;
            case Tdp.STALE: staleRecovery(r);return;
            case Tdp.BUSY:
                final byte[] p=packet;
                if(p!=null&&busyRetries<8){busyRetries++;publish("眼镜渲染忙，稍候重试…");delayer.post(()->{if(packet==p&&phase!=IDLE)sendStep(p);},BUSY_RESEND_S);}
                else transientFailure("眼镜一直忙");
                return;
            default: return;   /* RENDER_ERROR/BAD_PACKET/…: not ours to recover here */
        }
    }
    void setForeground(boolean active){foreground=active;}
    /** Exposed for tests/diagnostics. */
    int phase(){return phase;}long base(){return base;}long tx(){return tx;}
    String contentLabel(){return label;}
    /** Fast mode engaged (FAST2 negotiated via CAPS): the shell self-acks BEGIN/CHUNK gates (merged replies are
     *  accounting, not gate acks — without this every bulk gate would settle UNCERTAIN and quarantine the lane). */
    boolean fastMode(){return fast;}
    /** Play a glasses-local animation (ANIM_LOCAL negotiated, page open, session idle). The firmware renders it
     *  locally; zero per-frame uplink. Returns false when the capability was not negotiated or a frame is in flight. */
    /** Play a glasses-local animation (ANIM_LOCAL negotiated, page open, session idle). The firmware renders it
     *  locally; zero per-frame uplink. Returns false when busy or peerless. 2026-11-20 nb1124 (Tob): a cold
     *  start / reconnect loses the negotiated bits — instead of refusing outright, fire a one-shot QUERY probe
     *  and settle the queued play when the reply lands (byte32 rides CAPS and NO_SESSION alike). */
    boolean animPlay(long assetId,boolean loop,int fpsCap){
        if(phase!=IDLE)return false;
        if(!animLocal){animAsset=assetId;animLoop=loop;animCap=fpsCap;publish("动画点播协商中（先查询眼镜能力）…");sendQuery();return true;}
        publish("点播眼镜本地动画…");
        boolean ok=sendStepAndHold(Tdp.animPlay(SID,nextReq(),base,assetId,loop,fpsCap));
        /* Test cadence (Tob's spec): auto-stop the animation 30 s in — close the page; the CLOSED reply is absorbed. */
        if(ok)delayer.post(()->{if(phase==IDLE)sendStepAndHold(Tdp.control(Tdp.CLOSE,SID,nextReq(),base));},30.0);
        return ok;
    }
    /* nb1124 queued-play state (animAsset<0 = none). */
    private long animAsset=-1;private boolean animLoop;private int animCap;
    private void settleQueuedAnim(){
        long a=animAsset;animAsset=-1;if(a<0)return;
        if(animLocal)animPlay(a,animLoop,animCap);   /* gate open now; the wire answers honestly (开播/无页/在传帧) */
        else publish("动画点播未受理：眼镜端未广告本地动画能力（ANIM_LOCAL）");
    }
    private boolean sendStepAndHold(byte[] p){   /* control-class: settle on its own reply, then park */
        if(p==null)return false;packet=p;fileAck=false;replyAck=false;lastActivity=now();phase=WAITING;
        boolean ok;try{ok=sender.send(p);}catch(RuntimeException e){ok=false;}
        if(!ok){packet=null;phase=IDLE;return false;}
        delayer.post(()->{if(packet==p){packet=null;phase=IDLE;}},1.0);return true;
    }
    /** The in-flight packet's request id, 0 when idle — the shell arms the TransferGate ack only for this. */
    long inFlightRequest(){return packet==null?0:Tdp.u32(packet,12);}
}
