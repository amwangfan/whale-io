package android.content;
/** Minimal compile surface for the src closure (2026-11-19 rebuild). */
public class Intent {
 public Intent(){}
 public Intent(Context c,Class<?> k){}
 public Intent(String action){}
 public String getAction(){return null;}
 public String getStringExtra(String k){return null;}
 public int getIntExtra(String k,int d){return d;}
 public long getLongExtra(String k,long d){return d;}
 public boolean getBooleanExtra(String k,boolean d){return d;}
 public android.net.Uri getData(){return null;}
 public Intent setAction(String a){return this;}
 public Intent setType(String t){return this;}
 public Intent setClipData(Object clip){return this;}
 public Intent putExtra(String k,String v){return this;}
 public Intent putExtra(String k,int v){return this;}
 public Intent putExtra(String k,boolean v){return this;}
 public Intent putExtra(String k,android.os.Parcelable v){return this;}
 public Intent addFlags(int f){return this;}
 public Intent addCategory(String c){return this;}
 public static Intent createChooser(Intent i,String title){return i;}
 public static final String ACTION_SEND="android.intent.action.SEND";
 public static final String ACTION_OPEN_DOCUMENT="android.intent.action.OPEN_DOCUMENT";
 public static final String ACTION_BATTERY_CHANGED="android.intent.action.BATTERY_CHANGED";
 public static final String CATEGORY_OPENABLE="android.intent.category.OPENABLE";
 public static final String EXTRA_STREAM="android.intent.extra.STREAM";
 public static final String EXTRA_TEXT="android.intent.extra.TEXT";
 public static final String EXTRA_SUBJECT="android.intent.extra.SUBJECT";
 public static final int FLAG_GRANT_READ_URI_PERMISSION=1;
}
