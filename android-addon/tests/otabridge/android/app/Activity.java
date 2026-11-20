package android.app;
public class Activity extends android.content.Context {
 public boolean isChangingConfigurations(){return false;}
 public boolean isFinishing(){return false;}
 public boolean isDestroyed(){return false;}
 public void runOnUiThread(Runnable r){r.run();}
 public <T extends android.view.View> T findViewById(int id){return null;}
 public void startActivity(android.content.Intent i){}
 public void requestPermissions(String[] p,int rc){}
 public android.app.FragmentManager getFragmentManager(){return null;}
 public android.content.Intent getIntent(){return null;}
 public static final int RESULT_OK=-1;
}
