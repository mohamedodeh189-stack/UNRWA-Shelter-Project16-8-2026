package android.content;
import android.net.Uri;
import java.io.*;
public class ContentResolver {
    public InputStream openInputStream(Uri uri) throws FileNotFoundException {
        return new FileInputStream(uri.getPath());
    }
}
