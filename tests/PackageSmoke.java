import org.unrwa.yarmoukfield.BeneficiaryPackage;
import java.io.File;

/** P2.1: folder naming (name - registration, sanitized) and no-overwrite collision (_2/_3). */
public class PackageSmoke {
    static int fail = 0;
    static void ok(boolean c, String m) { System.out.println((c ? "OK: " : "FAIL: ") + m); if (!c) fail++; }

    public static void main(String[] a) throws Exception {
        ok(BeneficiaryPackage.baseName("سارة إبراهيم الناطور", "2-00585283").equals("سارة إبراهيم الناطور - 2-00585283"),
                "name - registration: " + BeneficiaryPackage.baseName("سارة إبراهيم الناطور", "2-00585283"));
        ok(BeneficiaryPackage.baseName("AAA", "").equals("AAA"), "no registration → name only");
        ok(BeneficiaryPackage.baseName("a/b:c*?", "1").equals("a b c - 1"), "illegal chars sanitized: " + BeneficiaryPackage.baseName("a/b:c*?", "1"));

        File tmp = new File(System.getProperty("java.io.tmpdir"), "pkgtest_" + System.nanoTime());
        tmp.mkdirs();
        File d1 = BeneficiaryPackage.createFolder(tmp, "سارة إبراهيم الناطور", "2-00585283");
        File d2 = BeneficiaryPackage.createFolder(tmp, "سارة إبراهيم الناطور", "2-00585283");
        File d3 = BeneficiaryPackage.createFolder(tmp, "سارة إبراهيم الناطور", "2-00585283");
        ok(d1.getName().equals("سارة إبراهيم الناطور - 2-00585283"), "1st folder = base name");
        ok(d2.getName().endsWith("_2"), "2nd run → _2, got " + d2.getName());
        ok(d3.getName().endsWith("_3"), "3rd run → _3, got " + d3.getName());
        ok(d1.isDirectory() && d2.isDirectory() && d3.isDirectory(), "all three exist as directories");
        ok(!d1.getName().equals(d2.getName()) && !d2.getName().equals(d3.getName()), "no overwrite — three distinct folders");

        System.out.println(fail == 0 ? "PACKAGE_SMOKE_OK" : "PACKAGE_SMOKE_FAILED:" + fail);
        if (fail > 0) System.exit(1);
    }
}
