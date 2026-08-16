"""Prepare Android-safe, tightly cropped Yarmouk map PDFs.

The divided source PDF references the legacy NART TrueType font without
embedding it. Android therefore substitutes another font and renders the
street labels as unrelated Latin glyphs. This script embeds the installed
NART font, removes the oversized page labels, and crops every page to its map.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from pypdf import PdfReader, PdfWriter, Transformation
from pypdf.generic import ContentStream, DecodedStreamObject, NameObject, NumberObject, RectangleObject


# Crop rectangles in original PDF coordinates: left, bottom, right, top.
# They were measured from 144-DPI renders of the supplied seven-page PDF.
SECTOR_CROPS = (
    (410.0, 291.0, 842.0, 897.0),
    (250.0, 467.0, 842.0, 723.0),
    (344.0, 449.0, 816.0, 759.0),
    (34.0, 263.0, 494.0, 847.0),
    (20.0, 375.0, 704.0, 841.0),
    (0.0, 431.0, 522.0, 763.0),
    (0.0, 415.0, 512.0, 788.0),
)

FULL_MAP_CROP = (63.0, 86.0, 830.0, 1068.0)
TEXT_SHOW_OPERATORS = {b"Tj", b"TJ", b"'", b'"'}


def crop_to_origin(page, rectangle: tuple[float, float, float, float]) -> None:
    left, bottom, right, top = rectangle
    page.add_transformation(Transformation().translate(-left, -bottom))
    box = RectangleObject((0, 0, right - left, top - bottom))
    page.mediabox = box
    page.cropbox = RectangleObject(box)
    page.trimbox = RectangleObject(box)
    page.bleedbox = RectangleObject(box)
    page.artbox = RectangleObject(box)


def embed_nart(page, font_data: bytes, embedded_descriptors: set[int]) -> int:
    embedded = 0
    resources = page.get("/Resources")
    fonts = resources.get("/Font", {}) if resources else {}
    for font_ref in fonts.values():
        font = font_ref.get_object()
        if str(font.get("/BaseFont", "")).lower() not in {"/nart", "/nart1"}:
            continue
        descriptor_ref = font.get("/FontDescriptor")
        if descriptor_ref is None:
            continue
        descriptor = descriptor_ref.get_object()
        marker = id(descriptor)
        if marker in embedded_descriptors or any(key in descriptor for key in ("/FontFile", "/FontFile2", "/FontFile3")):
            continue
        stream = DecodedStreamObject()
        stream.set_data(font_data)
        stream[NameObject("/Length1")] = NumberObject(len(font_data))
        descriptor[NameObject("/FontFile2")] = stream
        embedded_descriptors.add(marker)
        embedded += 1
    return embedded


def remove_page_titles(page, reader: PdfReader) -> None:
    content = ContentStream(page.get_contents(), reader)
    resources = page.get("/Resources", {})
    fonts = resources.get("/Font", {}) if resources else {}
    nart_keys = {
        str(key)
        for key, value in fonts.items()
        if str(value.get_object().get("/BaseFont", "")).lower() in {"/nart", "/nart1"}
    }
    keep_current_text = True
    filtered = []
    for operands, operator in content.operations:
        if operator == b"Tf" and len(operands) > 1:
            keep_current_text = str(operands[0]) in nart_keys
        if operator in TEXT_SHOW_OPERATORS and not keep_current_text:
            continue
        filtered.append((operands, operator))
    content.operations = filtered
    page[NameObject("/Contents")] = content


def optimize_sector_pdf(source: Path, destination: Path, nart_font: Path) -> None:
    reader = PdfReader(str(source))
    if len(reader.pages) != len(SECTOR_CROPS):
        raise ValueError(f"Expected seven sector pages, found {len(reader.pages)}")
    writer = PdfWriter()
    embedded_descriptors: set[int] = set()
    font_data = nart_font.read_bytes()
    embedded_count = 0
    for page, crop in zip(reader.pages, SECTOR_CROPS):
        embedded_count += embed_nart(page, font_data, embedded_descriptors)
        remove_page_titles(page, reader)
        crop_to_origin(page, crop)
        writer.add_page(page)
        writer.pages[-1].compress_content_streams()
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("wb") as output:
        writer.write(output)
    if embedded_count == 0:
        raise RuntimeError("The unembedded NART font resource was not found")


def optimize_full_map(source: Path, destination: Path) -> None:
    reader = PdfReader(str(source))
    if len(reader.pages) != 1:
        raise ValueError(f"Expected one full-map page, found {len(reader.pages)}")
    page = reader.pages[0]
    crop_to_origin(page, FULL_MAP_CROP)
    writer = PdfWriter()
    writer.add_page(page)
    writer.pages[-1].compress_content_streams()
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("wb") as output:
        writer.write(output)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--full-source", type=Path, required=True)
    parser.add_argument("--sector-source", type=Path, required=True)
    parser.add_argument("--nart-font", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    optimize_full_map(args.full_source, args.output_dir / "yarmouk_camp_map_optimized.pdf")
    optimize_sector_pdf(args.sector_source, args.output_dir / "yarmouk_sector_maps_optimized.pdf", args.nart_font)


if __name__ == "__main__":
    main()
