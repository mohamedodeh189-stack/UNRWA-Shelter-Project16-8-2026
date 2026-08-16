import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Exercises the same SAXParserFactory path used by the Android XLSX importer. */
public final class SpreadsheetXmlSmoke {
    private static XMLReader reader() throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newSAXParser().getXMLReader();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Pass one or more XLSX files");
        for (String arg : args) inspect(new File(arg));
    }

    private static void inspect(File file) throws Exception {
        if (!file.isFile()) throw new IllegalArgumentException("Missing: " + file);
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry shared = zip.getEntry("xl/sharedStrings.xml");
            if (shared != null) parse(zip.getInputStream(shared), new Counter());
            List<ZipEntry> sheets = new ArrayList<>();
            zip.stream().filter(e -> e.getName().matches("xl/worksheets/sheet\\d+\\.xml")).forEach(sheets::add);
            sheets.sort(Comparator.comparing(ZipEntry::getName));
            int rows = 0, cells = 0;
            for (ZipEntry sheet : sheets) {
                Counter counter = new Counter();
                parse(zip.getInputStream(sheet), counter);
                rows += counter.rows;
                cells += counter.cells;
            }
            if (sheets.isEmpty() || rows == 0) throw new IllegalStateException("No worksheet rows: " + file);
            System.out.println("XLSX_OK|" + file.getName() + "|sheets=" + sheets.size() + "|rows=" + rows + "|cells=" + cells);
        }
    }

    private static void parse(InputStream stream, Counter counter) throws Exception {
        XMLReader parser = reader();
        parser.setContentHandler(counter);
        try (InputStream input = stream) { parser.parse(new InputSource(input)); }
    }

    private static final class Counter extends DefaultHandler {
        int rows, cells;
        @Override public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if ("row".equals(qName)) rows++;
            if ("c".equals(qName)) cells++;
        }
    }
}
