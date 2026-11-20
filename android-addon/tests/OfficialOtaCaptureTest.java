package com.turboio.addon;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Path pick / naming / oversize rejection — no device I/O, no MediaStore. */
public final class OfficialOtaCaptureTest {
 static int n;static void ok(boolean b,String why){n++;if(!b)throw new AssertionError(why);}
 public static void main(String[] a)throws Exception{
  ok(OfficialOtaCaptureCore.acceptSize(1)&&OfficialOtaCaptureCore.acceptSize(OfficialOtaCaptureCore.MAX_BYTES),"bounds inclusive");
  ok(!OfficialOtaCaptureCore.acceptSize(0)&&!OfficialOtaCaptureCore.acceptSize(OfficialOtaCaptureCore.MAX_BYTES+1),"reject empty/oversize");
  ok(OfficialOtaCaptureCore.looksLikeOtaName("Strix_OS_1.0.6.11")&&OfficialOtaCaptureCore.looksLikeOtaName("strix-os-foo.zip"),"strix names");
  ok(OfficialOtaCaptureCore.looksLikeOtaName("ota")&&!OfficialOtaCaptureCore.looksLikeOtaName("photo.jpg"),"ota token vs unrelated");
  ok(OfficialOtaCaptureCore.displayName("Strix_OS_1.0.6.11",1).equals("StrixOS-1.0.6.11-official.zip"),"dir naming");
  ok(OfficialOtaCaptureCore.displayName("Strix_OS_1.0.6.11.zip",1).equals("StrixOS-1.0.6.11-official.zip"),"zip naming");
  ok(OfficialOtaCaptureCore.displayName("mystery",42).equals("StrixOS-export-42.zip"),"timestamp fallback");

  Path root=Files.createTempDirectory("turboio-ota-capture-");
  try{
   Path ota=root.resolve("ota");Files.createDirectories(ota);
   Path dir=ota.resolve("Strix_OS_1.0.6.11");Files.createDirectories(dir);
   Files.write(dir.resolve("OtaFileInfo.json"),"{}".getBytes("UTF-8"));
   Path zip=ota.resolve("Strix_OS_1.0.5.12.zip");Files.write(zip,new byte[64]);
   Path cache=root.resolve("cache");Files.createDirectories(cache);
   Files.write(cache.resolve("noise.bin"),new byte[4]);
   Files.write(cache.resolve("Strix_OS_1.0.7.0.zip"),new byte[32]);

   List<File> found=OfficialOtaCaptureCore.collectCandidates(root.toFile(),null,null,cache.toFile());
   ok(found.size()>=3,"scan finds dir+zips");
   File pick=OfficialOtaCaptureCore.pickSource(found);
   ok(pick!=null&&pick.getName().contains("Strix"),"pick prefers strix");

   Path packed=ota.resolve("Strix_OS_1.0.8.0");Files.createDirectories(packed);
   Path only=packed.resolve("payload.zip");Files.write(only,new byte[16]);
   File uniq=OfficialOtaCaptureCore.pickSource(Collections.singletonList(packed.toFile()));
   ok(uniq!=null&&uniq.getName().equals("payload.zip"),"unique zip inside dir");

   ok(OfficialOtaCaptureCore.estimateBytes(zip.toFile())==64,"file size");
   ok(OfficialOtaCaptureCore.estimateBytes(dir.toFile())==2,"dir size");
   ok(!OfficialOtaCaptureCore.acceptSize(OfficialOtaCaptureCore.MAX_BYTES+100),"reject huge");
  }finally{deleteTree(root);}
  System.out.println("OfficialOtaCaptureTest: "+n+" checks");
 }
 static void deleteTree(Path p)throws IOException{
  if(!Files.exists(p))return;
  if(Files.isDirectory(p))try(DirectoryStream<Path> ds=Files.newDirectoryStream(p)){for(Path c:ds)deleteTree(c);}
  Files.deleteIfExists(p);
 }
}
