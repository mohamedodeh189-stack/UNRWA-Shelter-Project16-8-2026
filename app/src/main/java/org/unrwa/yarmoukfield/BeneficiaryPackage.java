package org.unrwa.yarmoukfield;

import java.io.File;
import java.io.IOException;

/** P2.1 — the beneficiary delivery folder. Right now it only creates the top folder «الاسم - رقم التسجيل»
 * (later stages drop the quantities file, CAD/PDF, photos and docs inside). Two rules the field workflow needs:
 * the folder is named after the person (never a code), and an export NEVER overwrites an existing folder — a
 * repeat run makes a fresh «…_2», «…_3» so an earlier delivery is never silently replaced.
 *
 * The naming logic is pure Java (no Android) so it is unit-testable on a plain JDK; the app calls
 * {@link #createFolder} with a real destination directory. */
public final class BeneficiaryPackage {
    private BeneficiaryPackage() {}

    /** Folder base name: «name - registration», or just «name» when there is no registration number. Sanitized
     * so it is a legal folder name on Windows/Android (illegal characters → spaces, collapsed). */
    public static String baseName(String name, String registration) {
        String n = sanitize(name);
        String r = sanitize(registration);
        if (n.isEmpty()) n = "مستفيد";
        return r.isEmpty() ? n : n + " - " + r;
    }

    /** Create the beneficiary folder under {@code parent} without ever overwriting: if «base» already exists,
     * use «base_2», then «base_3» … Returns the freshly-created directory. */
    public static File createFolder(File parent, String name, String registration) throws IOException {
        if (parent == null) throw new IOException("لا يوجد مجلد وجهة");
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("تعذّر إنشاء مجلد الوجهة");
        String base = baseName(name, registration);
        File dir = new File(parent, base);
        for (int n = 2; dir.exists(); n++) dir = new File(parent, base + "_" + n);
        if (!dir.mkdir()) throw new IOException("تعذّر إنشاء مجلد المستفيد");
        return dir;
    }

    /** Strip characters illegal in Windows/Android folder names and collapse whitespace. */
    static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
    }
}
