package android.content;
public interface SharedPreferences {
 boolean getBoolean(String k,boolean d);String getString(String k,String d);long getLong(String k,long d);
 int getInt(String k,int d);float getFloat(String k,float d);boolean contains(String k);Editor edit();
 public interface Editor {Editor putBoolean(String k,boolean v);Editor putString(String k,String v);Editor putLong(String k,long v);
  Editor putInt(String k,int v);Editor putFloat(String k,float v);Editor remove(String k);Editor clear();boolean commit();void apply();}
}
