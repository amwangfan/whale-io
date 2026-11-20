package com.turboio.addon;
import android.content.*;
import java.io.*;
import java.util.*;

/** Shared glue for the research pages (enhanced lifelog / recording notes): the model-endpoint config and the
 *  official-cache directories. The context-English delivery stack was removed 2026-11-17 (user ruling: the feature
 *  was unused); the glasses-side module is retired separately on the firmware line. Main thread only. */
final class CeBridge {
    private static Context app;
    private CeBridge(){}
    static synchronized void init(Context c){if(app==null)app=c.getApplicationContext();}
    /** Host dirs where hive_ce may keep api_cache_user_<n>.hive (path_provider support dir and documents dir). */
    static List<File> dirs(){return Arrays.asList(app.getFilesDir(),new File(app.getApplicationInfo().dataDir,"app_flutter"));}
    static final class Config{String endpoint="",model="",key="";boolean disableThinking;String error;}
    static Config config(){
        Config c=new Config();SharedPreferences p=app.getSharedPreferences("turboio_settings",0);
        try{c.key=SecretStore.get(app);}catch(Exception e){c.key="";}
        c.model=p.getString("model","deepseek-flash");c.disableThinking=ChatPolicy.thinkingDisabled(p.contains("disable_thinking")?p.getBoolean("disable_thinking",true):null);
        String raw=p.getString("endpoint","https://api.deepseek.com/chat/completions");
        if(c.key==null||c.key.isEmpty()){c.error="请先配置自定义模型。";return c;}
        if(!CeCore.keyValid(c.key)){c.key="";c.error="模型地址或凭据无效";return c;}
        try{c.endpoint=CeCore.endpoint(raw);}catch(IllegalArgumentException e){c.error=e.getMessage();}
        return c;
    }
}
