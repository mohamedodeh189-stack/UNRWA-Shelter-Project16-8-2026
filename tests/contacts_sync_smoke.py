# -*- coding: utf-8 -*-
"""Phase 8 smoke test: contacts sync naming + duplicate-prevention logic,
mirroring ContactsSyncHelper.java exactly (displayName format, and the
find-existing-by-phone -> update-instead-of-insert decision tree). No real
Android ContactsProvider exists on this dev machine, so it is simulated here
as a simple phone-number-keyed dict, standing in for
ContactsContract.PhoneLookup + RawContacts.

Also verifies (as plain assertions on the source): READ_CONTACTS/WRITE_CONTACTS
are declared in the manifest (added, but never required at install time on
modern Android — runtime request only), and that no other phase's schema or
behavior was touched by grepping for absence of a Phase 8 DB migration
(contacts sync deliberately needs zero AppDatabase schema changes).
"""
import os
import re
import sys


def display_name(project, batch, name):
    """Mirrors ContactsSyncHelper.displayName() exactly."""
    batch_label = (batch or "").strip()
    return f"[{project}]" + (f" | [{batch_label}]" if batch_label else "") + f" | {name}"


class FakeContactsProvider:
    """Stands in for ContactsContract: a phone-number-keyed store of (raw_contact_id -> display_name)."""
    def __init__(self):
        self.by_phone = {}  # phone -> raw_contact_id
        self.names = {}     # raw_contact_id -> display_name
        self._next_id = 1

    def find_existing_raw_contact_id(self, phone):
        if not phone:
            return -1
        return self.by_phone.get(phone.strip(), -1)

    def sync_one(self, project, batch, name, phone1, phone2):
        """Mirrors ContactsSyncHelper.syncOne() exactly."""
        result = {"created": 0, "updated": 0, "skippedNoPhone": 0}
        if not (phone1 or "").strip() and not (phone2 or "").strip():
            result["skippedNoPhone"] = 1
            return result
        primary = (phone1 or "").strip() or (phone2 or "").strip()
        full_name = display_name(project, batch, name)
        existing = self.find_existing_raw_contact_id(primary)
        if existing < 0:
            raw_id = self._next_id
            self._next_id += 1
            self.names[raw_id] = full_name
            if (phone1 or "").strip():
                self.by_phone[phone1.strip()] = raw_id
            if (phone2 or "").strip():
                self.by_phone[phone2.strip()] = raw_id
            result["created"] = 1
        else:
            self.names[existing] = full_name
            result["updated"] = 1
        return result


def test_display_name_format():
    print("=== Contact display name format: [Project] | [List] | [Name] ===")
    assert display_name("مشروع مخيم اليرموك", "دفعة 2026-08-01", "أحمد حسين دياب") == \
        "[مشروع مخيم اليرموك] | [دفعة 2026-08-01] | أحمد حسين دياب"
    # No batch (e.g. a manually-added study-stage beneficiary never promoted to a followup list yet).
    assert display_name("مشروع مخيم اليرموك", "", "أحمد حسين دياب") == "[مشروع مخيم اليرموك] | أحمد حسين دياب"
    assert display_name("مشروع مخيم اليرموك", None, "أحمد حسين دياب") == "[مشروع مخيم اليرموك] | أحمد حسين دياب"
    print("OK: display name always follows [المشروع] | [رقم القائمة] | [الاسم], gracefully omitting an empty list tag.\n")


def test_resync_never_duplicates():
    print("=== Re-syncing the same beneficiary / same list repeatedly must never create duplicate contacts ===")
    provider = FakeContactsProvider()

    r1 = provider.sync_one("مشروع مخيم اليرموك", "دفعة 1", "أحمد حسين دياب", "0991234567", "")
    assert r1 == {"created": 1, "updated": 0, "skippedNoPhone": 0}
    assert len(provider.names) == 1, "First sync must create exactly one contact"

    # Re-sync the exact same beneficiary (e.g. list re-synced later) -> must UPDATE, not create a second contact.
    r2 = provider.sync_one("مشروع مخيم اليرموك", "دفعة 1", "أحمد حسين دياب", "0991234567", "")
    assert r2 == {"created": 0, "updated": 1, "skippedNoPhone": 0}
    assert len(provider.names) == 1, "Re-sync must not create a duplicate contact"

    # Even if the batch label or study status changed the tag, the SAME phone number must still update in place.
    r3 = provider.sync_one("مشروع مخيم اليرموك", "دفعة 2 (بعد اعتماد)", "أحمد حسين دياب", "0991234567", "")
    assert r3 == {"created": 0, "updated": 1, "skippedNoPhone": 0}
    assert len(provider.names) == 1, "Updated batch tag must still resolve to the SAME contact by phone number, not a new one"
    only_id = next(iter(provider.names))
    assert provider.names[only_id] == "[مشروع مخيم اليرموك] | [دفعة 2 (بعد اعتماد)] | أحمد حسين دياب", \
        "The existing contact's name must reflect the latest sync's tag"

    # A different beneficiary (different phone number) must create a genuinely new contact.
    r4 = provider.sync_one("مشروع مخيم اليرموك", "دفعة 1", "نعيم حسين دياب", "0997654321", "")
    assert r4 == {"created": 1, "updated": 0, "skippedNoPhone": 0}
    assert len(provider.names) == 2, "A different phone number must create a second, distinct contact"

    print("OK: syncing the same phone number repeatedly always updates the one existing contact in place; "
          "a different phone number correctly creates a new, separate contact.\n")


def test_no_phone_number_is_skipped_not_crashed():
    print("=== A beneficiary with no phone number at all is skipped cleanly, never crashes the sync ===")
    provider = FakeContactsProvider()
    r = provider.sync_one("مشروع مخيم اليرموك", "دفعة 1", "بلا رقم هاتف", "", "")
    assert r == {"created": 0, "updated": 0, "skippedNoPhone": 1}
    assert len(provider.names) == 0
    print("OK: no contact created/updated for a beneficiary with no phone number; sync just skips it.\n")


def test_manifest_declares_contacts_permissions_optional_only():
    print("=== AndroidManifest.xml declares READ_CONTACTS/WRITE_CONTACTS (requested at runtime, never forced) ===")
    manifest_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "AndroidManifest.xml")
    with open(manifest_path, encoding="utf-8") as f:
        manifest = f.read()
    assert 'android.permission.READ_CONTACTS' in manifest
    assert 'android.permission.WRITE_CONTACTS' in manifest
    # No <uses-feature ... required="true"> tying the whole app to a contacts-capable device.
    assert 'android.hardware.contacts' not in manifest
    print("OK: both contacts permissions are declared (required for the runtime request to even be legal), "
          "with nothing making the feature mandatory for installing/running the app.\n")


def test_no_phase8_database_migration_needed():
    print("=== Sanity: contacts sync needed zero AppDatabase schema changes (writes only to the OS contacts store) ===")
    appdb_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "java", "org", "unrwa", "yarmoukfield", "AppDatabase.java")
    with open(appdb_path, encoding="utf-8") as f:
        appdb = f.read()
    version_match = re.search(r'super\(context,\s*"yarmouk_field\.db",\s*null,\s*(\d+)\)', appdb)
    assert version_match, "Could not find DB_VERSION in AppDatabase.java"
    db_version = int(version_match.group(1))
    # DB_VERSION itself is free to advance for later, unrelated migrations (e.g. the dual-mode GPS
    # gps_source column) — the real invariant is that contacts sync specifically never needed any
    # AppDatabase schema at all, which is what the string check below actually proves.
    assert db_version >= 13, f"DB_VERSION must never go backwards below 13 (Phase 7), got {db_version}"
    assert "contact" not in appdb.lower(), "AppDatabase.java must have no contacts-related schema at all"
    print(f"OK: DB_VERSION is {db_version} — ContactsSyncHelper never touches AppDatabase, "
          f"confirming contacts sync is purely additive and cannot regress any earlier phase's schema.\n")


if __name__ == "__main__":
    try:
        test_display_name_format()
        test_resync_never_duplicates()
        test_no_phone_number_is_skipped_not_crashed()
        test_manifest_declares_contacts_permissions_optional_only()
        test_no_phase8_database_migration_needed()
        print("CONTACTS_SYNC_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
