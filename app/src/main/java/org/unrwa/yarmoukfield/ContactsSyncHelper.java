package org.unrwa.yarmoukfield;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;

import java.util.ArrayList;

/** Optional, purely additive phone-contact sync for beneficiaries — never required for any other feature to
 * work. Writes a local (no-account) contact named "[المشروع] | [القائمة] | [الاسم]" per beneficiary phone
 * number. Always looks up an existing contact by phone number first and UPDATES its name instead of creating
 * a duplicate — re-syncing the same beneficiary (or the same list) repeatedly must never multiply contacts. */
public final class ContactsSyncHelper {
    private ContactsSyncHelper() {}
    private static final String DIAG_TAG = "YarmoukDiag";

    public static final class SyncResult {
        public int created, updated, skippedNoPhone, failed;
        /** Exception class + message only — never the beneficiary name or phone number. Safe to show in a toast/log. */
        public String lastError = "";
        void add(SyncResult other) {
            created += other.created; updated += other.updated; skippedNoPhone += other.skippedNoPhone; failed += other.failed;
            if (!other.lastError.isEmpty()) lastError = other.lastError;
        }
    }

    /** F6: short, dialer-friendly contact name «<اسم مختصر> ق<رقم القائمة> مستفيد» (or «<اسم مختصر> مستفيد» when the
     * list number is unknown). NEVER contains the registration number or the full address. */
    static String displayName(String batch, String name) {
        String sn = shortName(name);
        String ln = listNumber(batch);
        return ln.isEmpty() ? (sn + " مستفيد") : (sn + " ق" + ln + " مستفيد");
    }
    /** A safe short form of a long Arabic name: the full name if ≤2 words, else «الاسم الأول + اسم العائلة». */
    static String shortName(String name) {
        if (name == null) return "مستفيد";
        String n = name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) return "مستفيد";
        String[] p = n.split(" ");
        if (p.length <= 2) return n;
        return p[0] + " " + p[p.length - 1];
    }
    /** The list number extracted from a batch tag like «قائمة 13» → «13» (empty when there is no number). */
    static String listNumber(String batch) {
        if (batch == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]{1,4})").matcher(batch);
        return m.find() ? m.group(1) : "";
    }
    /** The extra beneficiary detail for the contact's NOTE field — kept OUT of the contact name. */
    static String contactNote(String project, String batch, String registration, String sector, String shortAddress, String statusLabel) {
        StringBuilder sb = new StringBuilder("مستفيد — مشروع المأوى في الأونروا");
        if (project != null && !project.trim().isEmpty()) sb.append(" (").append(project.trim()).append(")");
        if (batch != null && !batch.trim().isEmpty()) sb.append("\nالقائمة: ").append(batch.trim());
        if (registration != null && !registration.trim().isEmpty()) sb.append("\nرقم التسجيل: ").append(registration.trim());
        if (sector != null && !sector.trim().isEmpty()) sb.append("\nالقطاع: ").append(sector.trim());
        if (shortAddress != null && !shortAddress.trim().isEmpty()) sb.append("\nالعنوان: ").append(shortAddress.trim());
        if (statusLabel != null && !statusLabel.trim().isEmpty()) sb.append("\nحالة المتابعة: ").append(statusLabel.trim());
        return sb.toString();
    }

    /** Finds an existing LOCAL contact's raw_contact_id by phone number via PhoneLookup (which normalizes
     * number variants for matching), or -1 if this number isn't in the address book yet. */
    private static long findExistingRawContactId(ContentResolver resolver, String phone) {
        if (phone == null || phone.trim().isEmpty()) return -1;
        Uri lookupUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone.trim()));
        try (Cursor c = resolver.query(lookupUri, new String[]{ContactsContract.PhoneLookup._ID}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                long contactId = c.getLong(0);
                try (Cursor rc = resolver.query(ContactsContract.RawContacts.CONTENT_URI, new String[]{ContactsContract.RawContacts._ID},
                        ContactsContract.RawContacts.CONTACT_ID + "=?", new String[]{String.valueOf(contactId)}, null)) {
                    if (rc != null && rc.moveToFirst()) return rc.getLong(0);
                }
            }
        }
        return -1;
    }

    /** Syncs one beneficiary. Skips (skippedNoPhone) if there is no phone number at all — nothing to match or
     * create a contact from. Caller must already hold READ_CONTACTS+WRITE_CONTACTS. */
    public static SyncResult syncOne(Context context, String project, String batch, String name, String phone1, String phone2,
                                     String registration, String sector, String shortAddress, String statusLabel) {
        SyncResult result = new SyncResult();
        if ((phone1 == null || phone1.trim().isEmpty()) && (phone2 == null || phone2.trim().isEmpty())) {
            result.skippedNoPhone = 1; return result;
        }
        ContentResolver resolver = context.getContentResolver();
        String primaryPhone = phone1 != null && !phone1.trim().isEmpty() ? phone1.trim() : phone2.trim();
        String fullName = displayName(batch, name);
        long existingRawId = findExistingRawContactId(resolver, primaryPhone);
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        try {
            if (existingRawId < 0) {
                // Deliberately NOT forcing ACCOUNT_TYPE/ACCOUNT_NAME to null here. That was the standard
                // pre-Android-14 idiom for a "local, no-sync" contact, but confirmed via real-device logcat
                // (adb logcat -s YarmoukDiag) this now throws IllegalArgumentException("Cannot add contacts
                // to local or SIM accounts when default account is set to cloud") on Android 14+ once the
                // device's system-level "default account for new contacts" is set to a cloud account —
                // exactly this phone's configuration. Omitting the columns entirely (rather than setting them
                // to null) lets ContactsProvider2 resolve the account itself per the new default-account
                // system, which is the documented-working pattern for this exact platform restriction.
                // NOTE: .withValues(new ContentValues()) is required here — without it, .build() leaves the
                // operation's internal ContentValues as a true Java null (only created lazily by the first
                // withValue() call), and confirmed via real-device logcat this crashes a SECOND real device
                // with NullPointerException("Attempt to invoke virtual method ...ContentValues.keySet() on a
                // null object reference") inside ContentProviderOperation's own applyBatch marshalling. An
                // explicit empty ContentValues has zero account keys (same omission as before) but is never null.
                ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).withValues(new ContentValues()).build());
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, fullName).build());
                if (phone1 != null && !phone1.trim().isEmpty()) {
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone1.trim())
                            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build());
                }
                if (phone2 != null && !phone2.trim().isEmpty()) {
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone2.trim())
                            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_OTHER).build());
                }
                // F6: the beneficiary detail lives in the NOTE (never in the name). Only on CREATE — an existing
                // contact matched by phone keeps its own note untouched.
                String note = contactNote(project, batch, registration, sector, shortAddress, statusLabel);
                if (!note.isEmpty()) {
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Note.NOTE, note).build());
                }
                resolver.applyBatch(ContactsContract.AUTHORITY, ops);
                result.created = 1;
            } else {
                // Existing contact found by phone number — update its name to keep the [project]|[list]|[name]
                // tag current instead of inserting a second contact for the same person.
                ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                        .withSelection(ContactsContract.Data.RAW_CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                                new String[]{String.valueOf(existingRawId), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE})
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, fullName).build());
                resolver.applyBatch(ContactsContract.AUTHORITY, ops);
                result.updated = 1;
            }
        } catch (Exception error) {
            // A single contact-provider failure must not abort a batch sync or crash the screen, but it must
            // never be silent either — this used to be swallowed entirely, so a real failure (e.g. an OEM
            // contacts provider rejecting a null-account RawContacts insert) looked identical to "nothing to
            // do" and produced a generic, undiagnosable "تعذرت المزامنة" toast with no way to tell why.
            result.failed = 1;
            result.lastError = error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
            Log.w(DIAG_TAG, "contacts_sync_failed raw_contact_existing=" + (existingRawId >= 0) + " error=" + result.lastError, error);
        }
        return result;
    }
}
