package com.turboio.addon;
import android.content.*;
import android.os.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Scriptable test entry (2026-11-17, user-authorized, logged per trigger): an exported runtime-registered
 *  broadcast receiver driving the SAME code paths as the UI buttons. Guards: op allowlist + token extra matching
 *  the in-package asset (assets/turboio/test-entry-token.txt — never quoted in docs/chats). Every trigger is
 *  logged with a test-origin marker. No OTA/flash/settings ops exist here by construction. */
final class TestEntry {
    static final String ACTION="com.rayneo.venus.pub.TURBOIO_TEST";
    private static String token;private static boolean registered;
    private TestEntry(){}
    static void init(Context app){
        if(registered)return;registered=true;   /* install() runs on every MainActivity resume — register once */
        try{InputStream in=app.getAssets().open("turboio/test-entry-token.txt");
            byte[] b=new byte[128];int n=in.read(b);in.close();
            token=new String(b,0,n,StandardCharsets.UTF_8).trim();}catch(Exception e){token=null;}
        if(token==null||token.length()<16){android.util.Log.w("TurboIOTransfer","test entry disabled: token asset missing");return;}
        BroadcastReceiver r=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){handle(c,i);}};
        IntentFilter f=new IntentFilter(ACTION);
        if(Build.VERSION.SDK_INT>=33)app.registerReceiver(r,f,Context.RECEIVER_EXPORTED);else app.registerReceiver(r,f);
        android.util.Log.i("TurboIOTransfer","test entry armed (token configured)");
    }
    private static void handle(Context c,Intent i){
        String op=i.getStringExtra("op"),given=i.getStringExtra("token");
        if(op==null||!(op.equals("greeting_push")||op.equals("link_state")||op.equals("anim_play")||op.equals("banner_push")||op.equals("push_image"))){android.util.Log.w("TurboIOTransfer","test-origin refused: unknown op");return;}
        if(given==null||!given.trim().equals(token)){android.util.Log.w("TurboIOTransfer","test-origin refused: token mismatch");return;}
        GreetingBridge.lastTestOrigin="op="+op;
        android.util.Log.i("TurboIOTransfer","test-origin op="+op);
        if(op.equals("greeting_push")){
            String title=i.getStringExtra("title"),sub=i.getStringExtra("sub");
            byte[] frame=GreetingImage.build(title==null||title.isEmpty()?"测试问候":title,sub==null?"":sub,null);
            /* 2026-11-19 ruling: the test surface pins PAGE — a test/calibration frame never collapses into a
             *  6 s banner when the glasses foreground is navigation/call/voice. Production stays PushPolicy-auto. */
            GreetingBridge.push(frame,"greeting","问候",PushPolicy.Form.PAGE);
        }else if(op.equals("anim_play")){
            /* ANIM 点播测试: glasses-local animation. 2026-11-20 nb1123: extras passthrough for the fps A/B
             *  (user verdict) — asset_id/loop/fps_cap, defaults 0/true/60; clamps keep the wire shape
             *  (asset u16; cap 1..255). Session layer untouched; only when ANIM_LOCAL was negotiated. */
            long asset=0;boolean loop=true;int cap=60;
            android.os.Bundle x=i.getExtras();
            if(x!=null){Object ao=x.get("asset_id");if(ao instanceof Number)asset=((Number)ao).longValue();
                Object co=x.get("fps_cap");if(co instanceof Number)cap=((Number)co).intValue();
                Object lo=x.get("loop");if(lo instanceof Boolean)loop=((Boolean)lo).booleanValue();}
            if(asset<0||asset>65535)asset=0;if(cap<1||cap>255)cap=60;
            boolean ok=GreetingBridge.animPlay(asset,loop,cap);
            android.util.Log.i("TurboIOTransfer","test-origin anim_play asset="+asset+" loop="+loop+" cap="+cap+" "+(ok?"sent":"refused (ANIM_LOCAL not negotiated / busy)"));
        }else if(op.equals("banner_push")){
            /* banner-form push: pinned NOTIFICATION_BANNER (the per-push pin rides this push alone — no shared
             *  override to race); the BEGIN carries it only when the FORM_FIELD bit was negotiated. */
            byte[] frame=GreetingImage.build("横幅测试",null,null);
            GreetingBridge.push(frame,"greeting","问候",PushPolicy.Form.NOTIFICATION_BANNER);
        }else if(op.equals("push_image")){
            /* Image push test: decode a file, fit to 512x128 grayscale (Timg.fit, like the sticker page), push. */
            String path=i.getStringExtra("path");
            if(path==null||path.isEmpty())path="/sdcard/Download/dsh-sticker.png";
            final String p=path;final int mode="0".equals(i.getStringExtra("mode"))?0:"2".equals(i.getStringExtra("mode"))?2:1;
            new Thread(()->{byte[] f=null;try{
                android.graphics.Bitmap bm=android.graphics.BitmapFactory.decodeFile(p);
                if(bm!=null){f=StickerUI.toFrame(bm,mode);bm.recycle();}
            }catch(Throwable t){f=null;}
                if(f==null){android.util.Log.w("TurboIOTransfer","test-origin push_image: cannot decode "+p);return;}
                GreetingBridge.push(f,"sticker","贴纸",PushPolicy.Form.PAGE);},"turboio-test-image").start();
            android.util.Log.i("TurboIOTransfer","test-origin push_image "+p);
        }else{
            android.util.Log.i("TurboIOTransfer","test-origin link_state: "+NativeTransfer.diagnostic());
        }
    }
}
