package com.turboio.addon;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** The one reviewed candidate, TVP2C (TVP2 ported to stock Strix OS 1.0.5.12), for hosts 1.0.10 / vc258.
 * Not an arbitrary firmware loader. No extracted paths. */
final class OtaPackage {
 static final String VERSION="Strix OS 1.0.6.11", NAME="TVP2C-611 · 自定义运行时（1.0.6.11 移植）";
 /** One-time authorization phrase typed by the user; also the feed/gate candidate token. */
 static final String TOKEN="TVP2C-611", CODE="TVP2C-611";
 /** Glasses versions this candidate may be offered over (当前眼镜在 1.0.5.12 fix1). */
 static final List<String> BASES=Arrays.asList("Strix OS 1.0.6.11");
 static final List<String> BASE_CODES=Arrays.asList("01.00.06.0011");
 static final String FEED_VERSION="0100060011", DIRECTORY="Strix_OS_1.0.6.11";
 /** What the user inspects on the lens before releasing the result guard. */
 static final String MARKER="显示 Turbo IO 三排菜单";
 static final int FEED_CODE=100060011;
 static final int ZIP_SIZE=9728363, AP_SIZE=8493896;
 static final String ZIP_SHA="5f2cdc93782b4b810dcccaecd193a1ba13e0acdcbf2fe95333e74f423c428014";
 static final String AP_SHA="7cb1dde50c7e17adcd5d95907842f2aac341528628a69249d56025b6e252a64f";
 // Restoring the AP row to the stock values must reproduce the stock 1.0.6.11 manifest exactly.
 private static final int STOCK_AP_SIZE=7967244;
 private static final String STOCK_AP_MD5="94d28a6e1bb4df87e3e0cefc3549ee82";
 private static final String STOCK_MANIFEST="829f9a4d7bd0bc654ce2f93c59ff04591950da0dc1034ac003dd76365a26f4af";
 // Non-AP pins match the official Strix OS 1.0.6.11 ZIP byte for byte.
  private static final String[] PINS={
    "OtaFileInfo.json 2662 e0615172dbb58742b0edf2ef1598184132b89a39e0d1b29dcaadfec76d6fa76c",
    "amap_rtos_sdk 775548 a14c47776864b5786bf61e4c37e159259c8d7c9b94ad6664e4526eadc445574a",
    "bt_controller.bin 632384 dd935af210755d94c43995c62d9ec03e469157cd14965b1642c83d2f1daa440b",
    "cb_fw_venus.bin 114592 8799f12071bdac082218e3601c24ab0989275fc2f7db74e37b8314a225c2cf7d",
    "fac_test_img.bin 1821552 18eb0901c3e0daeae48dd5f015252b6931ea7368f0160c2c985c4e74296d17eb",
    "images.bin 390937 404ae1d33592db7592467b24bc29c1c783260fb7cf53f4be22838097f38fa501",
    "lotties.bin 661305 a8b5fa78e57775a025d90aa94d41245495a555c7ca4399b226b515bc03997162",
    "nuttx_ap.bin 8493896 7cb1dde50c7e17adcd5d95907842f2aac341528628a69249d56025b6e252a64f",
    "nuttx_apc1.bin 1442112 c24794d051df5803152fac770051374aff4bd34f6d41ef8ab39853e7b4d05ba9",
    "nuttx_audio.bin 1461920 549a72c032595e3829da7d63b7e1fd13cceda1a55dfefa7a7cad02db5a9b42ab",
    "nuttx_bth.bin 1250856 1fd910ea03124cd26d010c3ea3ef6a1b68dc61bd6283dcd07d2ad744d28759ec",
    "nuttx_ota.bin 536384 016cdd0ba9424512c9669d229b7c81d94eebdb24e15ba3c1c7b461ca32f2bc1a",
    "ota_installer_progress.bin 5616 6fd65d296a667e1a7d94ec129adfd26458a4008ef403b331f855192c36eb3e3f",
    "pil_algo_up_demo_nand.dll 504388 e266726fe055ac6ec6f785a9440e8da7421402f2b4d3d7889039ad9b94e119c2",
    "pil_algo_vad_demo.dll 433900 0153a5a8e6b69ca0b9dd4cc742d45a758351d8209e604f38dbfe046c31f29f01",
    "pil_algo_wakeup_dll_nand.dll 287560 d1594cb8658b228e52c2606d02f1eab696d35a11ed42ddcb725e76a745f5550f",
    "smf.json 23556 43998e7acf06c8f9bc39635655d79120b7061a46f7b23f791f8699536f81836d"};















 static final int PIN_COUNT=PINS.length;
 private final Map<String,byte[]> members;
 private OtaPackage(Map<String,byte[]> members){this.members=members;}
 static String hash(String algorithm,byte[] bytes){try{byte[] h=MessageDigest.getInstance(algorithm).digest(bytes);StringBuilder out=new StringBuilder();for(byte v:h)out.append(String.format(Locale.ROOT,"%02x",v&255));return out.toString();}catch(Exception e){throw new IllegalStateException("digest unavailable");}}
 static OtaPackage verify(byte[] zip)throws IOException{
  if(zip==null||zip.length!=ZIP_SIZE||!ZIP_SHA.equals(hash("SHA-256",zip)))throw new IOException("仅接受已审核 "+CODE+" 原样 ZIP，大小或 SHA-256 不符");
  Map<String,String[]> pins=new HashMap<>();for(String line:PINS){String[] p=line.split(" ");pins.put(p[0],p);}
  Map<String,byte[]> files=new HashMap<>();int total=0;
  try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(zip))){ZipEntry e;while((e=in.getNextEntry())!=null){String[] p=pins.get(e.getName());if(p==null||e.isDirectory()||files.containsKey(e.getName()))throw new IOException("ZIP member");int size=Integer.parseInt(p[1]);byte[] data=new byte[size];int at=0,n;while(at<size&&(n=in.read(data,at,size-at))!=-1)at+=n;if(at!=size||in.read()!=-1||!p[2].equals(hash("SHA-256",data)))throw new IOException("member size/hash");total+=size;if(total>19000000)throw new IOException("expanded budget");files.put(e.getName(),data);in.closeEntry();}}
  if(files.size()!=PINS.length)throw new IOException("missing member");
  try{List<Object> rows=BoundedJson.list(BoundedJson.read(files.get("OtaFileInfo.json")));if(rows.size()!=PINS.length-1)throw new IllegalArgumentException();Set<String> found=new HashSet<>();
   for(Object value:rows){Map<String,Object> row=BoundedJson.map(value);BoundedJson.keys(row,"Name","Size","Md5","BurnMode","BurnAddr","BurnPath");String name=BoundedJson.text(row.get("Name"),64);byte[] bytes=files.get(name);if(bytes==null||name.equals("OtaFileInfo.json")||!found.add(name)||BoundedJson.number(row.get("Size"),1,AP_SIZE)!=bytes.length||!hash("MD5",bytes).equals(row.get("Md5")))throw new IllegalArgumentException();if(name.equals("nuttx_ap.bin")){row.put("Size",STOCK_AP_SIZE);row.put("Md5",STOCK_AP_MD5);}}
   // Restoring just AP size/MD5 must reproduce the original manifest exactly,
   // including all original row order, burn paths, addresses and burn modes.
   if(!STOCK_MANIFEST.equals(hash("SHA-256",BoundedJson.canonical(rows))))throw new IllegalArgumentException();
  }catch(RuntimeException e){throw new IOException("manifest/AP-only mismatch");}
  return new OtaPackage(files);
 }
 Object manifest(){return BoundedJson.read(members.get("OtaFileInfo.json"));}
 // Read-only preparation evidence, NOT a transfer-session or installation claim.
 static void verifyDirectory(java.nio.file.Path directory)throws IOException{
  freezeDirectory(directory);
 }
 // Snapshot checked bytes into private memory; outgoing official slices must
 // equal this snapshot even if the vendor's directory is replaced afterward.
 static OtaPackage freezeDirectory(java.nio.file.Path directory)throws IOException{
  if(java.nio.file.Files.isSymbolicLink(directory)||!java.nio.file.Files.isDirectory(directory,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IOException("directory unavailable");
  Set<String> expected=new HashSet<>();for(String line:PINS)expected.add(line.split(" ")[0]);
  try(java.nio.file.DirectoryStream<java.nio.file.Path> list=java.nio.file.Files.newDirectoryStream(directory)){
   for(java.nio.file.Path p:list)if(!expected.remove(p.getFileName().toString()))throw new IOException("unexpected member");
  }if(!expected.isEmpty())throw new IOException("missing member");
  Map<String,byte[]> frozen=new HashMap<>();
  for(String line:PINS){String[] pin=line.split(" ");java.nio.file.Path p=directory.resolve(pin[0]);
   java.nio.file.attribute.BasicFileAttributes before=java.nio.file.Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
   int size=Integer.parseInt(pin[1]);if(!before.isRegularFile()||before.size()!=size)throw new IOException("member type/size");
   byte[] bytes=new byte[size];java.nio.ByteBuffer target=java.nio.ByteBuffer.wrap(bytes);
   Set<java.nio.file.OpenOption> options=new HashSet<>();options.add(java.nio.file.StandardOpenOption.READ);options.add(java.nio.file.LinkOption.NOFOLLOW_LINKS);
   try(java.nio.channels.SeekableByteChannel in=java.nio.file.Files.newByteChannel(p,options)){
    while(target.hasRemaining())if(in.read(target)<0)throw new IOException("short file");
    if(in.read(java.nio.ByteBuffer.allocate(1))!=-1)throw new IOException("file grew");
   }
   java.nio.file.attribute.BasicFileAttributes after=java.nio.file.Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
   if(!java.util.Objects.equals(before.fileKey(),after.fileKey())||!before.lastModifiedTime().equals(after.lastModifiedTime())||after.size()!=size||!pin[2].equals(hash("SHA-256",bytes)))throw new IOException("member changed/hash");
   frozen.put(pin[0],bytes);
  }
  return new OtaPackage(frozen);
 }
 int size(String name){byte[] b=members.get(name);if(b==null||name.equals("OtaFileInfo.json"))throw new IllegalArgumentException("payload not allowed");return b.length;}
 byte[] slice(String name,int start,int size){int length=size(name);if(start<0||size<1||size>51200||start>length-size)throw new IllegalArgumentException("slice bounds");return Arrays.copyOfRange(members.get(name),start,start+size);}
}
