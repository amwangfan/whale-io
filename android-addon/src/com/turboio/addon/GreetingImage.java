package com.turboio.addon;
import android.graphics.*;

/** Builds a 512×128 8-bit grayscale frame for the greeting page. Text + optional left icon.
 *  Layout: icon (48px) at left, title (big) + subtitle (small) right of it. Pure drawing. */
final class GreetingImage {
    private GreetingImage(){}
    static byte[] build(String title,String subtitle,Bitmap icon){
        Bitmap bmp=Bitmap.createBitmap(512,128,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(bmp);c.drawColor(Color.BLACK);
        int left=12;
        if(icon!=null&&!icon.isRecycled()){
            Bitmap ic=Bitmap.createScaledBitmap(icon,96,96,true);
            c.drawBitmap(ic,12,16,new Paint(Paint.FILTER_BITMAP_FLAG));ic.recycle();left=120;
        }
        Paint t=new Paint(Paint.ANTI_ALIAS_FLAG);t.setColor(Color.WHITE);
        t.setTextSize(subtitle==null||subtitle.isEmpty()?44:36);t.setFakeBoldText(true);
        c.drawText(title==null?"":title,left,subtitle==null||subtitle.isEmpty()?78:62,t);
        if(subtitle!=null&&!subtitle.isEmpty()){
            Paint s=new Paint(Paint.ANTI_ALIAS_FLAG);s.setColor(0xffc0c0c0);s.setTextSize(24);
            c.drawText(subtitle,left,104,s);
        }
        int[] px=new int[512*128];bmp.getPixels(px,0,512,0,0,512,128);bmp.recycle();
        byte[] g=new byte[512*128];
        for(int i=0;i<px.length;i++){int v=px[i];g[i]=(byte)((Color.red(v)*299+Color.green(v)*587+Color.blue(v)*114)/1000);}
        return g;
    }
    /** A built-in whale-girl style glyph tile (48px) when no custom icon. */
    static Bitmap defaultIcon(){
        Bitmap b=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(Color.BLACK);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.WHITE);
        c.drawCircle(48,44,30,p);p.setColor(Color.BLACK);c.drawCircle(38,40,6,p);c.drawCircle(58,40,6,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);c.drawArc(new RectF(36,50,60,70),0,180,false,p);
        return b;
    }
}
