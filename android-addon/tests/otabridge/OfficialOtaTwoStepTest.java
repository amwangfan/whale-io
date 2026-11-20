package com.turboio.addon;

import android.app.Application;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.util.zip.ZipInputStream;

/** Two-step flow (2026-11-16): 「选择包」→「校验目录，并允许一次试刷」(token → service → freeze+preflight together →
 *  arm only when both green). Fail-stop on every refusal; the gate's type-4 fence is unchanged. Fakes only. */
public final class OfficialOtaTwoStepTest {
    static int n;static Application app;
    static void ok(boolean b,String why){n++;if(!b)throw new AssertionError("twostep "+n+" "+why+"\n"+OfficialOtaBridge.status());}
    static Object get(String f)throws Exception{Field x=OfficialOtaBridge.class.getDeclaredField(f);x.setAccessible(true);return x.get(null);}
    static void set(String f,Object v)throws Exception{Field x=OfficialOtaBridge.class.getDeclaredField(f);x.setAccessible(true);x.set(null,v);}
    static void call2(String m,String arg)throws Exception{Method x=OfficialOtaBridge.class.getDeclaredMethod(m,String.class);x.setAccessible(true);x.invoke(null,arg);}
    static void reply(int type,Object json){Map<String,Object> m=new HashMap<>();m.put("businessId",9);m.put("deviceId",NativeTransfer.current);m.put("payload",OtaFrame.encode(type,json,null));Map<String,Object> e=new HashMap<>();e.put("message",m);OfficialOtaBridge.observe("messageReceived",e);}
    static Object version611(){return OtaFrame.object("OsVersion","Strix OS 1.0.6.11","OtaValidationState","idle","OtaValidationConfirmed",true,"PackageTypes",Arrays.asList("files"));}
    /** Unzip the reviewed candidate into the fake files dir the way the official download would. */
    static void prepareDir()throws Exception{
        Path root=Files.createTempDirectory("twostep");Path dir=root.resolve("ota/"+OtaPackage.DIRECTORY);Files.createDirectories(dir);
        try(ZipInputStream z=new ZipInputStream(Files.newInputStream(Paths.get("E:/WhaleIO/tmp/build611-10th/v3/tier16384/StrixOS-1.0.6.11-TurboIO-TVP2-port-v3-tier16384.zip")))){
            ZipEntry e;while((e=z.getNextEntry())!=null){Files.copy(z,dir.resolve(e.getName()));}}
        ((android.content.Context)app).filesDirOverride=root.toFile();
    }
    static void waitFreeze()throws Exception{}
    /** The freeze runs on a real background thread; poll the condition, draining the fake main loop meanwhile. */
    static void waitFor(java.util.function.BooleanSupplier c)throws Exception{for(int i=0;i<200&&!c.getAsBoolean();i++){Thread.sleep(50);Handler.drain();}Handler.drain();}
    /** Poll WITHOUT draining: use when a queued main-loop runnable must not run before the replies are in. */
    static void waitQueued(java.util.function.BooleanSupplier c)throws Exception{for(int i=0;i<200&&!c.getAsBoolean();i++)Thread.sleep(50);}
    public static void main(String[] a)throws Exception{
        app=new Application();OfficialOtaBridge.init(app);Handler.drain();set("foreground",true);
        // 1. wrong token: refused before any work
        call2("inspectAndAuthorize","WRONG");Handler.drain();
        ok(!get("armed").equals(Boolean.TRUE)&&NavReflect.sent.isEmpty(),"wrong token: nothing sent, nothing armed");
        // 2. right token but no prepared directory: preflight queries go out immediately, freeze fails, no arm
        call2("inspectAndAuthorize",OtaPackage.TOKEN);
        waitFor(()->OfficialOtaBridge.status().contains("目录校验未通过"));
        ok(!get("armed").equals(Boolean.TRUE),"unprepared directory: refused");
        ok(NavReflect.sent.size()==3,"preflight queries went out concurrently with the freeze");
        // 3. prepared directory + green preflight replies → armed, and the gate's type-4 fence still governs Mode2
        prepareDir();call2("inspectAndAuthorize",OtaPackage.TOKEN);waitQueued(()->NavReflect.sent.size()>=6&&Handler.pending()>0);
        ok(NavReflect.sent.size()==6,"second attempt sent its own three queries");
        reply(1,version611());reply(2,OtaFrame.object("BatteryLevel",90));reply(11,OtaFrame.object("Result",true));
        Handler.drain();
        ok(get("armed").equals(Boolean.TRUE),"both green → armed");
        // vendor's own space query precedes Mode2 in the real flow
        Object space=new NavReflect.Message(OtaFrame.encode(3,OtaFrame.object("OtaSize",OtaPackage.ZIP_SIZE),null),NativeTransfer.current,NavReflect.Biz.MARS_FOTA);
        ok(Boolean.TRUE.equals(OfficialOtaBridge.allowQueue(space)),"type3 space query passes");
        reply(3,OtaFrame.object("Result",true));
        Object msg=new NavReflect.Message(OtaFrame.encode(4,OtaFrame.object("Mode",2),null),NativeTransfer.current,NavReflect.Biz.MARS_FOTA);
        ok(Boolean.TRUE.equals(OfficialOtaBridge.allowQueue(msg)),"Mode2 passes the unchanged gate fence");
        ok(get("pending").equals(Boolean.TRUE),"journal pending landed at Mode2");
        System.out.println("OfficialOtaTwoStepTest: "+n+" checks; wrong token / unprepared dir / full-green arm / type-4 fence unchanged");
    }
}
