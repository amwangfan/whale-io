package android.content;
import java.io.File;import java.io.InputStream;import java.util.*;
/** Journal fake: commit() persists to `disk` unless `fail` is set. Extended 2026-11-19 to cover the src closure. */
public class Context {
 public final Prefs prefs=new Prefs();
 public Context getApplicationContext(){return this;}
 public SharedPreferences getSharedPreferences(String n,int mode){return prefs;}
 public File getExternalFilesDir(String type){return null;}
 public File filesDirOverride;
 public File getFilesDir(){return filesDirOverride!=null?filesDirOverride:new File("/nonexistent-turboio-test");}
 public File getCacheDir(){return getFilesDir();}
 public File getNoBackupFilesDir(){return getFilesDir();}
 public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager();}
 public ComponentName startForegroundService(Intent i){return null;}
 public String getPackageName(){return "com.rayneo.venus.pub";}
 public android.content.pm.PackageManager getPackageManager(){return null;}
 public android.content.pm.ApplicationInfo getApplicationInfo(){return null;}
 public CharSequence getCharSequence(String key){return null;}
 public android.content.ContentResolver getContentResolver(){return null;}
 public android.content.res.Resources getResources(){return null;}
 public android.app.Application getApplication(){return null;}
 public int checkSelfPermission(String p){return 0;}
 public Object getSystemService(String name){return null;}
 public <T> T getSystemService(Class<T> type){return null;}
 public Intent registerReceiver(Object receiver,Object filter){return null;}
 public Intent registerReceiver(Object receiver,Object filter,int flags){return null;}
 public void unregisterReceiver(Object receiver){}
 public static final int RECEIVER_EXPORTED=2;
 public static final String AUDIO_SERVICE="audio",CAMERA_SERVICE="camera",CONNECTIVITY_SERVICE="connectivity";
 public static final class Prefs implements SharedPreferences {
  public boolean fail;public final Map<String,Object> disk=new HashMap<>();
  public boolean getBoolean(String k,boolean d){Object o=disk.get(k);return o==null?d:(Boolean)o;}
  public String getString(String k,String d){Object o=disk.get(k);return o==null?d:(String)o;}
  public long getLong(String k,long d){Object o=disk.get(k);return o==null?d:(Long)o;}
  public int getInt(String k,int d){Object o=disk.get(k);return o==null?d:(Integer)o;}
  public float getFloat(String k,float d){Object o=disk.get(k);return o==null?d:(Float)o;}
  public boolean contains(String k){return disk.containsKey(k);}
  public Editor edit(){Map<String,Object> staged=new HashMap<>();return new Editor(){
   public Editor putBoolean(String k,boolean v){staged.put(k,v);return this;}
   public Editor putString(String k,String v){staged.put(k,v);return this;}
   public Editor putLong(String k,long v){staged.put(k,v);return this;}
   public Editor putInt(String k,int v){staged.put(k,v);return this;}
   public Editor putFloat(String k,float v){staged.put(k,v);return this;}
   public Editor remove(String k){staged.remove(k);disk.remove(k);return this;}
   public Editor clear(){staged.clear();disk.clear();return this;}
   public boolean commit(){if(fail)return false;disk.putAll(staged);return true;}
   public void apply(){if(!fail)disk.putAll(staged);}};}
 }
}
