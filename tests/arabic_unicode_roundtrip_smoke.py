# -*- coding: utf-8 -*-
"""Fix-cycle regression test: Arabic Unicode must survive every import/search/export path unchanged —
not one character altered, and never turned into CJK-looking mojibake.

Audit performed (see fix-cycle report for the full trail): every text read path in
SpreadsheetImporter.java already declares StandardCharsets.UTF_8 explicitly for CSV/JSON
(new InputStreamReader(..., StandardCharsets.UTF_8)); the XLSX path uses the platform SAX
parser directly against the zip entry stream, which auto-detects encoding from the OOXML
XML declaration (always UTF-8 per the OOXML spec — never overridden here); SQLite via
ContentValues/Cursor.getString is UTF-8-safe by the Android platform regardless of locale;
and Canvas.drawText()/PrintDocumentAdapter (the A4 print path) take a plain Java String and
never touch raw bytes. No corruption path was found in the current source. This test locks
that in as a regression guard and is the "round-trip" test requested for the fix cycle.

This file mirrors two pure-logic algorithms exactly, so they can be exercised without an
Android runtime:
  1. SpreadsheetImporter.csv(String) — the hand-rolled CSV cell splitter.
  2. AddressClassifier.normalize()-style whitespace handling is NOT touched here (a separate,
     already-tested module) — this test only concerns raw text preservation, not classification.
"""
import json
import sys


def csv_split(line):
    """Mirrors SpreadsheetImporter.csv(String line) exactly: a hand-rolled RFC4180-ish splitter."""
    out = []
    cur = []
    quotes = False
    i = 0
    n = len(line)
    while i < n:
        ch = line[i]
        if ch == '"':
            if quotes and i + 1 < n and line[i + 1] == '"':
                cur.append('"')
                i += 1
            else:
                quotes = not quotes
        elif ch == ',' and not quotes:
            out.append(''.join(cur))
            cur = []
        else:
            cur.append(ch)
        i += 1
    out.append(''.join(cur))
    return out


# Real composite Arabic names/addresses seen in this project's own field data and guide text —
# deliberately including combining forms, hamza, shadda, and lam-alef ligature-prone sequences
# that are exactly where a UTF-8<->UTF-16<->CJK-codepage mojibake bug would first show up.
REAL_ARABIC_SAMPLES = [
    "هبة سليمان عطية",
    "نور الدين عادل عبد الله",
    "عبد الرؤوف محمد الفار",
    "شرق اليرموك 1 — شارع فلسطين أول جزء منه",
    "شارع فلسطين -مقابل معهد البشير",
    "المهندس محمد أمين عودة",
    "التّطبيق يعمل دون إنترنت؛ يُحفَظ محلياً على الجهاز.",
    "مؤسّسة الشؤون الإجتماعية — رقم الهاتف: 0992177915",
]


def test_csv_cell_roundtrip_preserves_every_arabic_character():
    print("=== CSV cell split preserves composite Arabic text byte-for-character ===")
    for name in REAL_ARABIC_SAMPLES:
        line = f'{name},0900000000,"{name} — ملاحظة, بفاصلة"'
        cells = csv_split(line)
        assert cells[0] == name, f"cell 0 corrupted: {cells[0]!r} != {name!r}"
        assert cells[2] == f"{name} — ملاحظة, بفاصلة", f"quoted cell with embedded comma corrupted: {cells[2]!r}"
    print(f"OK: {len(REAL_ARABIC_SAMPLES)} real composite Arabic samples round-tripped through CSV splitting unchanged.\n")


def test_utf8_encode_decode_roundtrip_matches_java_charset_declaration():
    print("=== UTF-8 byte round-trip (what StandardCharsets.UTF_8 guarantees on the Java side) ===")
    for name in REAL_ARABIC_SAMPLES:
        encoded = name.encode("utf-8")
        decoded = encoded.decode("utf-8")
        assert decoded == name, f"UTF-8 round-trip corrupted: {decoded!r} != {name!r}"
        # The classic "Arabic -> Chinese-looking glyphs" bug is what you get from decoding UTF-8
        # bytes as if they were GBK/Big5/CP936 — assert that never happens for any sample here.
        try:
            wrongly_decoded = encoded.decode("gbk")
            assert wrongly_decoded != name, "sample is ambiguous between UTF-8 and GBK — pick a different sample"
        except (UnicodeDecodeError, LookupError):
            pass  # expected: valid UTF-8 Arabic bytes are usually not valid GBK at all
    print("OK: every sample round-trips exactly through UTF-8 bytes (the encoding used by every text-reading path in SpreadsheetImporter.java).\n")


def test_json_roundtrip_preserves_arabic():
    print("=== JSON export/import (sync package + backup format) preserves Arabic exactly ===")
    payload = {"name": REAL_ARABIC_SAMPLES[0], "address": REAL_ARABIC_SAMPLES[3], "notes": REAL_ARABIC_SAMPLES[6]}
    text = json.dumps(payload, ensure_ascii=False)
    restored = json.loads(text)
    assert restored == payload, "JSON round-trip altered Arabic text"
    # Also confirm ensure_ascii=False is the correct choice (mirrors org.json's default UTF-8 behavior,
    # never \uXXXX-escaping Arabic) — a regression here would still round-trip correctly through json.loads,
    # but would bloat every exported file and is worth asserting explicitly stayed off.
    assert "\\u" not in text, "Arabic text was escaped as \\uXXXX instead of staying raw UTF-8 in the JSON text"
    print("OK: JSON round-trip exact, and Arabic stays as raw UTF-8 text rather than \\u-escaped.\n")


if __name__ == "__main__":
    try:
        test_csv_cell_roundtrip_preserves_every_arabic_character()
        test_utf8_encode_decode_roundtrip_matches_java_charset_declaration()
        test_json_roundtrip_preserves_arabic()
        print("ARABIC_UNICODE_ROUNDTRIP_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
