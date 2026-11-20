package com.turboio.addon;
import java.util.zip.CRC32;

/** TIMG v2 for TVP2's Turbo Image RX (image-upload-test built wide): 512×128 8-bit gray + 32-byte header,
 * file turbo-photo.timg. Byte-identical to a packet the unchanged firmware receiver accepts. */
final class Timg {
    static final int W=512,H=128,PIXELS=W*H,HEADER=32,WIRE=PIXELS+HEADER;static final String FILE="turbo-photo.timg";
    private Timg(){}
    private static void put(byte[] p,int at,long v){for(int i=0;i<4;i++)p[at+i]=(byte)(v>>(8*i));}
    static byte[] packet(byte[] pixels,long session,long frame){
        if(pixels==null||pixels.length!=PIXELS||session<=0||session>0xffffffffL||frame<=0||frame>0xffffffffL)return null;
        byte[] p=new byte[WIRE];byte[] prefix={'T','I','M','G',2,1,32,0,(byte)(W&255),(byte)(W>>8),(byte)(H&255),(byte)(H>>8),(byte)(W&255),(byte)(W>>8),0,0,(byte)(PIXELS&255),(byte)((PIXELS>>8)&255),(byte)((PIXELS>>16)&255),(byte)((PIXELS>>24)&255)};
        System.arraycopy(prefix,0,p,0,20);put(p,20,frame);put(p,24,session);CRC32 c=new CRC32();c.update(pixels);put(p,28,c.getValue());System.arraycopy(pixels,0,p,HEADER,PIXELS);return p;
    }
    /** Aspect-fit rectangle {x,y,w,h} inside 512×128 (black bars around it). */
    static int[] fit(int w,int h){double s=Math.min(W/(double)w,H/(double)h);int dw=Math.max(1,Math.min(W,(int)Math.round(w*s))),dh=Math.max(1,Math.min(H,(int)Math.round(h*s)));return new int[]{(W-dw)/2,(H-dh)/2,dw,dh};}
    /** Gray from a 512×128 ARGB canvas: (299R+587G+114B)/1000. */
    static byte[] luma(int[] argb){
        if(argb==null||argb.length!=PIXELS)return null;byte[] g=new byte[PIXELS];
        for(int i=0;i<PIXELS;i++){int c=argb[i];g[i]=(byte)((299*((c>>16)&255)+587*((c>>8)&255)+114*(c&255))/1000);}return g;
    }
    /** 16-level BT.601 luma, pre-quantized (GRAYMAP §4 scheme b, default): out = round(v/17)*17. The panel is
     *  g4 (4-bit / 16 levels, level k = k*17); pre-quantizing to the exact levels dodges the truncation crush. */
    static byte[] lumaQ16(int[] argb){
        if(argb==null||argb.length!=PIXELS)return null;byte[] g=new byte[PIXELS];
        for(int i=0;i<PIXELS;i++){int c=argb[i];int v=(299*((c>>16)&255)+587*((c>>8)&255)+114*(c&255))/1000;
            g[i]=(byte)(Math.round(v/17f)*17);}return g;
    }
    /* Bayer 8×8 ordered-dither matrix (GRAYMAP appendix A). */
    private static final int[] BAYER8={
        0,32, 8,40, 2,34,10,42,  48,16,56,24,50,18,58,26,  12,44, 4,36,14,46, 6,38,  60,28,52,20,62,30,54,22,
        3,35,11,43, 1,33, 9,41,  51,19,59,27,49,17,57,25,  15,47, 7,39,13,45, 5,37,  63,31,55,23,61,29,53,21};
    /** 16-level luma with Bayer 8×8 ordered dither (GRAYMAP §4 scheme c, gradient areas): spatial-for-gray. */
    static byte[] lumaQ16Dither(int[] argb){
        if(argb==null||argb.length!=PIXELS)return null;byte[] g=new byte[PIXELS];
        for(int i=0;i<PIXELS;i++){int c=argb[i];int v=(299*((c>>16)&255)+587*((c>>8)&255)+114*(c&255))/1000;
            int x=i%W,y=i/W;int lev=(int)Math.floor(v/255f*15+(BAYER8[(y%8)*8+(x%8)]+0.5)/64);
            g[i]=(byte)(Math.min(15,Math.max(0,lev))*17);}return g;
    }
}
