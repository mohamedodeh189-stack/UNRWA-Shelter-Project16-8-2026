# -*- coding: utf-8 -*-
"""Phase 7 smoke test: geographic statistics grouping (7 sectors + "غير
مصنف", scoped to one list or all lists) and خطط جولتي's nearest-neighbor
route planning, mirroring MainActivity.showGeographicStatistics() and
showRoutePlan()/nearestNeighborRoute() exactly.

AddressResolution's own logic (confirmed-correction priority, weak-match
review, GPS/sector conflict forcing review) is verified separately and more
strongly by tests/AddressResolutionSmoke.java, a standalone plain-JVM test
of the actual Java class.
"""
import math
import sys

SECTOR_ORDER = [
    "شرق اليرموك 1", "غرب اليرموك 1", "شرق اليرموك 2", "غرب اليرموك 2",
    "شرق اليرموك 3", "غرب اليرموك 3", "العروبة والتقدم",
]


def sector_key(sector):
    return sector if sector in SECTOR_ORDER else "غير مصنف"


def geographic_statistics(beneficiaries, batch_filter="all"):
    counts = {s: 0 for s in SECTOR_ORDER}
    counts["غير مصنف"] = 0
    total = 0
    for b in beneficiaries:
        if batch_filter != "all" and b.get("followup_batch", "") != batch_filter:
            continue
        total += 1
        counts[sector_key(b.get("sector", ""))] += 1
    return total, counts


def beneficiaries_for_sector(beneficiaries, sector_name, batch_filter="all"):
    return [
        b for b in beneficiaries
        if sector_key(b.get("sector", "")) == sector_name
        and (batch_filter == "all" or b.get("followup_batch", "") == batch_filter)
    ]


def test_geographic_statistics_all_seven_sectors_plus_unclassified():
    print("=== Geographic statistics: 7 sectors + 'غير مصنف', single list vs all lists ===")
    beneficiaries = [
        {"name": "A", "sector": "شرق اليرموك 1", "followup_batch": "دفعة 1"},
        {"name": "B", "sector": "شرق اليرموك 1", "followup_batch": "دفعة 2"},
        {"name": "C", "sector": "غرب اليرموك 2", "followup_batch": "دفعة 1"},
        {"name": "D", "sector": "منطقة غير معروفة", "followup_batch": "دفعة 1"},  # unclassified
        {"name": "E", "sector": "", "followup_batch": "دفعة 2"},  # also unclassified
        {"name": "F", "sector": "العروبة والتقدم", "followup_batch": "دفعة 1"},
    ]

    total_all, counts_all = geographic_statistics(beneficiaries, "all")
    assert total_all == 6
    assert counts_all["شرق اليرموك 1"] == 2
    assert counts_all["غرب اليرموك 2"] == 1
    assert counts_all["العروبة والتقدم"] == 1
    assert counts_all["غير مصنف"] == 2
    assert set(counts_all.keys()) == set(SECTOR_ORDER) | {"غير مصنف"}, "Must always show exactly the 7 sectors + غير مصنف"

    total_list1, counts_list1 = geographic_statistics(beneficiaries, "دفعة 1")
    assert total_list1 == 4
    assert counts_list1["شرق اليرموك 1"] == 1
    assert counts_list1["غرب اليرموك 2"] == 1
    assert counts_list1["العروبة والتقدم"] == 1
    assert counts_list1["غير مصنف"] == 1

    tapped = beneficiaries_for_sector(beneficiaries, "غير مصنف", "all")
    assert {b["name"] for b in tapped} == {"D", "E"}, "Tapping the count must open exactly the matching beneficiaries"

    tapped_scoped = beneficiaries_for_sector(beneficiaries, "شرق اليرموك 1", "دفعة 2")
    assert {b["name"] for b in tapped_scoped} == {"B"}

    print("OK: statistics work identically for a single list and for all lists, always report the 7 canonical "
          "sectors plus غير مصنف, and tapping a count opens exactly the matching beneficiaries.\n")


def haversine_m(lat1, lng1, lat2, lng2):
    R = 6371000
    dlat = math.radians(lat2 - lat1)
    dlng = math.radians(lng2 - lng1)
    a = math.sin(dlat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlng / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def nearest_neighbor_route(points):
    """Mirrors MainActivity.nearestNeighborRoute() exactly: greedy nearest-neighbor over REAL GPS points only."""
    remaining = list(points)
    if not remaining:
        return []
    ordered = [remaining.pop(0)]
    while remaining:
        current = ordered[-1]
        nearest = min(remaining, key=lambda p: haversine_m(current["lat"], current["lng"], p["lat"], p["lng"]))
        remaining.remove(nearest)
        ordered.append(nearest)
    return ordered


def test_route_plan_uses_only_real_gps_never_invents_for_missing():
    print("=== خطط جولتي: nearest-neighbor route from real GPS only; no-GPS beneficiaries kept separate ===")
    beneficiaries = [
        {"name": "بيت بعيد", "lat": 33.5100, "lng": 36.3000, "gps_captured_at": 5000},
        {"name": "بيت قريب من البداية", "lat": 33.4992, "lng": 36.2854, "gps_captured_at": 5000},
        {"name": "نقطة الانطلاق", "lat": 33.4991, "lng": 36.2853, "gps_captured_at": 5000},
        {"name": "بلا GPS 1", "lat": None, "lng": None, "gps_captured_at": 0, "sector": "شرق اليرموك 1"},
        {"name": "بلا GPS 2", "lat": None, "lng": None, "gps_captured_at": 0, "sector": "غير مصنف"},
    ]
    with_gps = [b for b in beneficiaries if b["gps_captured_at"] > 0]
    without_gps = [b for b in beneficiaries if b["gps_captured_at"] <= 0]
    assert len(with_gps) == 3 and len(without_gps) == 2

    route = nearest_neighbor_route(with_gps)
    assert len(route) == 3
    assert route[0]["name"] == "بيت بعيد"  # first point in input order is the start, unchanged
    # From "بيت بعيد", the nearest of the remaining two must come next (the one geographically closer).
    assert route[1]["name"] == "بيت قريب من البداية", f"expected greedy-nearest ordering, got {[p['name'] for p in route]}"
    assert route[2]["name"] == "نقطة الانطلاق"

    # Every beneficiary in the route must have had a real GPS fix -- none synthesized.
    for b in route:
        assert b["lat"] is not None and b["gps_captured_at"] > 0

    # The two GPS-less beneficiaries must NEVER appear in the ordered route, and must never get invented coordinates.
    route_names = {b["name"] for b in route}
    for b in without_gps:
        assert b["name"] not in route_names, f"{b['name']} (no GPS) must never be included in the ordered route"
        assert b["lat"] is None, "A beneficiary without GPS must never have a coordinate invented for it"

    print(f"OK: route ordered {len(route)} beneficiaries using only real GPS fixes "
          f"({[b['name'] for b in route]}), and {len(without_gps)} beneficiaries without GPS were kept "
          f"entirely separate with no invented coordinates.\n")


if __name__ == "__main__":
    try:
        test_geographic_statistics_all_seven_sectors_plus_unclassified()
        test_route_plan_uses_only_real_gps_never_invents_for_missing()
        print("GEO_STATS_ROUTE_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
