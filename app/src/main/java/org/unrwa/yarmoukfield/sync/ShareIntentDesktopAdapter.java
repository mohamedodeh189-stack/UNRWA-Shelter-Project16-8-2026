package org.unrwa.yarmoukfield.sync;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** THIS IS A DELIBERATELY PROVISIONAL TRANSPORT, NOT THE FINAL DESIGN. It implements SyncTarget by writing
 * the JSON package to a cache file and handing it to Android's ACTION_SEND share sheet so the user can send
 * it to the Windows desktop app by whatever channel they have (USB file transfer app, email, chat, etc.) —
 * this app has no INTERNET permission and makes no network call of its own. A future CloudApiSyncTarget
 * implementing the exact same SyncTarget interface would need ZERO changes to StudyRevisionPackage,
 * SyncPackageBuilder, or any study/beneficiary logic — only this one class would be replaced.
 *
 * Honesty about what "success" means here: launching the share sheet only proves a package was PRODUCED and
 * OFFERED to some app — it can never prove Windows actually received it (Android does not reliably report
 * what happens after the user picks a target app). export() returning success=true means exactly that and
 * nothing more; the caller (MainActivity) is responsible for tracking sync_status as "exported" (an
 * attempt), never as a confirmed sync, and for leaving sync_status untouched entirely if the user cancels
 * the chooser before picking anything (detected via RESULT_CANCELED in onActivityResult) or if building the
 * package fails before the chooser is even shown. study_status is never written by this class at all. */
public final class ShareIntentDesktopAdapter implements SyncTarget {
    private final Activity activity;
    private final String project;
    private final String engineerName;
    private final int requestCode;

    public ShareIntentDesktopAdapter(Activity activity, String project, String engineerName, int requestCode) {
        this.activity = activity; this.project = project; this.engineerName = engineerName; this.requestCode = requestCode;
    }

    @Override public SyncResult export(List<StudyRevisionPackage> revisions) {
        SyncResult result = new SyncResult();
        if (revisions == null || revisions.isEmpty()) { result.success = false; result.message = "لا توجد بيانات لتصديرها"; return result; }
        try {
            JSONObject json = SyncPackageJson.toJson(revisions, project, engineerName);
            File dir = new File(activity.getCacheDir(), "sync_exports");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("تعذر إنشاء مجلد التصدير المؤقت");
            String fileName = "yarmouk_sync_" + System.currentTimeMillis() + ".json";
            File file = new File(dir, fileName);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            Uri uri = SyncFileProvider.uriFor(activity, file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/json");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "مزامنة مشروع " + project + " — " + revisions.size() + " سجل");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, "مشاركة حزمة المزامنة مع جهاز Windows");
            activity.startActivityForResult(chooser, requestCode);
            result.success = true;
            result.message = "تم إنشاء حزمة المزامنة (" + revisions.size() + " سجل) وعرضها للمشاركة";
        } catch (Exception error) {
            result.success = false;
            result.message = "تعذر تجهيز حزمة المزامنة: " + error.getMessage();
        }
        return result;
    }
}
