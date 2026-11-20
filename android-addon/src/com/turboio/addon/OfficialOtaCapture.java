package com.turboio.addon;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.widget.*;
import java.io.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Scan official glasses OTA cache under app-private dirs and export a ZIP to public Download.
 * Relies on the user having already downloaded via official「设置→固件更新」(login / real SN).
 * Does NOT hit gray CDN, does NOT arm OfficialOtaFeed, does NOT call OtaController / flash frames. */
final class OfficialOtaCapture {
 // MediaStore RELATIVE_PATH: Download/TurboIO (same idea as Pictures/TurboIO). Plain DIRECTORY_DOWNLOADS also fine.
 private static final String REL_PATH=Environment.DIRECTORY_DOWNLOADS+"/TurboIO";
 private static final Handler MAIN=new Handler(Looper.getMainLooper());
 private static volatile boolean busy;
 private static volatile String note="尚未扫描";
 private static volatile File chosen;
 private static volatile String lastSha="",lastPath="";

 private OfficialOtaCapture(){}

 static String statusLine(){
  StringBuilder b=new StringBuilder(note);
  if(chosen!=null)b.append("\n候选：").append(chosen.getAbsolutePath())
    .append(chosen.isDirectory()?"（目录）":"（文件）");
  if(!lastPath.isEmpty())b.append("\n上次导出：").append(lastPath);
  if(!lastSha.isEmpty())b.append("\nSHA-256：").append(lastSha);
  if(busy)b.append("\n…进行中，请稍候");
  return b.toString();
 }

 static void show(Activity a){
  EditorialUI.Screen s=new EditorialUI.Screen(a,"官方眼镜 OTA → Download");
  LinearLayout body=s.body;
  body.addView(EditorialUI.text(a,"公网 gray 无登录态拿不到官方眼镜包。请先在官方「设置 → 固件更新」用已登录账号 / 真 SN 下载（包落在 App 私有 ota 缓存）。本操作只扫描并导出到公共 Download，不替你刷眼镜、不发 MARS_FOTA 安装帧、不走实验刷写授权。",14,EditorialUI.MUTED));
  TextView state=EditorialUI.text(a,statusLine(),13,EditorialUI.LIME);body.addView(state);
  Runnable refresh=()->{if(s.dialog.isShowing())state.setText(statusLine());};
  EditorialUI.button(a,body,"扫描本机官方 OTA 缓存",true,()->{
   if(busy){EditorialUI.notice(a,"进行中，请稍候");return;}
   busy=true;note="扫描中…";chosen=null;refresh.run();
   new Thread(()->{
    try{
     File files=a.getFilesDir(),parent=files==null?null:files.getParentFile();
     File ext=a.getExternalFilesDir(null),cache=a.getCacheDir();
     List<File> found=OfficialOtaCaptureCore.collectCandidates(files,parent,ext,cache);
     File pick=OfficialOtaCaptureCore.pickSource(found);
     if(pick==null){note="未发现官方 OTA 缓存。请先在官方固件更新页完成下载（常见目录名 Strix_OS_*）。";chosen=null;}
     else{
      long bytes=OfficialOtaCaptureCore.estimateBytes(pick);
      if(!OfficialOtaCaptureCore.acceptSize(bytes)){note="候选过大或为空（上限 512MiB）："+bytes+" 字节 · "+pick.getName();chosen=null;}
      else{chosen=pick;note="发现 "+found.size()+" 处；已选 "+pick.getName()+" · 约 "+bytes+" 字节";}
     }
    }catch(Exception e){note="扫描失败："+safe(e);chosen=null;}
    finally{busy=false;MAIN.post(refresh);}
   },"TurboIO-ota-capture-scan").start();
  });
  EditorialUI.button(a,body,"导出到公共 Download",false,()->{
   if(busy){EditorialUI.notice(a,"进行中，请稍候");return;}
   if(chosen==null||!chosen.exists()){EditorialUI.notice(a,"请先扫描并确认候选");return;}
   if(Build.VERSION.SDK_INT<29){EditorialUI.notice(a,"需要 Android 10+（MediaStore.Downloads）");return;}
   final File src=chosen;
   busy=true;note="导出中…";lastSha="";lastPath="";refresh.run();
   new Thread(()->{
    Uri uri=null;
    try{
     long bytes=OfficialOtaCaptureCore.estimateBytes(src);
     if(!OfficialOtaCaptureCore.acceptSize(bytes))throw new IOException("size "+bytes);
     String name=OfficialOtaCaptureCore.displayName(src.getName(),System.currentTimeMillis());
     ContentValues v=new ContentValues();
     v.put(MediaStore.Downloads.DISPLAY_NAME,name);
     v.put(MediaStore.Downloads.MIME_TYPE,"application/zip");
     v.put(MediaStore.Downloads.RELATIVE_PATH,REL_PATH);
     v.put(MediaStore.Downloads.IS_PENDING,1);
     uri=a.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
     if(uri==null)throw new IOException("MediaStore insert failed");
     MessageDigest md=MessageDigest.getInstance("SHA-256");
     long written;
     try(OutputStream raw=a.getContentResolver().openOutputStream(uri)){
      if(raw==null)throw new IOException("no stream");
      DigestOutputStream out=new DigestOutputStream(raw,md);
      if(src.isFile())written=copyFile(src,out);
      else written=zipDirectory(src,out);
      out.flush();
      if(!OfficialOtaCaptureCore.acceptSize(written))throw new IOException("wrote "+written);
     }
     v.clear();v.put(MediaStore.Downloads.IS_PENDING,0);
     a.getContentResolver().update(uri,v,null,null);
     lastSha=hex(md.digest());
     lastPath=REL_PATH+"/"+name;
     note="已导出 · "+written+" 字节 · "+name+"\n路径在文件管理器 Download/TurboIO（本操作不刷眼镜）";
    }catch(Exception e){
     if(uri!=null)try{a.getContentResolver().delete(uri,null,null);}catch(Exception ignored){}
     note="导出失败："+safe(e);
     lastSha="";lastPath="";
    }finally{busy=false;MAIN.post(refresh);}
   },"TurboIO-ota-capture-export").start();
  });
  EditorialUI.button(a,body,"关闭",false,()->s.dialog.dismiss());
  body.addView(EditorialUI.text(a,"验证：官方下载完成 → 本页扫描 → 导出 → 文件管理器查看 Download/TurboIO；或 adb shell ls /sdcard/Download/TurboIO。",12,EditorialUI.MUTED));
  Handler h=new Handler(Looper.getMainLooper());
  Runnable tick=new Runnable(){public void run(){if(!s.dialog.isShowing())return;refresh.run();h.postDelayed(this,500);}};
  h.post(tick);s.dialog.setOnDismissListener(d->h.removeCallbacks(tick));
 }

 private static long copyFile(File src,OutputStream out)throws IOException{
  long total=0;byte[] buf=new byte[65536];
  try(InputStream in=new FileInputStream(src)){
   int n;while((n=in.read(buf))>0){total+=n;if(total>OfficialOtaCaptureCore.MAX_BYTES)throw new IOException("oversize");out.write(buf,0,n);}
  }
  return total;
 }

 /** Pack only direct children of the directory (no nesting). Does not close {@code out}. */
 private static long zipDirectory(File dir,OutputStream out)throws IOException{
  File[] kids=dir.listFiles();if(kids==null)throw new IOException("unreadable");
  Arrays.sort(kids,Comparator.comparing(File::getName));
  long total=0;
  ZipOutputStream z=new ZipOutputStream(out);
  z.setLevel(Deflater.DEFAULT_COMPRESSION);
  byte[] buf=new byte[65536];
  for(File k:kids){
   if(k.isDirectory())throw new IOException("nested directory");
   if(java.nio.file.Files.isSymbolicLink(k.toPath()))throw new IOException("symlink");
   ZipEntry e=new ZipEntry(k.getName());e.setTime(k.lastModified());z.putNextEntry(e);
   try(InputStream in=new FileInputStream(k)){
    int n;while((n=in.read(buf))>0){total+=n;if(total>OfficialOtaCaptureCore.MAX_BYTES)throw new IOException("oversize");z.write(buf,0,n);}
   }
   z.closeEntry();
  }
  z.finish(); // finish only — closing would close MediaStore stream underneath DigestOutputStream
  return total;
 }

 private static String hex(byte[] h){StringBuilder b=new StringBuilder(h.length*2);for(byte v:h)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}
 private static String safe(Exception e){String m=e.getMessage();return m==null||m.isEmpty()?e.getClass().getSimpleName():m.length()>160?m.substring(0,160):m;}
}
