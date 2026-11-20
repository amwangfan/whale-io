package com.turboio.addon;
import android.content.Context;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;

/** Reflection into the official always-on controller (SDK plugin field y; verified per host by verifyHost):
 * configureDump f/e(enabled,realtime,cached,events) and status m() (1.0.20), j() (1.0.25) or k() (older).
 * Names are probed by signature; null / false when anything is missing. */
final class AlwaysOnHost {
    private AlwaysOnHost(){}
    private static Object controller(){
        try{Object p=TurboAddon.sdkPlugin();if(p==null)return null;Field y=p.getClass().getField("y");return y.get(p);}catch(Throwable e){return null;}
    }
    private static Method statusMethod(Object c)throws Exception{
        for(String n:new String[]{"m","j","k"}){try{Method m=c.getClass().getMethod(n);if(Map.class.isAssignableFrom(m.getReturnType()))return m;}catch(NoSuchMethodException ignored){}}
        throw new NoSuchMethodException("always-on status");
    }
    static Map<?,?> status(){
        try{Object c=controller();if(c==null)return null;Method k=statusMethod(c);Object r=k.invoke(c);return r instanceof Map?new LinkedHashMap<>((Map<?,?>)r):null;}catch(Throwable e){return null;}
    }
    /** iOS TIOAOSetAudioSaving: realtime + cached, never the event-text log; verified by reading the status back. */
    static boolean setSaving(boolean enabled){
        try{Object c=controller();if(c==null)return false;Method f=null;
            for(String n:new String[]{"f","e"}){try{Method m=c.getClass().getMethod(n,boolean.class,boolean.class,boolean.class,boolean.class);if(m.getReturnType()==void.class){f=m;break;}}catch(NoSuchMethodException ignored){}}
            if(f==null)return false;
            f.invoke(c,enabled,true,true,false);return AlwaysOnAudio.verify(status(),enabled);}catch(Throwable e){return false;}
    }
    /** Same base the host uses: external files dir, else internal files dir. */
    static File base(Context c){Context app=c.getApplicationContext();File ext=app.getExternalFilesDir(null);return ext!=null?ext:app.getFilesDir();}
}
