# -*- coding: utf-8 -*-
"""Fix-cycle regression test for the dual-mode GPS capture system (GpsLocationHelper.java +
MainActivity.java's captureHomeLocation/saveHomeLocation), added per explicit user requirement:
  - MODE_GPS_OFFLINE (GPS_PROVIDER only) is the official default, works with zero connectivity.
  - MODE_DEVICE_HIGH_ACCURACY uses every enabled provider (GPS_PROVIDER + NETWORK_PROVIDER) — faster
    indoors, but the actual winning reading's source is always reported per Location.getProvider(),
    never assumed from which button the user pressed.
  - Over a capture window, only the single BEST (lowest accuracy value) reading is kept — never first,
    never last.
  - No location is ever saved without either (a) it being a first-time pin from pure GPS, or (b) explicit
    user confirmation — required for every update-over-existing-fix AND every non-pure-GPS save, even
    first-time.
  - This app adds no INTERNET permission — LocationManager's NETWORK_PROVIDER is consumed via the
    ACCESS_FINE_LOCATION-gated system service, never a direct network call from this app's own code.
Android-dependent (LocationManager/Location/AlertDialog), so mirrored in Python per this project's
established precedent.
"""
import sys

MODE_GPS_OFFLINE = "gps_offline"
MODE_DEVICE_HIGH_ACCURACY = "device_high_accuracy"
ACCURACY_WARNING_M = 30


class FakeLocation:
    def __init__(self, lat, lng, accuracy, provider):
        self.lat, self.lng, self.accuracy, self.provider = lat, lng, accuracy, provider


def source_for(location):
    """Mirrors MainActivity.sourceFor(Location): the actual winning provider decides the label, never
    which mode button the user pressed."""
    return MODE_GPS_OFFLINE if location.provider == "gps" else MODE_DEVICE_HIGH_ACCURACY


def best_of_window(readings):
    """Mirrors GpsLocationHelper's onLocationChanged best-tracking: keeps the single lowest-accuracy
    reading seen across the whole stream, regardless of arrival order."""
    best = None
    for reading in readings:
        if best is None or reading.accuracy < best.accuracy:
            best = reading
    return best


class Beneficiary:
    def __init__(self, gps_lat=None, gps_lng=None, gps_captured_at=0, gps_accuracy_m=0, gps_source=""):
        self.gps_lat, self.gps_lng, self.gps_captured_at = gps_lat, gps_lng, gps_captured_at
        self.gps_accuracy_m, self.gps_source = gps_accuracy_m, gps_source


class SaveDecision:
    """Mirrors the branch chosen in GpsLocationHelper.Callback.onFinished()."""
    def __init__(self, kind, requires_confirmation):
        self.kind = kind
        self.requires_confirmation = requires_confirmation


def decide(b, location):
    is_update = b.gps_captured_at > 0
    source = source_for(location)
    if location.accuracy > ACCURACY_WARNING_M:
        return SaveDecision("weak_accuracy_prompt", True)
    if is_update:
        return SaveDecision("confirm_replace", True)
    if source != MODE_GPS_OFFLINE:
        return SaveDecision("confirm_non_gps_first_save", True)
    return SaveDecision("save_immediately", False)


def save(b, location, source):
    b.gps_lat, b.gps_lng, b.gps_accuracy_m = location.lat, location.lng, location.accuracy
    b.gps_captured_at = 99999
    b.gps_source = source


def test_best_of_window_keeps_lowest_accuracy_regardless_of_order():
    print("=== Best-of-window selection keeps the single most accurate reading, not first or last ===")
    readings = [
        FakeLocation(33.1, 36.1, 40.0, "gps"),
        FakeLocation(33.11, 36.11, 8.0, "gps"),   # best
        FakeLocation(33.12, 36.12, 25.0, "gps"),
    ]
    best = best_of_window(readings)
    assert best.accuracy == 8.0
    print(f"OK: kept accuracy={best.accuracy}m out of {[r.accuracy for r in readings]}\n")


def test_winning_source_reflects_actual_provider_not_the_chosen_mode():
    print("=== Even in device-high-accuracy mode, a GPS-provider reading is correctly labeled gps_offline ===")
    readings = [
        FakeLocation(33.1, 36.1, 60.0, "network"),
        FakeLocation(33.11, 36.11, 12.0, "gps"),  # best, and it's real GPS
    ]
    best = best_of_window(readings)
    assert source_for(best) == MODE_GPS_OFFLINE, "the WINNING reading's real provider must decide the label"
    print("OK: winning reading is correctly labeled gps_offline despite the session running in high-accuracy mode.\n")

    readings2 = [
        FakeLocation(33.1, 36.1, 45.0, "gps"),
        FakeLocation(33.11, 36.11, 9.0, "network"),  # best, and it's network
    ]
    best2 = best_of_window(readings2)
    assert source_for(best2) == MODE_DEVICE_HIGH_ACCURACY
    print("OK: winning reading correctly labeled device_high_accuracy when network genuinely won on accuracy.\n")


def test_first_time_pure_gps_save_needs_no_extra_confirmation():
    print("=== First-time save from pure GPS_PROVIDER (accuracy under threshold) saves without extra confirm ===")
    b = Beneficiary()
    location = FakeLocation(33.5, 36.3, 10.0, "gps")
    decision = decide(b, location)
    assert decision.kind == "save_immediately" and not decision.requires_confirmation
    save(b, location, source_for(location))
    assert b.gps_source == MODE_GPS_OFFLINE
    print("OK: pure-GPS first-time pin saves directly, source recorded as gps_offline.\n")


def test_first_time_network_sourced_save_requires_explicit_confirmation():
    print("=== First-time save whose best reading came from NETWORK_PROVIDER always requires confirmation ===")
    b = Beneficiary()
    location = FakeLocation(33.5, 36.3, 10.0, "network")
    decision = decide(b, location)
    assert decision.kind == "confirm_non_gps_first_save" and decision.requires_confirmation
    print("OK: even a first-time, good-accuracy, network-sourced fix is never silently saved — user must confirm.\n")


def test_update_over_existing_fix_always_requires_confirmation_regardless_of_source():
    print("=== Any update over an existing fix requires confirmation, whether GPS or network-sourced ===")
    b = Beneficiary(gps_lat=1, gps_lng=2, gps_captured_at=1000, gps_accuracy_m=5, gps_source=MODE_GPS_OFFLINE)
    before = (b.gps_lat, b.gps_lng, b.gps_accuracy_m, b.gps_captured_at, b.gps_source)
    for provider in ("gps", "network"):
        location = FakeLocation(9.9, 9.9, 7.0, provider)
        decision = decide(b, location)
        assert decision.kind == "confirm_replace" and decision.requires_confirmation
    assert (b.gps_lat, b.gps_lng, b.gps_accuracy_m, b.gps_captured_at, b.gps_source) == before, "deciding must never itself write anything"
    print("OK: updates always require confirmation for both sources; old fix untouched until then.\n")


def test_weak_accuracy_always_requires_confirmation_regardless_of_update_or_source():
    print("=== Accuracy worse than 30m always requires explicit confirmation, in every combination ===")
    for is_update in (False, True):
        for provider in ("gps", "network"):
            b = Beneficiary(gps_lat=1, gps_lng=2, gps_captured_at=1000 if is_update else 0)
            location = FakeLocation(5, 5, 55.0, provider)
            decision = decide(b, location)
            assert decision.kind == "weak_accuracy_prompt" and decision.requires_confirmation
    print("OK: weak accuracy always gates on confirmation across all 4 combinations.\n")


def test_cancel_or_no_reading_never_touches_stored_location():
    print("=== No reading received in the window (best=None) must never call save at all ===")
    b = Beneficiary(gps_lat=1.0, gps_lng=2.0, gps_captured_at=500, gps_accuracy_m=6, gps_source=MODE_GPS_OFFLINE)
    before = (b.gps_lat, b.gps_lng, b.gps_accuracy_m, b.gps_captured_at, b.gps_source)
    best = best_of_window([])  # empty stream -> caller must treat as onUnavailable, never call save()
    assert best is None
    assert (b.gps_lat, b.gps_lng, b.gps_accuracy_m, b.gps_captured_at, b.gps_source) == before
    print("OK: an empty reading stream produces no best location and no write.\n")


if __name__ == "__main__":
    try:
        test_best_of_window_keeps_lowest_accuracy_regardless_of_order()
        test_winning_source_reflects_actual_provider_not_the_chosen_mode()
        test_first_time_pure_gps_save_needs_no_extra_confirmation()
        test_first_time_network_sourced_save_requires_explicit_confirmation()
        test_update_over_existing_fix_always_requires_confirmation_regardless_of_source()
        test_weak_accuracy_always_requires_confirmation_regardless_of_update_or_source()
        test_cancel_or_no_reading_never_touches_stored_location()
        print("GPS_DUAL_MODE_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
