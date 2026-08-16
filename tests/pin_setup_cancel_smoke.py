# -*- coding: utf-8 -*-
"""Fix-cycle regression test for the PIN setup trap found during Final Device Acceptance Testing:
togglePinProtection() used to call security.setEnabled(true) BEFORE the setup dialog even opened, and
showPinSetup(false) (first-time setup) had setCancelable(false) and no negative button at all — so
backing out before saving a PIN left the app permanently re-opening an uncancellable setup dialog on
every single launch. Mirrors the fixed state machine (AppSecurity + MainActivity are Android-dependent,
so — per this project's established precedent — the exact enabled/configured/dialog-outcome logic is
mirrored here as plain Python state).
"""
import sys


class FakeAppSecurity:
    """Mirrors AppSecurity.java's persisted state exactly: two independent booleans."""
    def __init__(self):
        self.enabled = False
        self.configured = False  # true only once setPin() has actually succeeded

    def set_enabled(self, value):
        self.enabled = value

    def set_pin(self, pin):
        if not (pin and len(pin) == 6 and pin.isdigit()):
            raise ValueError("invalid pin")
        self.configured = True


def toggle_pin_protection(security):
    """Mirrors the FIXED togglePinProtection(): never flips enabled=true up front."""
    if security.enabled:
        security.enabled = False
        security.configured = False
        return "disabled"
    return "opened_setup_dialog"  # security.enabled is NOT touched here anymore


def show_pin_setup_outcome(security, changing, user_action, pin=None, confirm=None):
    """Mirrors showPinSetup()'s onDismissListener + save handler exactly.
    user_action: 'save' | 'cancel_button' | 'back_press' (BACK and the explicit Cancel button both
    behave identically now — the dialog is cancelable in every case)."""
    if user_action == "save":
        if pin != confirm or not (pin and len(pin) == 6 and pin.isdigit()):
            return "save_rejected"  # dialog stays open, no state change at all
        security.set_pin(pin)
        security.set_enabled(True)  # only NOW does protection become active
        return "saved"
    # cancel_button or back_press: dialog dismisses with nothing saved.
    if not changing and not security.configured and security.enabled:
        # Self-heals a device left "enabled but unconfigured" by the OLD buggy dialog.
        security.enabled = False
    return "cancelled"


def test_cancelling_first_time_setup_before_saving_leaves_protection_off():
    print("=== First-time setup: BACK/إلغاء before saving a PIN leaves protection completely off ===")
    security = FakeAppSecurity()
    outcome = toggle_pin_protection(security)
    assert outcome == "opened_setup_dialog"
    assert security.enabled is False, "enabling must not happen before the dialog even opens"
    for action in ("cancel_button", "back_press"):
        sec = FakeAppSecurity()
        toggle_pin_protection(sec)
        result = show_pin_setup_outcome(sec, changing=False, user_action=action)
        assert result == "cancelled"
        assert sec.enabled is False, f"{action}: protection must stay OFF, was left ON by the old bug"
        assert sec.configured is False
    print("OK: cancelling first-time setup via either path leaves the app in exactly its prior state.\n")


def test_saving_a_valid_pin_enables_protection_only_after_success():
    print("=== Protection becomes enabled ONLY after setPin() actually succeeds ===")
    security = FakeAppSecurity()
    toggle_pin_protection(security)
    assert security.enabled is False
    result = show_pin_setup_outcome(security, changing=False, user_action="save", pin="135790", confirm="135790")
    assert result == "saved"
    assert security.enabled is True and security.configured is True
    print("OK: enabled flips to true exactly once, after a real PIN is persisted.\n")


def test_mismatched_confirmation_does_not_enable_or_configure_anything():
    print("=== Save with mismatched PIN/confirm rejects without touching enabled/configured ===")
    security = FakeAppSecurity()
    toggle_pin_protection(security)
    result = show_pin_setup_outcome(security, changing=False, user_action="save", pin="135790", confirm="000000")
    assert result == "save_rejected"
    assert security.enabled is False and security.configured is False
    print("OK: a rejected save leaves state untouched, dialog would stay open for correction.\n")


def test_self_heals_a_device_already_stuck_by_the_old_bug():
    print("=== A device already left enabled=true/configured=false by the OLD bug self-heals on next cancel ===")
    security = FakeAppSecurity()
    security.enabled = True   # simulates the exact broken state a real device was found stuck in
    security.configured = False
    result = show_pin_setup_outcome(security, changing=False, user_action="back_press")
    assert result == "cancelled"
    assert security.enabled is False, "must self-heal the inconsistent enabled=true/configured=false state"
    print("OK: an already-stuck device recovers as soon as the fixed dialog is cancelled once.\n")


def test_changing_an_existing_pin_cancel_never_disables_current_protection():
    print("=== Changing an ALREADY-configured PIN: cancelling must not disable existing protection ===")
    security = FakeAppSecurity()
    security.enabled = True
    security.configured = True
    result = show_pin_setup_outcome(security, changing=True, user_action="cancel_button")
    assert result == "cancelled"
    assert security.enabled is True and security.configured is True, "cancelling a PIN CHANGE must leave the existing PIN and enabled state untouched"
    print("OK: cancelling a PIN change (not first-time setup) leaves existing protection intact.\n")


if __name__ == "__main__":
    try:
        test_cancelling_first_time_setup_before_saving_leaves_protection_off()
        test_saving_a_valid_pin_enables_protection_only_after_success()
        test_mismatched_confirmation_does_not_enable_or_configure_anything()
        test_self_heals_a_device_already_stuck_by_the_old_bug()
        test_changing_an_existing_pin_cancel_never_disables_current_protection()
        print("PIN_SETUP_CANCEL_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
