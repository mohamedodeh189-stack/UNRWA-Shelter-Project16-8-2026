# -*- coding: utf-8 -*-
"""Fix-cycle regression test for the saveStudy() address-reclassification bug found during Final Device
Acceptance Testing: resolveAddress()/applyAddressResolution() used to re-run and silently overwrite
sector/street on EVERY "حفظ بيانات الدراسة" tap, even when the address text was completely unchanged —
this is how a real followup-stage beneficiary's displayed sector/street label was observed to change
between two screenshots with no edit action taken. MainActivity.java's private saveStudy() can't be
called directly from a plain test (Android-dependent, needs a live AppDatabase/EditText), so — following
this project's established precedent for Android-dependent logic — this mirrors the exact decision tree
added to it:
    boolean addressTextChanged = !normalize(newValue).equals(normalize(previousAddress));
    if (!value.isEmpty() && addressTextChanged) {
        ... resolveAddress + applyAddressResolution ...
        if (a prior CONFIRMED correction existed for the OLD text, and the new result isn't itself a
            confirmed correction) -> force needs_review instead of silently accepting the new guess.
    }
"""
import sys

STATUS_CONFIRMED_CORRECTION = "confirmed_correction"
STATUS_CLASSIFIED = "classified"
STATUS_NEEDS_REVIEW = "needs_review"


def normalize(text):
    """Minimal stand-in for AddressClassifier.normalize() — real implementation does much more
    (diacritics, alef/ya unification, whitespace); this test only needs whitespace/case-insensitive
    equality to exercise the changed/unchanged decision, not classification quality itself."""
    return " ".join((text or "").split()).strip()


class Beneficiary:
    def __init__(self, address, sector, street, match_status, confidence, address_source):
        self.address = address
        self.sector = sector
        self.street = street
        self.match_status = match_status
        self.confidence = confidence
        self.address_source = address_source


def fuzzy_classify(address):
    """Stand-in classifier: returns a plausible-but-different sector each call to simulate the real
    non-determinism risk (the actual bug: the fuzzy matcher can score a different sector for the exact
    same or near-identical text on a later run)."""
    if "جامع البشير" in address or "معهد البشير" in address:
        return {"sector": "شرق اليرموك 1", "street": "جامع البشير", "confidence": 0.80}
    return {"sector": "شرق اليرموك 1", "street": "شارع فلسطين أول جزء منه", "confidence": 0.82}


def resolve_address(address, confirmed_corrections):
    normalized = normalize(address)
    correction = confirmed_corrections.get(normalized)
    if correction is not None:
        return {"sector": correction["sector"], "street": correction["street"], "confidence": 1.0,
                "status": STATUS_CONFIRMED_CORRECTION, "needs_review": False, "review_reason": "",
                "address_source": "تصحيح مؤكَّد سابقاً"}
    fuzzy = fuzzy_classify(address)
    weak = fuzzy["confidence"] < 0.78
    return {"sector": fuzzy["sector"], "street": fuzzy["street"], "confidence": fuzzy["confidence"],
            "status": STATUS_NEEDS_REVIEW if weak else STATUS_CLASSIFIED, "needs_review": weak,
            "review_reason": "", "address_source": "تصنيف تلقائي"}


def save_study(b, new_address_text, confirmed_corrections):
    """Mirrors the FIXED MainActivity.saveStudy() decision tree exactly."""
    previous_address = b.address
    value = (new_address_text or "").strip()
    b.address = value  # mirrors db.updateStudy() — text field is always saved regardless
    address_text_changed = normalize(value) != normalize(previous_address)
    if value and address_text_changed:
        resolved = resolve_address(value, confirmed_corrections)
        previous_normalized = normalize(previous_address)
        prior_confirmation = confirmed_corrections.get(previous_normalized) if previous_normalized else None
        if prior_confirmation is not None and resolved["status"] != STATUS_CONFIRMED_CORRECTION:
            resolved["needs_review"] = True
            resolved["status"] = STATUS_NEEDS_REVIEW
            resolved["review_reason"] = "تغيّر نص العنوان بعد تصحيح مؤكَّد سابقاً"
            resolved["address_source"] = "تعارض مع تصحيح مؤكَّد سابقاً — يحتاج مراجعة يدوية"
        b.sector = resolved["sector"]
        b.street = resolved["street"]
        b.confidence = resolved["confidence"]
        b.match_status = resolved["status"]
        b.address_source = resolved["address_source"]
    # else: address text unchanged (or emptied) -> sector/street/match_status/confidence/address_source
    # are left completely untouched, exactly as the bug fix requires.


def test_resaving_unchanged_address_never_touches_classification():
    print("=== Re-saving the SAME address text (e.g. re-opening and tapping Save) must not reclassify ===")
    b = Beneficiary(address="شارع فلسطين -مقابل معهد البشير", sector="شرق اليرموك 1",
                     street="شارع فلسطين أول جزء منه", match_status="تصنيف تلقائي", confidence=0.82,
                     address_source="تصنيف تلقائي")
    before = (b.sector, b.street, b.match_status, b.confidence, b.address_source)
    # Save is triggered 5 times in a row with the identical text — mirrors repeated accidental taps
    # observed during device testing.
    for _ in range(5):
        save_study(b, "شارع فلسطين -مقابل معهد البشير", confirmed_corrections={})
    after = (b.sector, b.street, b.match_status, b.confidence, b.address_source)
    assert before == after, f"classification changed on unchanged-text re-save: {before} -> {after}"
    print("OK: 5 repeated saves with identical address text left sector/street/classification byte-identical.\n")


def test_changing_address_text_does_reclassify():
    print("=== Actually editing the address text DOES trigger reclassification (expected, not a bug) ===")
    b = Beneficiary(address="عنوان غامض جداً", sector="", street="", match_status="", confidence=0,
                     address_source="")
    save_study(b, "شارع فلسطين -مقابل معهد البشير", confirmed_corrections={})
    assert b.sector == "شرق اليرموك 1" and b.street == "جامع البشير"
    print("OK: a real address text change correctly triggers a fresh classification.\n")


def test_changing_address_after_manual_confirmation_forces_needs_review_not_silent_overwrite():
    print("=== Editing an address whose OLD text was manually confirmed forces needs_review, never a silent new guess ===")
    old_text = "شارع فلسطين -مقابل معهد البشير"
    confirmed = {normalize(old_text): {"sector": "شرق اليرموك 1", "street": "شارع فلسطين أول جزء منه"}}
    b = Beneficiary(address=old_text, sector="شرق اليرموك 1", street="شارع فلسطين أول جزء منه",
                     match_status=STATUS_CONFIRMED_CORRECTION, confidence=1.0, address_source="تصحيح مؤكَّد سابقاً")
    save_study(b, "شارع فلسطين -قرب جامع البشير الجديد", confirmed_corrections=confirmed)
    assert b.match_status == STATUS_NEEDS_REVIEW, f"expected needs_review, got {b.match_status}"
    assert "تعارض" in b.address_source or "مراجعة" in b.address_source
    print("OK: address change after a manual confirmation is flagged needs_review instead of silently replaced.\n")


def test_resaving_after_confirmed_correction_with_same_text_keeps_it_confirmed():
    print("=== Re-saving with the SAME text as a prior confirmed correction stays confirmed (no false needs_review) ===")
    text = "شارع فلسطين -مقابل معهد البشير"
    confirmed = {normalize(text): {"sector": "شرق اليرموك 1", "street": "شارع فلسطين أول جزء منه"}}
    b = Beneficiary(address=text, sector="شرق اليرموك 1", street="شارع فلسطين أول جزء منه",
                     match_status=STATUS_CONFIRMED_CORRECTION, confidence=1.0, address_source="تصحيح مؤكَّد سابقاً")
    save_study(b, text, confirmed_corrections=confirmed)  # identical text -> unchanged branch, no resolve at all
    assert b.match_status == STATUS_CONFIRMED_CORRECTION
    print("OK: identical-text re-save never runs resolveAddress at all, confirmed status untouched.\n")


def test_original_address_field_is_never_touched_by_save_study():
    print("=== original_address is a completely separate field save_study() never writes to ===")
    # save_study()/updateStudy() only ever mutate b.address (the editable current address) — original_address
    # is set once by ensureOriginalAddress() at creation time and has no setter reachable from here at all.
    b = Beneficiary(address="نص أصلي", sector="", street="", match_status="", confidence=0, address_source="")
    b.original_address = "نص أصلي كما أُدخل أول مرة"
    save_study(b, "نص مُعدَّل بالكامل", confirmed_corrections={})
    assert b.original_address == "نص أصلي كما أُدخل أول مرة", "original_address must never change, no matter how many times the address is edited"
    print("OK: original_address stayed byte-identical across an address edit.\n")


if __name__ == "__main__":
    try:
        test_resaving_unchanged_address_never_touches_classification()
        test_changing_address_text_does_reclassify()
        test_changing_address_after_manual_confirmation_forces_needs_review_not_silent_overwrite()
        test_resaving_after_confirmed_correction_with_same_text_keeps_it_confirmed()
        test_original_address_field_is_never_touched_by_save_study()
        print("ADDRESS_RECLASSIFY_GUARD_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
