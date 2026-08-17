package android.net;
public class Uri {
    private final String path;
    private Uri(String p){ path = p; }
    public static Uri parse(String s){ return new Uri(s); }
    public static Uri fromFile(java.io.File f){ return new Uri(f.getAbsolutePath()); }
    public String getPath(){ return path; }
    @Override public String toString(){ return path; }
}
