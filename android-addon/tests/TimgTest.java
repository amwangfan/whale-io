package com.turboio.addon;
import java.nio.file.*;import java.util.*;

/** TIMG v2 codec (fit/luma/packet) — the one-shot upload state machine was removed 2026-11-17 with the image-upload
 *  lab; the sticker page reuses this codec for its glasses push. */
public final class TimgTest {
    static int n;static void ok(boolean b,String why){n++;if(!b)throw new AssertionError("timg "+n+" "+why);}
    public static void main(String[] a)throws Exception{
        byte[] px=new byte[Timg.PIXELS];for(int i=0;i<px.length;i++)px[i]=(byte)(i%251);
        ok(Arrays.equals(Timg.packet(px,77,1),Files.readAllBytes(Paths.get(a[0]).resolve("packet.bin"))),"packet byte-identical to the firmware-accepted reference");
        ok(Timg.packet(px,0,1)==null&&Timg.packet(px,77,0)==null&&Timg.packet(new byte[10],77,1)==null,"invalid packet refused");
        int[] r=Timg.fit(1024,1024);ok(r[0]==192&&r[1]==0&&r[2]==128&&r[3]==128,"fit square "+Arrays.toString(r));
        r=Timg.fit(4000,500);ok(r[2]==512&&r[3]==64&&r[1]==32,"fit wide "+Arrays.toString(r));
        int[] argb=new int[Timg.PIXELS];Arrays.fill(argb,0xffffffff);argb[1]=0xff000000;argb[2]=0xffff0000;byte[] g=Timg.luma(argb);ok((g[0]&255)==255&&g[1]==0&&(g[2]&255)==76,"luma");
        System.out.println("TimgTest: "+n+" checks; golden packet, fit, luma");
    }
}
