package com.turboio.addon;
public final class TimgQ16Test {
    static int n; static void ok(boolean b,String w){n++;if(!b)throw new AssertionError("q16 "+n+" "+w);}
    public static void main(String[] a){
        int[] px=new int[Tdp.PIXELS]; for(int i=0;i<px.length;i++)px[i]=0xff000000|((i*7)%256)<<16|((i*5)%256)<<8|((i*3)%256);
        byte[] b=Timg.lumaQ16(px); ok(b!=null&&b.length==Tdp.PIXELS,"q16 size");
        boolean all17=true; for(byte v:b)if(((v&255)%17)!=0)all17=false; ok(all17,"q16 all multiples of 17");
        byte[] d=Timg.lumaQ16Dither(px); boolean d17=true; for(byte v:d)if(((v&255)%17)!=0)d17=false; ok(d17,"dither all multiples of 17");
        // a mid-gray area should dither to a mix of levels, not a single flat value
        int[] mid=new int[Tdp.PIXELS]; for(int i=0;i<mid.length;i++)mid[i]=0xff808080;
        byte[] dm=Timg.lumaQ16Dither(mid); java.util.Set<Integer> lv=new java.util.HashSet<>(); for(byte v:dm)lv.add(v&255);
        ok(lv.size()>1,"dither spreads mid-gray over adjacent levels");
        // scheme b maps 128 to a single level (no dither)
        byte[] bm=Timg.lumaQ16(mid); ok(bm[0]==bm[1]&&bm[0]==bm[Tdp.PIXELS-1],"q16 mid-gray flat");
        // black/white endpoints
        int[] bw=new int[Tdp.PIXELS]; bw[0]=0xff000000; bw[1]=0xffffffff;
        byte[] be=Timg.lumaQ16(bw); ok((be[0]&255)==0,"black→0"); ok((be[1]&255)==255,"white→255");
        System.out.println("TimgQ16Test: "+n+" checks; q16 pre-quant, bayer dither, endpoints");
    }
}