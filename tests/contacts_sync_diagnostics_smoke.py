# -*- coding: utf-8 -*-
"""Fix-cycle regression test for the contacts-sync silent-failure bug: ContactsSyncHelper.syncOne() used
to swallow every ContentProvider exception with a bare `catch (Exception ignored) {}`, so a real failure
(e.g. an OEM contacts provider rejecting the insert) was indistinguishable from "nothing to do" — the app
could only show a generic, undiagnosable "تعذّرت المزامنة" toast. Mirrors the fixed SyncResult contract
(failed count + safe lastError string) and the MainActivity toast-building logic around it.
"""
import sys


class SyncResult:
    def __init__(self):
        self.created = 0
        self.updated = 0
        self.skipped_no_phone = 0
        self.failed = 0
        self.last_error = ""

    def add(self, other):
        self.created += other.created
        self.updated += other.updated
        self.skipped_no_phone += other.skipped_no_phone
        self.failed += other.failed
        if other.last_error:
            self.last_error = other.last_error


def sync_one(name, phone1, phone2, provider_raises=None):
    """Mirrors the FIXED ContactsSyncHelper.syncOne(): a provider exception is counted and its safe
    message captured, never silently dropped."""
    result = SyncResult()
    if not (phone1 or "").strip() and not (phone2 or "").strip():
        result.skipped_no_phone = 1
        return result
    if provider_raises is not None:
        # Mirrors: result.lastError = error.getClass().getSimpleName() + ": " + error.getMessage()
        result.failed = 1
        result.last_error = f"{type(provider_raises).__name__}: {provider_raises}"
        return result
    result.created = 1
    return result


def toast_for_single_sync(result):
    """Mirrors the fixed syncBeneficiaryContact() toast selection."""
    if result.created > 0:
        return "تمت إضافة جهة اتصال جديدة"
    if result.updated > 0:
        return "تم تحديث جهة الاتصال الموجودة (بلا تكرار)"
    if result.failed > 0:
        return f"تعذّرت المزامنة — السبب: {result.last_error}"
    return "تعذّرت المزامنة"


def test_provider_failure_is_never_silently_swallowed():
    print("=== A ContentProvider exception is counted as failed=1 with a safe error message, never dropped ===")
    result = sync_one("مستفيد", "0999888777", "", provider_raises=RuntimeError("insert rejected by provider"))
    assert result.failed == 1
    assert result.created == 0 and result.updated == 0
    assert "RuntimeError" in result.last_error and "insert rejected by provider" in result.last_error
    print(f"OK: failure surfaced explicitly: {result.last_error!r}\n")


def test_failure_message_never_contains_name_or_phone():
    print("=== The safe error message never embeds the beneficiary's name or phone number (PII) ===")
    name, phone = "اسم حقيقي حساس", "0991234567"
    result = sync_one(name, phone, "", provider_raises=RuntimeError("SecurityException: no default account"))
    assert name not in result.last_error
    assert phone not in result.last_error
    print("OK: diagnostic message is PII-free (class name + exception message only).\n")


def test_toast_shows_the_real_reason_instead_of_a_blank_generic_message():
    print("=== The user-facing toast surfaces the real reason instead of a bare, undiagnosable message ===")
    result = sync_one("م", "0999888777", "", provider_raises=IllegalStateException_like("account type null rejected"))
    toast = toast_for_single_sync(result)
    assert "السبب:" in toast and "account type null rejected" in toast
    print(f"OK: {toast!r}\n")


def IllegalStateException_like(message):
    class IllegalStateException(Exception):
        pass
    return IllegalStateException(message)


def test_success_paths_are_unaffected_by_the_diagnostics_addition():
    print("=== Normal create/update/no-phone outcomes are unchanged by the failure-tracking addition ===")
    created = sync_one("أ", "0900000001", "")
    assert created.created == 1 and created.failed == 0 and created.last_error == ""
    no_phone = sync_one("ب", "", "")
    assert no_phone.skipped_no_phone == 1 and no_phone.failed == 0
    print("OK: create and skipped-no-phone results are untouched by the fix.\n")


def test_batch_sync_aggregates_failed_count_and_keeps_last_error():
    print("=== Batch sync (syncBatchContacts): failures are counted across the whole batch, not just the last one ===")
    total = SyncResult()
    total.add(sync_one("أ", "0900000001", ""))
    total.add(sync_one("ب", "0900000002", "", provider_raises=RuntimeError("first failure")))
    total.add(sync_one("ج", "0900000003", "", provider_raises=RuntimeError("second failure")))
    assert total.created == 1 and total.failed == 2
    assert total.last_error == "RuntimeError: second failure", "should reflect the most recent failure for the toast"
    print(f"OK: batch total created={total.created} failed={total.failed} last_error={total.last_error!r}\n")


if __name__ == "__main__":
    try:
        test_provider_failure_is_never_silently_swallowed()
        test_failure_message_never_contains_name_or_phone()
        test_toast_shows_the_real_reason_instead_of_a_blank_generic_message()
        test_success_paths_are_unaffected_by_the_diagnostics_addition()
        test_batch_sync_aggregates_failed_count_and_keeps_last_error()
        print("CONTACTS_SYNC_DIAGNOSTICS_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
