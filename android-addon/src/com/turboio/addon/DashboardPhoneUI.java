package com.turboio.addon;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.*;
import java.util.*;
import static com.turboio.addon.BoundedJson.*;

/**
 * Phone UI for dashboard-service: configure HTTPS origin + phone Token, refresh jobs,
 * optional preview, and auto-approve (默认开)：awaiting → approve → CardTransport.publish(document,false) → receipt.
 * No flash; no silent firmware flash; no auto-retry of same job; no fake device_accepted.
 * Adaptive foreground poll 30s / ~7s. Server still uses approve API; client skips manual confirm when auto on.
 */
final class DashboardPhoneUI {
    private DashboardPhoneUI(){}
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    /** Session-scoped: same job id never auto-approved twice; one in-flight auto send. */
    private static final Set<String> AUTO_ATTEMPTED=Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Object AUTO_LOCK=new Object();
    private static boolean autoInFlight=false;
    private static final Map<String,String> STATE_LABELS;
    static{
        Map<String,String> m=new LinkedHashMap<>();
        m.put("awaiting_phone_approval","待手机批准");
        m.put("sending","发送中");
        m.put("outcome_unknown","结果未知 · 请核对眼镜");
        m.put("expired","已过期");
        m.put("failed","未完成");
        m.put("device_accepted","眼镜配置已回读");
        STATE_LABELS=Collections.unmodifiableMap(m);
    }

    @SuppressWarnings("unchecked")
    static void show(Activity a){
        EditorialUI.Screen s=new EditorialUI.Screen(a,"云端数据源 / 任务");
        LinearLayout b=s.body;
        b.addView(EditorialUI.text(a,"连接你自己的 HTTPS dashboard-service。只读 phone 角色任务；Token 进 SecretStore，不写 APK。不会刷机。自动上镜需眼镜已有 TCE1/TVP2，本页不刷机。公网 HTTPS 就绪后再联调。",13,EditorialUI.MUTED));
        TextView message=EditorialUI.text(a,DashboardPhoneStore.configured(a)?"已配置。点刷新任务。":"尚未配置 HTTPS 地址与 phone Token。",14,EditorialUI.INK);
        b.addView(message);
        final Map<String,String> autoNotes=new LinkedHashMap<>();
        LinearLayout jobsBox=EditorialUI.column(a);b.addView(jobsBox);
        final boolean[] busy={false};
        final Object[] jobsHolder={Collections.emptyList()};
        final Runnable[] poll={null};
        final boolean[] pollOn={true};

        Runnable render=()->{
            jobsBox.removeAllViews();
            Map<String,String> cfg=DashboardPhoneStore.settings(a);
            if(cfg.isEmpty()){jobsBox.addView(EditorialUI.text(a,"先配置连接。",14,EditorialUI.MUTED));return;}
            if(((List<Object>)jobsHolder[0]).isEmpty()){jobsBox.addView(EditorialUI.text(a,"暂无任务。后端发布后在此自动上镜或预览确认。",14,EditorialUI.MUTED));return;}
            String device=cfg.get("backendDevice");
            boolean auto=DashboardPhoneStore.autoApprove(a);
            for(Object o:((List<Object>)jobsHolder[0])){
                if(!(o instanceof Map))continue;
                Map<String,Object> j=map(o);
                String st=String.valueOf(j.get("state"));
                String id=String.valueOf(j.get("id"));
                String title=String.valueOf(j.getOrDefault("card",j.get("id")));
                String detail=STATE_LABELS.containsKey(st)?STATE_LABELS.get(st):"未知状态";
                if(autoNotes.containsKey(id))detail=detail+" · "+autoNotes.get(id);
                else if(auto&&"awaiting_phone_approval".equals(st)&&AUTO_ATTEMPTED.contains(id))
                    detail=detail+" · 本会话已尝试自动发送";
                LinearLayout card=EditorialUI.card(a,jobsBox);
                card.addView(EditorialUI.text(a,title,16,EditorialUI.INK));
                card.addView(EditorialUI.text(a,detail,13,EditorialUI.MUTED));
                if("awaiting_phone_approval".equals(st)){
                    if(auto)EditorialUI.button(a,card,"查看",false,()->openJob(a,id,message,true));
                    else EditorialUI.button(a,card,"预览并批准",false,()->openJob(a,id,message,false));
                }
            }
            String block=DashboardPhone.blockReason(((List<Object>)jobsHolder[0]),device);
            if(block!=null)jobsBox.addView(EditorialUI.text(a,block,13,EditorialUI.MUTED));
        };

        final Runnable[] refresh={null};
        final Runnable[] maybeAuto={null};
        refresh[0]=()->{
            if(busy[0])return;
            Map<String,String> cfg=DashboardPhoneStore.settings(a);
            String token=DashboardPhoneStore.token(a);
            if(cfg.isEmpty()||!DashboardPhone.tokenOk(token)){message.setText("请先配置 HTTPS 与 phone Token。");return;}
            busy[0]=true;message.setText("正在读取任务…");
            String origin=cfg.get("origin"),device=cfg.get("backendDevice");
            new Thread(()->{
                try{
                    Object v=DashboardPhone.request(origin,token,"/v1/jobs","GET",null);
                    if(!(v instanceof List)||((List<?>)v).size()>100)throw new IllegalArgumentException("任务列表格式错误");
                    List<Object> rows=new ArrayList<>();
                    for(Object o:(List<?>)v){
                        if(!(o instanceof Map))throw new IllegalArgumentException("任务字段不符");
                        Map<String,Object> j=map(o);
                        if(!device.equals(j.get("device"))||!(j.get("id") instanceof String)||!(j.get("card") instanceof String)||!(j.get("state") instanceof String))
                            throw new IllegalArgumentException("任务设备或字段不符，未加载。");
                        rows.add(j);
                    }
                    List<Object> copy=rows;
                    MAIN.post(()->{
                        busy[0]=false;
                        if(!s.dialog.isShowing())return;
                        jobsHolder[0]=copy;
                        message.setText(DashboardPhoneStore.autoApprove(a)
                            ?"任务已读取。自动上镜开启：待批准快照将静默发送；超时或结果未知不会自动重试。"
                            :"任务已读取。只有待批准快照可发送；超时或结果未知不会自动重试。");
                        render.run();
                        if(pollOn[0])schedulePoll(s,poll,jobsHolder,device,refresh[0]);
                        maybeAuto[0].run();
                    });
                }catch(Exception e){
                    String err=e.getMessage()==null?"网络请求未完成；没有自动重试。":e.getMessage();
                    MAIN.post(()->{
                        busy[0]=false;
                        if(s.dialog.isShowing()){
                            message.setText(err);
                            if(pollOn[0])schedulePoll(s,poll,jobsHolder,device,refresh[0]);
                        }
                    });
                }
            },"TurboIO-dashboard-jobs").start();
        };

        maybeAuto[0]=()->maybeAutoApprove(a,s,message,jobsHolder,autoNotes,render);

        EditorialUI.button(a,b,"配置 HTTPS 地址与 Token",true,()->configure(a,message,()->{jobsHolder[0]=Collections.emptyList();render.run();}));
        EditorialUI.button(a,b,"断开后端配置",false,()->EditorialUI.confirm(a,"断开后端？","仅清除本机地址和 Token，不删除服务器记录或眼镜卡片。",()->{
            DashboardPhoneStore.disconnect(a);jobsHolder[0]=Collections.emptyList();message.setText("已移除本机后端配置。");render.run();
        }));
        EditorialUI.button(a,b,"刷新任务",false,()->refresh[0].run());
        EditorialUI.toggle(a,b,"自动上镜（跳过每次批准）",DashboardPhoneStore.autoApprove(a),(v,on)->{
            DashboardPhoneStore.setAutoApprove(a,on);
            message.setText(on?"已开启自动上镜：待批准将静默发送（仍走 approve API）。":"已关闭自动上镜：需点「预览并批准」。");
            render.run();
            if(on)maybeAuto[0].run();
        });
        EditorialUI.toggle(a,b,"前台短轮询（空闲约30s；有待批准约7s）",true,(v,on)->{
            pollOn[0]=on;
            if(!on&&poll[0]!=null){MAIN.removeCallbacks(poll[0]);poll[0]=null;}
            else if(on)schedulePoll(s,poll,jobsHolder,DashboardPhoneStore.settings(a).get("backendDevice"),refresh[0]);
        });
        render.run();
        s.dialog.setOnDismissListener(d->{if(poll[0]!=null)MAIN.removeCallbacks(poll[0]);});
        if(DashboardPhoneStore.configured(a))refresh[0].run();
    }

    @SuppressWarnings("unchecked")
    private static void maybeAutoApprove(Activity a,EditorialUI.Screen listScreen,TextView message,
            Object[] jobsHolder,Map<String,String> autoNotes,Runnable render){
        if(!listScreen.dialog.isShowing())return;
        if(!DashboardPhoneStore.autoApprove(a)||!DashboardPhoneStore.configured(a))return;
        Map<String,String> settings=new LinkedHashMap<>(DashboardPhoneStore.settings(a));
        String token=DashboardPhoneStore.token(a);
        if(settings.isEmpty()||!DashboardPhone.tokenOk(token))return;
        String peer=settings.get("peer");
        if(peer==null||!peer.equals(NativeTransfer.connected())||CardTransport.busy())return;
        List<?> jobs=(List<?>)jobsHolder[0];
        String device=settings.get("backendDevice");
        if(DashboardPhone.blockReason(jobs,device)!=null)return;
        String jobId;
        synchronized(AUTO_LOCK){
            if(autoInFlight)return;
            jobId=DashboardPhone.pickAutoJobId(jobs,device,AUTO_ATTEMPTED);
            if(jobId==null)return;
            AUTO_ATTEMPTED.add(jobId);
            autoInFlight=true;
        }
        message.setText("自动上镜：正在读取任务详情…");
        autoNotes.put(jobId,"自动发送中…");
        render.run();
        final String id=jobId;
        new Thread(()->{
            try{
                Object job=DashboardPhone.request(settings.get("origin"),token,"/v1/jobs/"+id,"GET",null);
                MAIN.post(()->{
                    if(!listScreen.dialog.isShowing()){releaseAuto();return;}
                    if(!settings.equals(DashboardPhoneStore.settings(a))
                        ||!peer.equals(NativeTransfer.connected())||CardTransport.busy()){
                        autoNotes.put(id,"自动发送失败：前置条件已变化");
                        message.setText(autoNotes.get(id));
                        releaseAuto();render.run();return;
                    }
                    if(!DashboardPhone.validJob(job,device)){
                        autoNotes.put(id,"自动发送失败：任务已过期或校验不通过");
                        message.setText(autoNotes.get(id));
                        releaseAuto();render.run();return;
                    }
                    approvePublishReceipt(a,listScreen,message,map(job),settings,token,peer,true,outcome->{
                        autoNotes.put(id,outcome);
                        releaseAuto();
                        if(listScreen.dialog.isShowing()){message.setText(outcome);render.run();}
                    });
                });
            }catch(Exception e){
                String err=e.getMessage()==null?"自动发送失败：读取详情失败":e.getMessage();
                MAIN.post(()->{
                    autoNotes.put(id,"自动发送失败："+err);
                    if(listScreen.dialog.isShowing()){message.setText(autoNotes.get(id));render.run();}
                    releaseAuto();
                });
            }
        },"TurboIO-dashboard-auto").start();
    }

    private static void releaseAuto(){synchronized(AUTO_LOCK){autoInFlight=false;}}

    private static void schedulePoll(EditorialUI.Screen s,Runnable[] poll,Object[] jobsHolder,String device,Runnable refresh){
        if(poll[0]!=null)MAIN.removeCallbacks(poll[0]);
        if(!s.dialog.isShowing())return;
        int ms=DashboardPhone.pollIntervalMs((List<?>)jobsHolder[0],device==null?"":device);
        poll[0]=()->{if(s.dialog.isShowing())refresh.run();};
        MAIN.postDelayed(poll[0],ms);
    }

    private static EditText field(Activity a,LinearLayout b,String label,String value,boolean password){
        b.addView(EditorialUI.text(a,label,14,EditorialUI.MUTED));
        EditText e=new EditText(a);e.setText(value);e.setTextColor(EditorialUI.INK);e.setTextSize(16);
        e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(EditorialUI.LIME));
        if(password)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        e.setSingleLine();b.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;
    }

    private static void configure(Activity a,TextView message,Runnable after){
        String peer=NativeTransfer.connected();
        if(peer==null||peer.isEmpty()){EditorialUI.notice(a,"先连接目标眼镜。配置会绑定当前眼镜，避免发到其他设备。");return;}
        EditorialUI.Screen cfg=new EditorialUI.Screen(a,"连接独立 API");
        Map<String,String> cur=DashboardPhoneStore.settings(a);
        cfg.body.addView(EditorialUI.text(a,"使用自己的 HTTPS 服务及 phone 角色 Token。读取身份后，将服务 device 绑定到当前眼镜；本轮不发送卡片。勿粘贴仓库 .state 文件路径。自动上镜需眼镜已有 TCE1/TVP2，本页不刷机。",13,EditorialUI.MUTED));
        EditText origin=field(a,cfg.body,"HTTPS 地址（默认可改；勿硬编码公网 IP）",cur.getOrDefault("origin",DashboardPhone.DEFAULT_ORIGIN),false);
        EditText device=field(a,cfg.body,"服务 device（默认 fanwang_io）",cur.getOrDefault("backendDevice",DashboardPhone.DEFAULT_DEVICE),false);
        EditText token=field(a,cfg.body,"phone Token（留空保留已保存）","",true);
        EditorialUI.button(a,cfg.body,"验证并绑定",true,()->{
            String o=DashboardPhone.origin(origin.getText().toString());
            String d=device.getText().toString().trim();
            String t=token.getText().toString().trim();
            token.setText("");
            if(o==null||!DashboardPhone.deviceOk(d)){EditorialUI.notice(a,"HTTPS 地址或 device 无效");return;}
            String useToken=t.isEmpty()?DashboardPhoneStore.token(a):t;
            if(!DashboardPhone.tokenOk(useToken)){EditorialUI.notice(a,"请粘贴有效 phone Token（32–128 位）");return;}
            String peerNow=peer;
            message.setText("正在验证服务身份…");
            new Thread(()->{
                try{
                    Object v=DashboardPhone.request(o,useToken,"/v1/identity","GET",null);
                    if(!(v instanceof Map)||!"phone".equals(map(v).get("role")))
                        throw new IllegalArgumentException("身份不符：需要 phone 角色 Token。");
                    Object idDevice=map(v).get("device");
                    if(!(idDevice instanceof String)||!d.equals(idDevice))
                        throw new IllegalArgumentException("Token 所属 device 与填写不一致。");
                    if(!peerNow.equals(NativeTransfer.connected()))
                        throw new IllegalArgumentException("眼镜连接已变化，未保存。");
                    boolean ok=DashboardPhoneStore.save(a,o,t.isEmpty()?"":t,peerNow,d);
                    MAIN.post(()->{
                        if(!ok){message.setText("身份不符或保存失败，未写入。");return;}
                        message.setText("已绑定当前眼镜。默认自动上镜；可在任务页关闭。");
                        cfg.dialog.dismiss();after.run();
                    });
                }catch(Exception e){
                    String err=e.getMessage()==null?"验证失败":e.getMessage();
                    MAIN.post(()->message.setText(err));
                }
            },"TurboIO-dashboard-identity").start();
        });
    }

    /** @param viewOnly true = 只读预览（自动开启时「查看」）；false = 可确认发送 */
    private static void openJob(Activity a,String jobId,TextView listMessage,boolean viewOnly){
        Map<String,String> settings=new LinkedHashMap<>(DashboardPhoneStore.settings(a));
        String token=DashboardPhoneStore.token(a);
        if(settings.isEmpty()||!DashboardPhone.tokenOk(token)){EditorialUI.notice(a,"请先配置后端。");return;}
        listMessage.setText("正在读取任务详情…");
        new Thread(()->{
            try{
                Object job=DashboardPhone.request(settings.get("origin"),token,"/v1/jobs/"+jobId,"GET",null);
                MAIN.post(()->{
                    if(!settings.equals(DashboardPhoneStore.settings(a))){listMessage.setText("配置已变化，未打开。");return;}
                    if(!DashboardPhone.validJob(job,settings.get("backendDevice"))){listMessage.setText("任务已过期或文档校验不通过，未发送。");return;}
                    review(a,map(job),settings,token,viewOnly);
                });
            }catch(Exception e){
                String err=e.getMessage()==null?"读取失败":e.getMessage();
                MAIN.post(()->listMessage.setText(err));
            }
        },"TurboIO-dashboard-job").start();
    }

    private static void review(Activity a,Map<String,Object> job,Map<String,String> settings,String token,boolean viewOnly){
        EditorialUI.Screen s=new EditorialUI.Screen(a,viewOnly?"任务预览":"发布预览");
        Map<String,Object> doc=map(job.get("document"));
        try{s.body.addView(new CardCanvas(a,doc,false));}catch(RuntimeException e){
            s.body.addView(EditorialUI.text(a,String.valueOf(doc.get("name")),18,EditorialUI.INK));
        }
        String summary=doc.get("name")+" · 版本 "+job.get("revision")+"\n来源："+settings.get("origin")
            +"\n仅发布此快照；不刷机；物理显示未验。自动上镜需眼镜已有 TCE1/TVP2，本页不刷机。";
        TextView status=EditorialUI.text(a,summary,14,EditorialUI.INK);s.body.addView(status);
        if(viewOnly){
            s.body.addView(EditorialUI.text(a,"只读预览。自动上镜开启时由列表静默发送；关闭自动后可用「预览并批准」。",13,EditorialUI.MUTED));
            return;
        }
        final boolean[] started={false};
        TextView send=EditorialUI.button(a,s.body,"确认发送到当前眼镜",true,()->{});
        send.setOnClickListener(v->{
            if(started[0])return;
            Map<String,String> now=DashboardPhoneStore.settings(a);
            if(!settings.equals(now)||!DashboardPhone.validJob(job,settings.get("backendDevice"))){
                EditorialUI.notice(a,"眼镜连接、配置或快照有效期已变化。请回列表刷新；不要在升级期间发送。");return;}
            String peer=settings.get("peer");
            if(peer==null||!peer.equals(NativeTransfer.connected())||CardTransport.busy()){
                EditorialUI.notice(a,"请保持前台、结束其他传输，并确认仍是绑定的眼镜。");return;}
            started[0]=true;send.setEnabled(false);
            approvePublishReceipt(a,s,status,job,settings,token,peer,false,outcome->{
                status.setText(outcome);
                if(outcome.startsWith("批准失败")||outcome.contains("未发送")){started[0]=false;send.setEnabled(true);}
            });
        });
    }

    /**
     * Shared path: re-list block gate → POST approve → CardTransport.publish(document,false) → receipt.
     * Honest failed/outcome_unknown; 409 treated as already approved (no publish, no fake accept).
     * {@code autoMode} only affects user-facing outcome strings.
     */
    private static void approvePublishReceipt(Activity a,EditorialUI.Screen s,TextView status,
            Map<String,Object> job,Map<String,String> settings,String token,String peer,
            boolean autoMode,java.util.function.Consumer<String> onTerminal){
        status.setText(autoMode?"自动上镜：批准当前文档…":"批准当前文档，等待服务确认…");
        String origin=settings.get("origin");
        Map<String,Object> body=new LinkedHashMap<>();body.put("hash",job.get("hash"));
        String jobId=String.valueOf(job.get("id"));
        new Thread(()->{
            try{
                Object list=DashboardPhone.request(origin,token,"/v1/jobs","GET",null);
                String block=list instanceof List?DashboardPhone.blockReason((List<?>)list,settings.get("backendDevice")):null;
                if(block!=null){
                    MAIN.post(()->onTerminal.accept(autoMode?"自动发送失败："+block:block));
                    return;
                }
                Object response=DashboardPhone.request(origin,token,"/v1/phone/jobs/"+jobId+"/approve","POST",body);
                if(!(response instanceof Map))throw new IllegalArgumentException("批准结果格式错误，未发送，请刷新确认。");
                Map<String,Object> resp=map(response);
                Object receiptToken=resp.get("receiptToken");
                if(!(receiptToken instanceof String)||((String)receiptToken).length()<32||((String)receiptToken).length()>128
                    ||!job.get("id").equals(resp.get("id"))||!job.get("hash").equals(resp.get("hash"))
                    ||!docEquals(resp.get("document"),job.get("document")))
                    throw new IllegalArgumentException("批准内容与预览不一致，未发送；请核对后端状态。");
                String lease=(String)receiptToken;
                MAIN.post(()->{
                    if(!s.dialog.isShowing()||!settings.equals(DashboardPhoneStore.settings(a))
                        ||!peer.equals(NativeTransfer.connected())){
                        report(a,s,status,settings,job,lease,mapOf("state","failed","code","phone_preflight_changed"),autoMode,onTerminal);
                        return;
                    }
                    status.setText(autoMode?"自动上镜：正在发送，请保持手机前台…":"正在发送，请保持手机前台，等待眼镜配置回读…");
                    CardTransport.init();
                    CardTransport.publish(map(job.get("document")),false);
                    watchTransport(a,s,status,settings,job,lease,peer,autoMode,onTerminal);
                });
            }catch(Exception e){
                String err=e.getMessage()==null?"批准失败":e.getMessage();
                MAIN.post(()->{
                    if(DashboardPhone.httpConflictAlreadyApproved(err)){
                        onTerminal.accept(autoMode?"自动发送冻结：服务端已批处理(409)，未再次发送":"服务端已批处理(409)，未再次发送；请刷新核对，勿盲重试。");
                    }else{
                        onTerminal.accept(autoMode?"自动发送失败："+err:"批准失败："+err);
                    }
                });
            }
        },"TurboIO-dashboard-approve").start();
    }

    private static boolean docEquals(Object a,Object b){
        try{return a!=null&&b!=null&&Arrays.equals(canonical(a),canonical(b));}catch(RuntimeException e){return false;}
    }

    private static Map<String,String> mapOf(String...kv){
        Map<String,String> m=new LinkedHashMap<>();
        for(int i=0;i+1<kv.length;i+=2)m.put(kv[i],kv[i+1]);
        return m;
    }

    private static void watchTransport(Activity a,EditorialUI.Screen s,TextView status,Map<String,String> settings,
            Map<String,Object> job,String lease,String peer,boolean autoMode,java.util.function.Consumer<String> onTerminal){
        long deadline=System.currentTimeMillis()+22000;
        Runnable[] tick={null};
        tick[0]=()->{
            if(!s.dialog.isShowing()){onTerminal.accept(autoMode?"自动发送中断：页面已关闭":"发送中断：页面已关闭");return;}
            if(!CardTransport.busy()){
                Map<String,String> receipt=DashboardPhone.receiptFromStatus(CardTransport.status());
                report(a,s,status,settings,job,lease,receipt,autoMode,onTerminal);
                return;
            }
            if(System.currentTimeMillis()>=deadline){
                report(a,s,status,settings,job,lease,mapOf("state","outcome_unknown","code","card_transport_timeout"),autoMode,onTerminal);
                return;
            }
            if(!peer.equals(NativeTransfer.connected())){
                report(a,s,status,settings,job,lease,mapOf("state","failed","code","phone_peer_changed"),autoMode,onTerminal);
                return;
            }
            status.setText((autoMode?"自动上镜发送中… ":"发送中… ")+CardTransport.status());
            MAIN.postDelayed(tick[0],500);
        };
        MAIN.post(tick[0]);
    }

    private static void report(Activity a,EditorialUI.Screen s,TextView status,Map<String,String> settings,
            Map<String,Object> job,String lease,Map<String,String> receipt,boolean autoMode,
            java.util.function.Consumer<String> onTerminal){
        if(!settings.equals(DashboardPhoneStore.settings(a))){
            onTerminal.accept(autoMode?"自动发送失败：后端配置已变化，回执未发送":"后端配置发生变化，回执未发送。请在原后端核对状态；不要重复发送卡片。");
            return;
        }
        String state=receipt.get("state"),code=receipt.get("code");
        String pending=autoMode
            ?("device_accepted".equals(state)?"已自动发送：眼镜配置已回读，正在同步回执":"自动发送未完成或结果未知，正在同步回执；不自动重发")
            :("device_accepted".equals(state)?"眼镜配置已回读，正在同步回执。仍需确认实际镜片显示。":"本轮未完成或结果未知，正在同步回执；不自动重发。");
        status.setText(pending);
        String token=DashboardPhoneStore.token(a);
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("receiptToken",lease);body.put("state",state);body.put("code",code);
        new Thread(()->{
            try{
                DashboardPhone.request(settings.get("origin"),token,"/v1/phone/jobs/"+job.get("id")+"/receipt","POST",body);
                String done=autoMode
                    ?("device_accepted".equals(state)?"已自动发送：眼镜配置已回读，回执已保存":"自动发送结束：状态已同步，请先核对眼镜，不自动重发")
                    :("device_accepted".equals(state)?"眼镜配置已回读，后端回执已保存。请确认实际显示（物理显示未验）。":"状态已同步到后端，请先核对眼镜，不自动重发。");
                MAIN.post(()->onTerminal.accept(done));
            }catch(Exception e){
                String fail=autoMode?"自动发送结束：眼镜操作已结束，但后端回执未保存；不要重复发送"
                    :"眼镜操作已经结束，但后端回执未保存。请刷新状态；不要重复发送。";
                MAIN.post(()->onTerminal.accept(fail));
            }
        },"TurboIO-dashboard-receipt").start();
    }
}
