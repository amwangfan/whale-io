package android.content.res;
import java.io.ByteArrayInputStream;import java.io.InputStream;
public final class AssetManager {
 public String[] list(String path){return new String[]{"official-ota-guarded.txt"};}
 public InputStream open(String path){return new ByteArrayInputStream(new byte[0]);}
}
