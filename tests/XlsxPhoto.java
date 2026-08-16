import org.unrwa.yarmoukfield.BeneficiaryXlsx;
import java.io.*;
import java.util.*;

/** Embeds two JPEGs into the «صور قبل الترميم» sheet and writes the workbook — validated with openpyxl afterwards. */
public class XlsxPhoto {
    public static void main(String[] a) throws Exception {
        byte[] img = java.nio.file.Files.readAllBytes(new File(a[0]).toPath());
        List<byte[]> photos = Arrays.asList(img, img);
        Map<Integer, Double> q = new LinkedHashMap<>();
        q.put(1, 12.5); q.put(6, 5.5); q.put(37, 6.0);
        try (InputStream tpl = new FileInputStream(a[1]); OutputStream out = new FileOutputStream(a[2])) {
            BeneficiaryXlsx.write(tpl, q, "سارة إبراهيم الناطور", photos, out);
        }
        System.out.println("WROTE " + a[2]);
    }
}
