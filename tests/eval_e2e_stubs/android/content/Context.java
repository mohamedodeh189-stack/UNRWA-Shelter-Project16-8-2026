package android.content;
import java.io.File;
public class Context {
    private final File filesDir, cacheDir;
    private final ContentResolver resolver = new ContentResolver();
    public Context(File filesDir, File cacheDir){ this.filesDir = filesDir; this.cacheDir = cacheDir; }
    public File getFilesDir(){ return filesDir; }
    public File getCacheDir(){ return cacheDir; }
    public ContentResolver getContentResolver(){ return resolver; }
}
