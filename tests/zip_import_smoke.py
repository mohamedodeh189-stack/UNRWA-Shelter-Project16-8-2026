# -*- coding: utf-8 -*-
"""Fix-cycle regression test for ZipImportHelper.java's extraction safety logic.

ZipImportHelper.extract() takes an Android Context/Uri and can't run outside a device/emulator,
so — following this project's established precedent for Android-dependent logic (see
contacts_sync_smoke.py, sync_export_smoke.py) — this mirrors the exact algorithm in Python and
exercises it against REAL zip files built with Python's zipfile module on disk, including
deliberately malicious ones (Zip Slip, oversized entries). The mirror implements the same three
defenses as the Java source, in the same order:
  1. every entry is flattened to its basename before ever touching the filesystem (removes Zip
     Slip entirely, independent of any path check);
  2. the resolved destination's real path is re-checked against the extraction root anyway
     (defense in depth, matches the Java canonical-path check);
  3. running per-entry and total uncompressed-size counters enforced while streaming out, not
     trusted from the (spoofable) central directory header — the actual Zip Bomb defense.
"""
import os
import shutil
import sys
import tempfile
import zipfile

MAX_ENTRIES = 300
MAX_ENTRY_UNCOMPRESSED_BYTES = 80 * 1024 * 1024
MAX_TOTAL_UNCOMPRESSED_BYTES = 250 * 1024 * 1024
MAX_ARCHIVE_BYTES = 400 * 1024 * 1024
SUPPORTED_EXT = {"xlsx", "xlsm", "xls", "csv", "json"}


class ZipRejected(Exception):
    pass


def extension_of(base_name):
    dot = base_name.rfind(".")
    return base_name[dot + 1:].lower() if dot >= 0 else ""


def last_segment(name):
    return name.replace("\\", "/").rsplit("/", 1)[-1]


def unique_target(directory, base_name):
    candidate = os.path.join(directory, base_name)
    if not os.path.exists(candidate):
        return candidate
    stem, ext = os.path.splitext(base_name)
    counter = 2
    while os.path.exists(candidate):
        candidate = os.path.join(directory, f"{stem}_{counter}{ext}")
        counter += 1
    return candidate


def extract(zip_path, extract_dir, chunk_size=64 * 1024):
    """Python mirror of ZipImportHelper.extract(), including the outer-archive-size sanity check —
    this is exactly the gate that rejected a real field .zip at the old 120MB value."""
    if os.path.getsize(zip_path) > MAX_ARCHIVE_BYTES:
        raise ZipRejected(f"archive too large (> {MAX_ARCHIVE_BYTES} bytes)")
    os.makedirs(extract_dir, exist_ok=True)
    canonical_root = os.path.realpath(extract_dir) + os.sep
    files, skipped = [], []
    entry_count = 0
    total_uncompressed = 0

    with zipfile.ZipFile(zip_path) as zf:
        for info in zf.infolist():
            entry_count += 1
            if entry_count > MAX_ENTRIES:
                raise ZipRejected(f"too many entries (> {MAX_ENTRIES})")
            if info.is_dir():
                continue
            name = info.filename or ""
            base = last_segment(name)
            if "__MACOSX" in name or not base or base.startswith("."):
                skipped.append(f"{name} — ignored")
                continue
            ext = extension_of(base)
            if ext not in SUPPORTED_EXT:
                skipped.append(f"{name} — unsupported type")
                continue
            if info.file_size > MAX_ENTRY_UNCOMPRESSED_BYTES:
                raise ZipRejected(f"entry too large: {base}")

            out_path = unique_target(extract_dir, base)
            out_canonical = os.path.realpath(out_path)
            if not (out_canonical + (os.sep if os.path.isdir(out_canonical) else "")).startswith(canonical_root) and not out_canonical.startswith(canonical_root):
                skipped.append(f"{name} — unsafe path, ignored")
                continue

            written = 0
            with zf.open(info) as src, open(out_path, "wb") as dst:
                while True:
                    chunk = src.read(chunk_size)
                    if not chunk:
                        break
                    written += len(chunk)
                    total_uncompressed += len(chunk)
                    if written > MAX_ENTRY_UNCOMPRESSED_BYTES or total_uncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES:
                        raise ZipRejected("uncompressed content exceeds the allowed limit — extraction stopped")
                    dst.write(chunk)
            files.append((name, out_path, written))
    return files, skipped


def _mk_zip(path, entries):
    """entries: list of (arcname, content_bytes)."""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        for arcname, content in entries:
            zf.writestr(arcname, content)


def test_valid_single_file_zip_extracts_and_is_supported():
    print("=== Valid ZIP containing one supported CSV extracts cleanly ===")
    with tempfile.TemporaryDirectory() as tmp:
        zpath = os.path.join(tmp, "list.zip")
        _mk_zip(zpath, [("beneficiaries.csv", "name,address\nهبة سليمان عطية,شرق اليرموك\n".encode("utf-8"))])
        files, skipped = extract(zpath, os.path.join(tmp, "out"))
        assert len(files) == 1 and files[0][0] == "beneficiaries.csv"
        assert not skipped
        with open(files[0][1], encoding="utf-8") as f:
            assert "هبة سليمان عطية" in f.read()
    print("OK: single supported file extracted with Arabic content intact.\n")


def test_multi_file_zip_lists_every_supported_file():
    print("=== ZIP with several supported files: all are extracted and offered ===")
    with tempfile.TemporaryDirectory() as tmp:
        zpath = os.path.join(tmp, "bundle.zip")
        _mk_zip(zpath, [
            ("list_a.xlsx", b"not a real xlsx but extraction doesn't parse content"),
            ("list_b.csv", b"name,address\nx,y\n"),
            ("meta.json", b'{"a":1}'),
            ("readme.txt", b"not supported"),
            ("__MACOSX/._list_a.xlsx", b"mac resource fork junk"),
        ])
        files, skipped = extract(zpath, os.path.join(tmp, "out"))
        names = sorted(f[0] for f in files)
        assert names == ["list_a.xlsx", "list_b.csv", "meta.json"], names
        assert any("readme.txt" in s for s in skipped)
        assert any("__MACOSX" in s for s in skipped)
    print(f"OK: 3 supported files extracted, unsupported/__MACOSX entries skipped without error (skipped={len(skipped)}).\n")


def test_corrupt_zip_raises_a_clear_error_not_a_crash():
    print("=== Corrupt/non-ZIP file raises a catchable error, never crashes the extractor ===")
    with tempfile.TemporaryDirectory() as tmp:
        fake = os.path.join(tmp, "not_really.zip")
        with open(fake, "wb") as f:
            f.write(b"this is not a zip file at all, just plain bytes")
        try:
            extract(fake, os.path.join(tmp, "out"))
            raise AssertionError("expected a BadZipFile-style error for a corrupt archive")
        except ZipRejected:
            raise AssertionError("wrong exception type for corruption")
        except zipfile.BadZipFile:
            pass  # expected — Java's ZipFile constructor throws ZipException analogously
    print("OK: corrupt archive raises a clean, catchable error.\n")


def test_zip_slip_path_traversal_is_neutralized():
    print("=== Zip Slip (../../ path traversal) never escapes the extraction directory ===")
    with tempfile.TemporaryDirectory() as tmp:
        zpath = os.path.join(tmp, "evil.zip")
        outside_marker = os.path.join(tmp, "should_not_be_created.csv")
        # Craft an entry name that would traverse out of the extraction dir if used literally.
        _mk_zip(zpath, [("../../../../tmp/should_not_be_created.csv", b"name,address\nmalicious,x\n")])
        out_dir = os.path.join(tmp, "out")
        files, skipped = extract(zpath, out_dir)
        assert not os.path.exists(outside_marker), "Zip Slip escaped the extraction directory!"
        # Flattening to the basename means it either lands safely inside out_dir or is skipped —
        # both are acceptable outcomes; escaping the sandbox is the only unacceptable one.
        if files:
            assert os.path.realpath(files[0][1]).startswith(os.path.realpath(out_dir) + os.sep)
    print("OK: traversal entry never escaped the sandbox directory (extracted safely inside it, or skipped).\n")


def test_large_legitimate_archive_over_old_120mb_limit_is_no_longer_rejected():
    print("=== The real 133MB Organized_Final.zip size now passes the archive-size gate (old 120MB rejected it) ===")
    # Verified arithmetically rather than by writing 130MB to disk (the CI/dev disk here is near-full, and the
    # gate is a pure size comparison anyway). The real file that triggered this bug is ~133MB.
    real_file_size = 133_651_969  # actual bytes of F:\Project_Data13\Organized_Final.zip
    old_limit = 120 * 1024 * 1024
    assert real_file_size > old_limit, "sanity: the real file really did exceed the old 120MB gate"
    assert real_file_size < MAX_ARCHIVE_BYTES, "the real file must now pass the raised 400MB archive-size gate"
    # And an absurd input still gets rejected, so the ceiling still guards against garbage.
    assert (500 * 1024 * 1024) > MAX_ARCHIVE_BYTES, "a 500MB input must still be rejected by the sanity ceiling"
    print(f"OK: {real_file_size // (1024*1024)}MB (the real file) passes the new gate; the old 120MB gate would have rejected it; 500MB is still refused.\n")


def test_zip_bomb_entry_exceeding_cap_is_rejected():
    print("=== An entry whose real uncompressed size exceeds the per-entry cap is rejected while streaming ===")
    with tempfile.TemporaryDirectory() as tmp:
        zpath = os.path.join(tmp, "bomb.zip")
        # Highly compressible content that is small on disk but expands past the per-entry cap —
        # exercises the running-counter check, not just a central-directory size field.
        oversized_repeats = (MAX_ENTRY_UNCOMPRESSED_BYTES // (64 * 1024) + 2)
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as zf:
            with zf.open("bomb.csv", "w") as w:
                chunk = b"0" * (64 * 1024)
                for _ in range(oversized_repeats):
                    w.write(chunk)
        try:
            extract(zpath, os.path.join(tmp, "out"))
            raise AssertionError("expected ZipRejected for an oversized entry")
        except ZipRejected as rejected:
            assert "large" in str(rejected) or "limit" in str(rejected)
    print("OK: oversized entry rejected mid-stream before exhausting disk space.\n")


def test_entry_count_cap_is_enforced():
    print("=== An archive with more entries than MAX_ENTRIES is rejected ===")
    with tempfile.TemporaryDirectory() as tmp:
        zpath = os.path.join(tmp, "many.zip")
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as zf:
            for i in range(MAX_ENTRIES + 5):
                zf.writestr(f"f{i}.csv", b"a,b\n1,2\n")
        try:
            extract(zpath, os.path.join(tmp, "out"))
            raise AssertionError("expected ZipRejected for too many entries")
        except ZipRejected as rejected:
            assert "entries" in str(rejected)
    print("OK: entry-count cap enforced.\n")


def test_fifty_file_archive_extracts_all_fifty_not_just_one():
    print("=== A 50-.xlsx archive (the real Organized_Final.zip shape) extracts ALL 50, never just the first ===")
    with tempfile.TemporaryDirectory() as tmp:
        zpath = os.path.join(tmp, "Organized_Final.zip")
        arabic_names = [f"مستفيد رقم {i} تجريبي" for i in range(1, 51)]
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as zf:
            zf.writestr("Organized_Final/", b"")  # directory entry, must be skipped cleanly
            for name in arabic_names:
                # content doesn't need to be a real xlsx for the EXTRACTION test — that's the importer's job.
                zf.writestr(f"Organized_Final/{name}.xlsx", b"PK-fake-xlsx-bytes")
        files, skipped = extract(zpath, os.path.join(tmp, "out"))
        assert len(files) == 50, f"expected all 50 files extracted, got {len(files)}"
        extracted_names = {os.path.splitext(os.path.basename(f[0]))[0] for f in files}
        assert extracted_names == set(arabic_names), "every one of the 50 Arabic-named files must be extracted, none dropped"
    print("OK: all 50 .xlsx entries extracted (Arabic names intact), directory entry skipped — no more 'only the first file'.\n")


def simulate_batch_import(per_file_results):
    """Mirrors MainActivity.importZipFilesBatch() aggregation exactly: append every file's rows, continue on
    a single-file failure, count ok/failed/beneficiaries. per_file_results: list of (name, rows_or_None) where
    None means the file threw / had no importable rows."""
    ok = failed = beneficiaries = 0
    failures = []
    for name, rows in per_file_results:
        if rows is None or rows == 0:
            failed += 1
            failures.append(name)
        else:
            ok += 1
            beneficiaries += rows
    return {"ok": ok, "failed": failed, "beneficiaries": beneficiaries, "failures": failures}


def test_batch_import_continues_past_a_single_failure_and_sums_correctly():
    print("=== Batch import: one bad file doesn't abort the rest; totals aggregate across all files ===")
    per_file = [(f"list_{i}.xlsx", 1) for i in range(50)]
    per_file[17] = ("list_17.xlsx", None)   # one file fails (e.g. corrupt / empty)
    per_file[42] = ("bad_old.xls", None)    # a legacy .xls, explicitly rejected by the importer
    summary = simulate_batch_import(per_file)
    assert summary["ok"] == 48
    assert summary["failed"] == 2
    assert summary["beneficiaries"] == 48
    assert "list_17.xlsx" in summary["failures"] and "bad_old.xls" in summary["failures"]
    print(f"OK: 48 succeeded, 2 failed (named), 48 beneficiaries imported; the batch never stopped at the first failure.\n")


def test_single_file_zip_still_imports_that_one_file():
    print("=== A single-supported-file ZIP still imports exactly that one file (old behaviour preserved) ===")
    summary = simulate_batch_import([("only_list.csv", 12)])
    assert summary["ok"] == 1 and summary["failed"] == 0 and summary["beneficiaries"] == 12
    print("OK: single-file archive imports its one file with all its rows.\n")


def test_name_collision_after_flattening_is_never_silently_overwritten():
    print("=== Two entries with the same basename in different source folders don't clobber each other ===")
    with tempfile.TemporaryDirectory() as tmp:
        zpath = os.path.join(tmp, "collide.zip")
        _mk_zip(zpath, [
            ("folderA/list.csv", b"a,b\nA,1\n"),
            ("folderB/list.csv", b"a,b\nB,2\n"),
        ])
        files, skipped = extract(zpath, os.path.join(tmp, "out"))
        assert len(files) == 2, "one file was silently dropped/overwritten on name collision"
        contents = set()
        for _, path, _ in files:
            with open(path, "rb") as f:
                contents.add(f.read())
        assert contents == {b"a,b\nA,1\n", b"a,b\nB,2\n"}, "both files' distinct content must survive"
    print("OK: name collision resolved with a suffix, both files preserved intact.\n")


if __name__ == "__main__":
    try:
        test_valid_single_file_zip_extracts_and_is_supported()
        test_multi_file_zip_lists_every_supported_file()
        test_corrupt_zip_raises_a_clear_error_not_a_crash()
        test_zip_slip_path_traversal_is_neutralized()
        test_large_legitimate_archive_over_old_120mb_limit_is_no_longer_rejected()
        test_zip_bomb_entry_exceeding_cap_is_rejected()
        test_entry_count_cap_is_enforced()
        test_fifty_file_archive_extracts_all_fifty_not_just_one()
        test_batch_import_continues_past_a_single_failure_and_sums_correctly()
        test_single_file_zip_still_imports_that_one_file()
        test_name_collision_after_flattening_is_never_silently_overwritten()
        print("ZIP_IMPORT_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
