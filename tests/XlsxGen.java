import org.unrwa.yarmoukfield.BeneficiaryXlsx;
import java.io.*;
import java.util.*;

/** Reads the bundled template, injects a few sample quantities + a name, writes the filled workbook to arg[1]. */
public class XlsxGen {
    public static void main(String[] a) throws Exception {
        String template = a[0], out = a[1];
        Map<Integer, Double> q = new LinkedHashMap<>();
        q.put(1, 5.0);    // بيتون مسلح  -> F7
        q.put(9, 20.0);   // جلي بلاط    -> F15
        q.put(18, 2.0);   // صيانة (ألمنيوم?) -> F24
        q.put(37, 3.0);   // دهان        -> F43
        try (InputStream in = new FileInputStream(template); OutputStream os = new FileOutputStream(out)) {
            BeneficiaryXlsx.write(in, q, "سارة إبراهيم الناطور", os);
        }
        System.out.println("WROTE " + out);
    }
}
