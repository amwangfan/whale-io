package com.turboio.addon;

/** Obfuscated host names per pinned host build; selected once by probing each manager, newest first. */
final class HostAbi {
    final String manager, message, priority, business, error, core, send, file, version, devices, smallCore, sendFailed;
    private HostAbi(String version,String pkg,String sdk,String core,String msg,String send,String file){
        this(version,pkg,sdk,core,msg,send,file,"u","b","g","b","r","A");
    }
    private HostAbi(String version,String pkg,String sdk,String core,String msg,String send,String file,
                    String managerClass,String priorityClass,String devices,String smallCore,String sendFailed){
        this(version,pkg,sdk,core,msg,send,file,managerClass,priorityClass,devices,smallCore,sendFailed,"A");
    }
    private HostAbi(String version,String pkg,String sdk,String core,String msg,String send,String file,
                    String managerClass,String priorityClass,String devices,String smallCore,String sendFailed,String errorClass){
        this.version=version;manager=pkg+"."+managerClass;message=pkg+"."+msg;priority=pkg+"."+priorityClass;
        business=sdk+".h";error=sdk+"."+errorClass;this.core=core+".q";this.send=send;this.file=file;
        this.devices=devices;this.smallCore=smallCore;this.sendFailed=sendFailed;
    }
    static final HostAbi V106=new HostAbi("1.0.6","X3","i4","j4","Q","t","u");
    static final HostAbi V107=new HostAbi("1.0.7","Y3","j4","k4","S","t","u");
    static final HostAbi V110=new HostAbi("1.0.10","O4","a5","b5","V","t","u","v","c","i","SmallCore","MESSAGE_SEND_FAILED");
    static final HostAbi V120=new HostAbi("1.0.20","com.ffalconxr.mercury.coreconnect","vb","wb","v0","u","v","w","b","i","SmallCore","MESSAGE_SEND_FAILED","a0");
    /* 1.0.25: same coreconnect package; message v0→u0, business enum vb.h→lc.h, core enum wb.q→mc.q,
     * manager w→v. Verified by field/enum double-compare (tmp/abi-1025-notes.md). */
    static final HostAbi V125=new HostAbi("1.0.25","com.ffalconxr.mercury.coreconnect","lc","mc","u0","u","v","v","b","i","SmallCore","MESSAGE_SEND_FAILED","a0");
    private static volatile HostAbi current;
    static HostAbi get(){
        HostAbi a=current;if(a!=null)return a;
        // JVM test hook only: Android never sets this property for the app process.
        String forced=System.getProperty("turboio.host");
        // Unknown hosts fall back to the oldest supported ABI; every reflective use fails closed.
        if("1.0.25".equals(forced))a=V125;
        else if("1.0.20".equals(forced))a=V120;
        else if("1.0.10".equals(forced))a=V110;
        else if("1.0.7".equals(forced))a=V107;
        else if("1.0.6".equals(forced))a=V106;
        else a=matches(V125)?V125:matches(V120)?V120:matches(V110)?V110:matches(V107)?V107:V106;
        current=a;return a;
    }
    private static boolean matches(HostAbi a){
        try{Class<?> m=Class.forName(a.manager);return m.getField("h").getType()==m&&Class.forName(a.business).isEnum();}catch(Throwable e){return false;}
    }
    static String manager(){return get().manager;}
    static String message(){return get().message;}
    static String priority(){return get().priority;}
    static String business(){return get().business;}
    static String error(){return get().error;}
    static String core(){return get().core;}
    static String send(){return get().send;}
    static String file(){return get().file;}
    static String devices(){return get().devices;}
    static String smallCore(){return get().smallCore;}
    static String sendFailed(){return get().sendFailed;}
}
