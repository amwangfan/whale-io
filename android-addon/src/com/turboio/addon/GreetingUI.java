package com.turboio.addon;
import android.app.Activity;
import android.graphics.*;
import android.widget.*;
import android.view.*;

/** F2 proactive greeting UI: type a line / pick an image, push to the glasses' auto-popup display page
 *  (GREETING_SID). Requires TVP2C-611 firmware. */
final class GreetingUI {
    static void show(Activity a){
        EditorialUI.Screen s=new EditorialUI.Screen(a,"主动问候 · 眼镜弹窗");
        LinearLayout b=s.body;
        TextView info=EditorialUI.text(a,"往眼镜推一帧 512×128 灰度图，眼镜自动弹出显示页（不用先开页）。需要 TVP2C-611 固件。",13,EditorialUI.MUTED);b.addView(info);
        final EditText title=NewsUI.input(a,b,"主标题","你好呀");final EditText sub=NewsUI.input(a,b,"副标题","来自手机");
        final BitmapHolder holder=new BitmapHolder();
        EditorialUI.button(a,b,"选一张图（可选）",false,()->DocumentPicker.pick(a,8*1024*1024,bytes->{holder.bitmap=decode(bytes);}));
        TextView status=EditorialUI.text(a,GreetingBridge.status(),13,EditorialUI.MUTED);b.addView(status);
        EditorialUI.button(a,b,"推送问候到眼镜",true,()->{
            byte[] frame=GreetingImage.build(title.getText().toString(),sub.getText().toString(),holder.bitmap);
            GreetingBridge.push(frame);
        });
        EditorialUI.button(a,b,"上下文问候 · AI 生成并推送",false,()->{
            status.setText("云端生成问候文案…");
            WhaleCloud.chat(a,"结合现在的时间和我今天的日程/天气，生成一句适合推到眼镜的简短问候（不超过16字，只要问候语本身）。","quick",null,(reply,sid,err)->{
                if(err!=null||reply==null){status.setText("生成失败："+(err==null?"空":err));return;}
                String t=reply.trim();if(t.length()>16)t=t.substring(0,16);
                byte[] frame=GreetingImage.build(t,"",holder.bitmap);
                GreetingBridge.push(frame);
            });
        });
        EditorialUI.button(a,b,"外卖演示弹窗",false,()->{
            byte[] frame=GreetingImage.build("外卖到了","美团 · 预计 12:30 送达",holder.bitmap);
            GreetingBridge.push(frame);
        });
        Runnable refresh=()->status.setText(GreetingBridge.status());GreetingBridge.listen(refresh);
        s.dialog.setOnDismissListener(d->GreetingBridge.listen(null));
    }
    private static Bitmap decode(byte[] bytes){try{BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);if(o.outWidth<=0)return null;BitmapFactory.Options p=new BitmapFactory.Options();while(Math.max(o.outWidth,o.outHeight)/Math.max(1,p.inSampleSize)>512)p.inSampleSize=p.inSampleSize<1?2:p.inSampleSize*2;return BitmapFactory.decodeByteArray(bytes,0,bytes.length,p);}catch(RuntimeException e){return null;}}
    private static final class BitmapHolder{Bitmap bitmap;}
}
