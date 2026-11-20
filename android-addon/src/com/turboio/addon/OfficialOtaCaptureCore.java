package com.turboio.addon;
import java.io.*;
import java.util.*;
import java.util.regex.*;

/** Pure helpers for OfficialOtaCapture: naming, size gate, conservative path pick (no MediaStore / UI). */
final class OfficialOtaCaptureCore {
 static final long MAX_BYTES=512L*1024*1024;
 private static final Pattern STRIX_VER=Pattern.compile("(?i)strix[_\\s-]*os[_\\s-]*([0-9]+(?:\\.[0-9]+){1,4})");
 private static final Pattern ANY_VER=Pattern.compile("([0-9]+(?:\\.[0-9]+){2,4})");
 private OfficialOtaCaptureCore(){}

 static boolean acceptSize(long bytes){return bytes>0&&bytes<=MAX_BYTES;}

 static boolean looksLikeOtaName(String name){
  if(name==null||name.isEmpty())return false;
  String n=name.toLowerCase(Locale.ROOT);
  if(n.startsWith("strix")||n.contains("strix_os")||n.contains("strix-os")||n.contains("strix os"))return true;
  return n.contains("ota")&&(n.endsWith(".zip")||n.indexOf('.')<0);
 }

 static String displayName(String sourceName,long nowMs){
  String base=sourceName==null?"":sourceName;
  if(base.toLowerCase(Locale.ROOT).endsWith(".zip"))base=base.substring(0,base.length()-4);
  Matcher m=STRIX_VER.matcher(base);
  if(m.find())return "StrixOS-"+m.group(1)+"-official.zip";
  m=ANY_VER.matcher(base);
  if(m.find()&&looksLikeOtaName(base))return "StrixOS-"+m.group(1)+"-official.zip";
  return "StrixOS-export-"+nowMs+".zip";
 }

 static File pickSource(List<File> candidates){
  if(candidates==null||candidates.isEmpty())return null;
  List<File> list=new ArrayList<>(candidates);
  Collections.sort(list,Comparator.comparing(File::getAbsolutePath));
  File bestDir=null,bestZip=null;
  for(File f:list){
   if(f==null||!f.exists())continue;
   if(f.isDirectory()&&looksLikeOtaName(f.getName())){
    File only=uniqueZipInside(f);if(only!=null)return only;
    if(bestDir==null)bestDir=f;
   }else if(f.isFile()&&f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")&&looksLikeOtaName(f.getName())){
    if(bestZip==null)bestZip=f;
   }
  }
  if(bestDir!=null)return bestDir;
  if(bestZip!=null)return bestZip;
  for(File f:list){if(f.isFile()&&f.getName().toLowerCase(Locale.ROOT).endsWith(".zip"))return f;if(f.isDirectory())return f;}
  return list.get(0);
 }

 static File uniqueZipInside(File dir){
  File[] kids=dir.listFiles();if(kids==null)return null;
  File zip=null;int n=0;
  for(File k:kids)if(k.isFile()&&k.getName().toLowerCase(Locale.ROOT).endsWith(".zip")){n++;zip=k;}
  return n==1?zip:null;
 }

 static long estimateBytes(File src)throws IOException{
  if(src==null)throw new IOException("missing");
  if(src.isFile())return src.length();
  if(!src.isDirectory())throw new IOException("not a file/dir");
  File[] kids=src.listFiles();if(kids==null)throw new IOException("unreadable directory");
  long total=0;
  for(File k:kids){
   if(k.isDirectory())throw new IOException("nested directory not packed");
   if(java.nio.file.Files.isSymbolicLink(k.toPath()))throw new IOException("symlink refused");
   total+=k.length();if(total>MAX_BYTES)return total;
  }
  return total;
 }

 static List<File> collectCandidates(File filesDir,File flutterOtaParent,File externalFiles,File cacheDir){
  List<File> out=new ArrayList<>();
  addOtaChildren(out,filesDir==null?null:new File(filesDir,"ota"));
  if(flutterOtaParent!=null)addOtaChildren(out,new File(flutterOtaParent,"app_flutter/ota"));
  if(filesDir!=null&&filesDir.getParentFile()!=null)addOtaChildren(out,new File(filesDir.getParentFile(),"app_flutter/ota"));
  if(externalFiles!=null){addOtaChildren(out,new File(externalFiles,"ota"));addShallowHits(out,externalFiles);}
  addShallowHits(out,cacheDir);addShallowHits(out,filesDir);
  Map<String,File> uniq=new LinkedHashMap<>();
  for(File f:out)if(f!=null)uniq.put(f.getAbsolutePath(),f);
  return new ArrayList<>(uniq.values());
 }

 private static void addOtaChildren(List<File> out,File otaRoot){
  if(otaRoot==null||!otaRoot.isDirectory())return;
  File[] kids=otaRoot.listFiles();if(kids==null)return;
  for(File k:kids){
   String n=k.getName();
   if(k.isDirectory()&&looksLikeOtaName(n))out.add(k);
   else if(k.isFile()&&n.toLowerCase(Locale.ROOT).endsWith(".zip")&&looksLikeOtaName(n))out.add(k);
  }
 }

 private static void addShallowHits(List<File> out,File dir){
  if(dir==null||!dir.isDirectory())return;
  File[] kids=dir.listFiles();if(kids==null)return;
  for(File k:kids){
   String n=k.getName(),low=n.toLowerCase(Locale.ROOT);
   if(k.isFile()&&low.endsWith(".zip")&&looksLikeOtaName(n))out.add(k);
   else if(k.isDirectory()&&looksLikeOtaName(n)&&low.contains("strix"))out.add(k);
  }
 }
}
