package com.turboio.addon;
import java.net.URI;

/** Shared model-endpoint helpers for the research pages (enhanced lifelog / recording notes / doctor tools).
 *  The context-English core (selection/prompt/validation) was removed 2026-11-17 (user ruling: unused). */
final class CeCore {
    private CeCore(){}
    private static IllegalArgumentException fail(String why){return new IllegalArgumentException(why);}
    /** NSCharacterSet.controlCharacterSet: Unicode Cc and Cf. */
    static boolean control(String s){for(int i=0;i<s.length();){int cp=s.codePointAt(i);i+=Character.charCount(cp);int t=Character.getType(cp);if(t==Character.CONTROL||t==Character.FORMAT)return true;}return false;}
    /** Bearer key: 1–8192 printable ASCII characters, so no platform header error can ever echo it. */
    static boolean keyValid(String key){if(key==null||key.isEmpty()||key.length()>8192)return false;for(int i=0;i<key.length();i++){char c=key.charAt(i);if(c<0x21||c>0x7e)return false;}return true;}
    /** https, host, no user/query/fragment; "/chat/completions" appended once. */
    static String endpoint(String endpoint){
        String bad="仅允许无账号、查询参数和片段的 HTTPS 模型地址";if(endpoint==null||endpoint.length()>2048)throw fail("模型地址或凭据无效");
        URI u;try{u=new URI(endpoint);}catch(Exception e){throw fail(bad);}
        if(u.getScheme()==null||!u.getScheme().equalsIgnoreCase("https")||u.getHost()==null||u.getHost().isEmpty()||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null)throw fail(bad);
        String path=u.getRawPath()==null?"":u.getRawPath();while(path.endsWith("/"))path=path.substring(0,path.length()-1);if(!path.endsWith("/chat/completions"))path+="/chat/completions";
        return "https://"+u.getRawAuthority()+path;
    }
}
