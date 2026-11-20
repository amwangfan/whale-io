package com.turboio.addon;

import java.util.zip.CRC32;

/** Shared little-endian u32 + CRC-32 helpers (used by AppCodec/MusicCodec). The TFP1 pomodoro codec that used to
 *  live here was removed 2026-11-17 with the pomodoro feature (user ruling: unused). */
public final class FocusCodec {
    private FocusCodec() {}
    public static long u32(byte[] b,int at) {return (b[at]&255L)|((b[at+1]&255L)<<8)|((b[at+2]&255L)<<16)|((b[at+3]&255L)<<24);}
    public static long crc(byte[] b,int length){CRC32 crc=new CRC32();crc.update(b,0,length);return crc.getValue();}
}
