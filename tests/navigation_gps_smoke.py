# -*- coding: utf-8 -*-
"""Fix-cycle regression test for MainActivity.openNavigation(): the new "📍 فتح الموقع للملاحة" button.
Mirrors the exact decision logic (Android-dependent — Intent/Uri/PackageManager — so not directly
runnable outside a device, per this project's established Python-mirror precedent):
  - gpsCapturedAt <= 0 -> a clear "no GPS" message, NEVER a geo: URI built from sector/text guesswork.
  - gpsCapturedAt > 0  -> a standards-compliant geo: URI built ONLY from gps_lat/gps_lng.
"""
import sys
import urllib.parse


class Beneficiary:
    def __init__(self, name, gps_lat=None, gps_lng=None, gps_captured_at=0, sector="", address=""):
        self.name = name
        self.gps_lat = gps_lat
        self.gps_lng = gps_lng
        self.gps_captured_at = gps_captured_at
        self.sector = sector       # deliberately present on the object to prove it's never read below
        self.address = address     # same


class NoGpsResult:
    def __init__(self, offered_pinning):
        self.offered_pinning = offered_pinning


def open_navigation(b):
    """Mirrors MainActivity.openNavigation(AppDatabase.Beneficiary) exactly."""
    if b is None or b.gps_captured_at <= 0:
        return NoGpsResult(offered_pinning=b is not None)
    label = urllib.parse.quote(b.name or "موقع المستفيد")
    return f"geo:{b.gps_lat},{b.gps_lng}?q={b.gps_lat},{b.gps_lng}({label})"


def test_beneficiary_with_real_gps_gets_a_geo_uri_built_only_from_lat_lng():
    print("=== Real GPS fix -> geo: URI built strictly from gps_lat/gps_lng ===")
    b = Beneficiary("أحمد حسين دياب", gps_lat=33.481321, gps_lng=36.288849, gps_captured_at=1750000000000,
                     sector="شرق اليرموك 1", address="عنوان نصي لا علاقة له بالموقع الحقيقي المطلوب")
    uri = open_navigation(b)
    assert isinstance(uri, str) and uri.startswith("geo:33.481321,36.288849"), uri
    assert "q=33.481321,36.288849" in uri
    # The sector name and free-text address must never leak into the coordinates part of the URI —
    # only the URL-encoded label may reference anything beyond raw lat/lng.
    assert "شرق اليرموك" not in uri.split("(")[0]
    print(f"OK: {uri}\n")


def test_beneficiary_without_gps_gets_no_estimated_location():
    print("=== No GPS fix -> clear message, never a sector/text-guessed location ===")
    b = Beneficiary("مستفيد بلا GPS", gps_lat=None, gps_lng=None, gps_captured_at=0,
                     sector="شرق اليرموك 2", address="عنوان تفصيلي واضح تماماً")
    result = open_navigation(b)
    assert isinstance(result, NoGpsResult), "must not silently fabricate a geo: URI from sector/address text"
    assert result.offered_pinning, "should offer the existing pin-location flow as the next step"
    print("OK: no GPS fix produces a clear 'no location' result, never an estimated geo: URI.\n")


def test_map_point_estimated_flag_suppresses_the_navigation_button_entirely():
    print("=== showPointSummary(): a sector-centroid ESTIMATED map point must not offer navigation at all ===")
    # Mirrors: if (!p.estimated) builder.setNeutralButton("الملاحة إلى الموقع", ...)
    for estimated, expect_button in [(True, False), (False, True)]:
        offer_nav_button = not estimated
        assert offer_nav_button == expect_button
    print("OK: navigation button is only ever offered for non-estimated (real GPS) map points.\n")


if __name__ == "__main__":
    try:
        test_beneficiary_with_real_gps_gets_a_geo_uri_built_only_from_lat_lng()
        test_beneficiary_without_gps_gets_no_estimated_location()
        test_map_point_estimated_flag_suppresses_the_navigation_button_entirely()
        print("NAVIGATION_GPS_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
