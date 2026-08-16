# -*- coding: utf-8 -*-
"""Phase 6 smoke test: map status bucketing, GPS-vs-sector-fallback point
placement, and sector boundary data integrity, mirroring
MainActivity.mapStatusBucket()/showOperationalMap()'s point-building logic
and SectorBoundaries.contains() exactly.

MapCalibration's own math (similarity-transform fit, per-point/overall
error) is verified separately and more strongly by tests/MapCalibrationSmoke.java,
a standalone plain-JVM test of the actual Java class (no Android dependency,
so it runs directly against a real JDK rather than being mirrored in Python).
"""
import json
import os
import sys

DAY_MS = 24 * 3600 * 1000
BOQ_NOT_DELIVERED = "not_delivered"


# --- Mirrors MainActivity.mapStatusBucket() exactly ---
def map_status_bucket(visit_status, work_delivered, execution_start_at, now, progress_percent):
    if visit_status == "completed":
        return "done" if work_delivered else "ready_for_pickup"
    if execution_start_at and execution_start_at > 0 and now > execution_start_at + 35 * DAY_MS:
        return "late"
    if visit_status == "pending":
        return "in_progress" if progress_percent > 0 else "awaiting"
    if visit_status == "review":
        return "in_progress"
    return "awaiting"


def execution_start_at(delivery_confirmed_at, first_payment_available_at):
    if not delivery_confirmed_at or not first_payment_available_at:
        return 0
    return max(delivery_confirmed_at, first_payment_available_at)


def test_map_status_bucket_matches_followup_screen_logic():
    print("=== mapStatusBucket() classification (must agree with the follow-up detail screen's own 'late' logic) ===")
    now = 1_700_000_000_000

    # completed + delivered -> done
    assert map_status_bucket("completed", True, 0, now, 0) == "done"
    # completed + not yet delivered -> ready_for_pickup
    assert map_status_bucket("completed", False, 0, now, 0) == "ready_for_pickup"
    # pending, no progress recorded -> awaiting (not yet visited)
    assert map_status_bucket("pending", False, 0, now, 0) == "awaiting"
    # pending, some progress recorded -> in_progress
    assert map_status_bucket("pending", False, 0, now, 45) == "in_progress"
    # review -> in_progress bucket
    assert map_status_bucket("review", False, 0, now, 0) == "in_progress"

    # Execution window not yet started (either date missing) -> never 'late', regardless of elapsed time.
    exec_start = execution_start_at(delivery_confirmed_at=now - 200 * DAY_MS, first_payment_available_at=0)
    assert exec_start == 0
    assert map_status_bucket("pending", False, exec_start, now, 60) == "in_progress", \
        "Must never be 'late' while execution_start_at is 0, no matter how much time has passed"

    # Execution window started and now past 35 days, still not completed -> late
    exec_start2 = execution_start_at(delivery_confirmed_at=now - 40 * DAY_MS, first_payment_available_at=now - 36 * DAY_MS)
    assert exec_start2 == now - 36 * DAY_MS  # MAX of the two -> the later one
    assert map_status_bucket("pending", False, exec_start2, now, 50) == "late"

    # But once actually completed, 'late' must never apply even past the 35-day window.
    assert map_status_bucket("completed", False, exec_start2, now, 100) == "ready_for_pickup"
    assert map_status_bucket("completed", True, exec_start2, now, 100) == "done"

    # Within the 35-day window -> not late yet.
    exec_start3 = execution_start_at(now - 10 * DAY_MS, now - 5 * DAY_MS)
    assert exec_start3 == now - 5 * DAY_MS
    assert map_status_bucket("pending", False, exec_start3, now, 0) == "awaiting"

    print("OK: bucket classification matches expected rules, and 'late' only ever fires when "
          "execution_start_at (Phase 4's own MAX-of-two-dates logic) has actually started and 35 days passed.\n")


def test_gps_point_vs_sector_fallback_placement():
    print("=== Point placement: real GPS (calibrated+in-frame) vs sector-centroid fallback vs skip-if-unclassified ===")

    def place_point(calib_reliable, has_gps, frac_in_frame, sector_known):
        """Mirrors the exact decision tree in MainActivity.showOperationalMap()'s refresh Runnable."""
        if calib_reliable and has_gps and frac_in_frame:
            return "real_gps"
        if sector_known:
            return "sector_estimate"
        return "skipped"

    assert place_point(calib_reliable=True, has_gps=True, frac_in_frame=True, sector_known=True) == "real_gps"
    # Calibration not reliable/ready yet -> must fall back to sector estimate, never invent a GPS-based pixel.
    assert place_point(calib_reliable=False, has_gps=True, frac_in_frame=True, sector_known=True) == "sector_estimate"
    # No GPS captured for this beneficiary yet -> sector estimate.
    assert place_point(calib_reliable=True, has_gps=False, frac_in_frame=False, sector_known=True) == "sector_estimate"
    # GPS exists but the calibrated transform puts it outside the raster's 0..1 frame -> fall back, not clamp/guess.
    assert place_point(calib_reliable=True, has_gps=True, frac_in_frame=False, sector_known=True) == "sector_estimate"
    # No GPS AND sector unrecognized (e.g. "غير مصنف") -> no point is placed at all, never invented.
    assert place_point(calib_reliable=True, has_gps=False, frac_in_frame=False, sector_known=False) == "skipped"

    print("OK: a beneficiary only ever gets a real-GPS marker when calibration is reliable, a GPS fix exists, "
          "and it maps inside the raster frame; otherwise it falls back to a sector-centroid estimate, and if "
          "even the sector is unknown, no point is invented at all.\n")


def polygon_centroid(points):
    """Mirrors MainActivity.polygonCentroid() exactly: the true area-weighted centroid, not a plain vertex
    average -- required because a plain average can land outside a concave/irregular polygon (as it did for
    the L-shaped غرب اليرموك 1/2/3 splits before this formula was used)."""
    n = len(points)
    area = 0.0
    cx = 0.0
    cy = 0.0
    for i in range(n):
        x0, y0 = points[i]
        x1, y1 = points[(i + 1) % n]
        cross = x0 * y1 - x1 * y0
        area += cross
        cx += (x0 + x1) * cross
        cy += (y0 + y1) * cross
    area *= 0.5
    if abs(area) < 1e-9:
        sx = sum(p[0] for p in points); sy = sum(p[1] for p in points)
        return (sx / n, sy / n)
    return (cx / (6 * area), cy / (6 * area))


def point_in_polygon(fx, fy, points):
    inside = False
    n = len(points)
    j = n - 1
    for i in range(n):
        xi, yi = points[i]
        xj, yj = points[j]
        if ((yi > fy) != (yj > fy)) and (fx < (xj - xi) * (fy - yi) / (yj - yi) + xi):
            inside = not inside
        j = i
    return inside


def test_sector_boundaries_json_integrity():
    print("=== sector_boundaries.json: matches AddressClassifier's 7 canonical sectors, valid closed polygons ===")
    path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "sector_boundaries.json")
    with open(path, encoding="utf-8") as f:
        data = json.load(f)

    expected_sectors = {
        "شرق اليرموك 1", "غرب اليرموك 1", "شرق اليرموك 2", "غرب اليرموك 2",
        "شرق اليرموك 3", "غرب اليرموك 3", "العروبة والتقدم",
    }
    assert set(data.keys()) == expected_sectors, f"Sector name mismatch: {set(data.keys())} vs {expected_sectors}"

    for name, points in data.items():
        assert len(points) >= 3, f"{name}: polygon must have at least 3 vertices, got {len(points)}"
        for x, y in points:
            assert 0.0 <= x <= 1.0 and 0.0 <= y <= 1.0, f"{name}: vertex ({x},{y}) out of the 0..1 fraction range"
        cx, cy = polygon_centroid(points)
        assert 0.0 <= cx <= 1.0 and 0.0 <= cy <= 1.0, f"{name}: centroid ({cx},{cy}) out of range"
        assert point_in_polygon(cx, cy, points), f"{name}: its own centroid must test as inside its polygon"

    # The three غرب اليرموك sub-sectors must be stacked top-to-bottom with no vertical overlap in centroid Y
    # (they were split from one connected blob using the plan's own divider lines).
    gharb1_y = polygon_centroid(data["غرب اليرموك 1"])[1]
    gharb2_y = polygon_centroid(data["غرب اليرموك 2"])[1]
    gharb3_y = polygon_centroid(data["غرب اليرموك 3"])[1]
    assert gharb1_y < gharb2_y < gharb3_y, \
        f"غرب 1/2/3 centroids must be in top-to-bottom order, got {gharb1_y},{gharb2_y},{gharb3_y}"

    print(f"OK: all 7 sectors present matching AddressClassifier exactly, every polygon is valid (>=3 vertices, "
          f"in-range, self-consistent centroid), and غرب اليرموك 1/2/3 are correctly ordered top-to-bottom.\n")


if __name__ == "__main__":
    try:
        test_map_status_bucket_matches_followup_screen_logic()
        test_gps_point_vs_sector_fallback_placement()
        test_sector_boundaries_json_integrity()
        print("OPERATIONAL_MAP_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
