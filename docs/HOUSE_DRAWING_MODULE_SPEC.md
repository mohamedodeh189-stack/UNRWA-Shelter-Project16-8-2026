# House Drawing Module — Spec & Staged Plan (in-project source of truth)

Status: **approved 2026-08-07**. This file is the durable specification (do not rely on session memory alone).
Part of the beneficiary-file journey: field survey → damage → quantities → **drawing** → DXF → beneficiary file.

## Goal
A professional **field sketch tool** (NOT full CAD) inside the beneficiary file, section «مخطط المنزل», letting the
engineer quickly draw a simple house plan with real dimensions and export it. Must never block the project — MVP first.

## MVP scope (this cycle)
Walls, lines, rooms (rectangle), select, move, delete, pinch-zoom + pan, **scale calibration**, dimensions (real
meters), layers (show/hide), save, **PDF export**, **DXF export**.

## Deferred (later cycle)
Detailed architectural symbols (door/window/stair/column/opening), BOQ linking, edit-length-by-number, advanced snapping.

## Fixed decisions
- **Zero external libraries.** Native only: custom `Canvas` View, `ScaleGestureDetector`+`Matrix`, `android.graphics.pdf.PdfDocument` for PDF, hand-written ASCII DXF, and this module's own tiny JSON I/O (`DrawJson`, no `org.json`).
- **Room names:** Arabic in-app and in PDF; **Latin-safe codes (R1, R2…) in DXF** (AutoCAD Arabic shaping is unreliable). Keep an AR↔code map on the PDF.
- **Persistence:** independent files linked to the beneficiary (`<key>.draw.json` + `.pdf` + `.dxf` in the beneficiary/app folder). **No DB change now.** Later (additive only): `drawing_model_uri`, `drawing_pdf_uri`, `drawing_dxf_uri` beside existing `drawing_uri`.
- **Field-assistant** guidance on first open → force scale calibration before drawing.
- Identity: navy `#0B2F52` / teal `#0E8A9C` / white; faint UNRWA watermark behind canvas; same card + assistant style as the beneficiary file. PDF header: «مشروع المأوى في الأونروا / UNRWA Shelter Project» + beneficiary name + file no + date + engineer + UNRWA logo.

## Engineering-grade requirements (core to the FINAL product — added 2026-08-07)
Direction locked now so the tool aims at an engineering croquis (like a hand CAD sketch → PDF), not free drawing.
MVP scope is unchanged; these are recorded as design and scheduled below. **Area calc + PDF + DXF are core final deliverables, not optional.**

1. **Auto area + summary** — when the house boundary is closed, compute floor area in **m²** automatically and show a live summary panel: `مساحة المخطط` (m²), `عدد الغرف`, `حالة المخطط` (مغلق/غير مغلق), `حالة المعايرة`. Area from room rectangles is trivial (w×h); a whole-house closed boundary uses the shoelace formula over the ordered loop vertices — needs a "closed boundary" (closed polyline/polygon) notion, so a closed-loop detection (or a dedicated boundary tool) is required. Uncalibrated → area shown as "—".
2. **Deed-area comparison (optional, in the beneficiary file)** — engineer may enter the title-deed area (m²); app shows drawn-area vs deed area and the difference in m² and %. Example:
   ```
   مساحة المخطط: 98.6 م²
   مساحة السند: 102 م²
   الفرق: 3.4 م² (3.3%)
   ```
3. **Final outputs (all three):** internal **JSON** model + **PDF** (print/attach to beneficiary file) + **DXF/CAD** (AutoCAD).
4. **PDF spec:** clear plan frame; north arrow; **Arabic** room names; wall dimensions; ceiling height if entered; beneficiary name; file no; engineer name; date; identity line «مشروع المأوى في الأونروا / UNRWA Shelter Project» + UNRWA logo.
5. **DXF spec:** explicit layers **Walls / Rooms / Dimensions / Notes**; Latin-safe room codes in DXF if Arabic breaks in AutoCAD (Arabic stays in the PDF). Not full CAD — a precise, fast field sketch.

Schedule delta (MVP still first): PDF details fold into **M6**; DXF layers/codes into **M7**; a new **M9 — area summary + deed comparison** stage is appended after M8; a lightweight area/room/status panel may surface earlier but is formally verified in M9. Ceiling-height input is a small optional field added with M9/M6.

**Reference croquis conventions (from the user's sample plan, 2026-08-07):** target look = an engineering croquis. Concrete elements the sample confirms: wall dimensions written **in centimetres** (e.g. 335, 360, 300) — the app computes in metres, so the PDF/DXF will offer a **cm dimension presentation** to match this house-croquis convention (display option, not a model change); a ceiling-height header `h=3.20m`; an **N** north arrow; Arabic room labels (غرفة / صالون / حمام / مطبخ / WC); and a **title block** under the plan — «كروكي لمنزل <المستفيد>»، floor label (e.g. «طابق أول»)، and «إعداد المهندس/ة: <name>». Fold the title block + cm option into the M6 PDF spec.

## Technical risks (mitigations)
0. **Closed-boundary area** — a house area needs a genuinely closed loop; open walls have no area. Mitigation: sum room rectangles and/or detect a closed wall loop (shoelace); show "غير مغلق" until closed, never a guessed area.
1. DXF Arabic shaping → Latin room codes; `$INSUNITS`=meters; minimal proven entity set (LINE/LWPOLYLINE/TEXT + LAYER table).
2. Calibration error → all dims wrong → guard zero length, always show scale, allow re-calibrate, confirm before save.
3. Touch precision → snapping to endpoints + 90°, large hit margins.
4. Performance on low-end → simple model, local invalidate, sane element cap.
5. Export destination → save to beneficiary/app folder or MediaStore, NEVER the (device-broken) DocumentsUI picker.

## Testing strategy
- Pure-logic (model/geometry/JSON/DXF-string) → **standalone JVM smoke tests** (`tests/*.java`, plain JDK).
- Visual (canvas/tools/calibration) → **debug build + adb** (drawing = touch taps/swipes; calibration = digit input — both adb-drivable; sidesteps Arabic-input/file-picker blockers). Screenshot each.
- Export files verified on PC (pull PDF/DXF; open DXF in a viewer/AutoCAD).
- Arabic room-name entry is not adb-typable → defaulted/skipped in automated tests.
- **No release build** until final approval. Debug builds use a debuggable temp-manifest (build-time only; source manifest untouched).

## Stages & approval checkpoints
| Stage | Content | Test | Checkpoint |
|---|---|---|---|
| M0 | Model + geometry + JSON I/O (pure Java) | JVM smoke | auto |
| M1 | Canvas View: zoom/pan/grid/north | device screenshot | auto |
| M2 | Draw walls/lines/rooms + select/move/delete + undo/redo + snapping | device | auto |
| M3 | Scale calibration + dimensions + first-open assistant | device | **STOP — approval 1** |
| M4 | Layers (show/hide) | device | auto |
| M5 | Save/load JSON linked to beneficiary | device (draw→exit→reopen) | **STOP — approval 2** |
| M6 | PDF export (native, AR labels, header) | export + open | auto |
| M7 | DXF export (ASCII, layers, dims, Latin codes) | **open on PC/AutoCAD** | **STOP — approval 3** |
| M8 | «مخطط المنزل» card integration in beneficiary file | device | **STOP — approval 4 (final)** |

Auto-run between checkpoints unless a technical risk or design decision arises. Enterprise cadence: spec → build → test → approve → move on.

## Progress log
- 2026-08-07: Spec approved.
- 2026-08-07: **M0 DONE.** Pure-Java `drawing/` package: `DrawElement`, `DrawLayer`, `DrawGeo`, `DrawModel` (calibration/undo/bounds), `DrawJson` (own dependency-free JSON I/O). `tests/DrawModelSmoke.java` → DRAW_MODEL_SMOKE_OK (30 assertions). App compile-only: APP_COMPILE_OK.
- 2026-08-07: **M1 + M2 DONE (device-verified, debug).** `DrawCanvasView` (Matrix pan/zoom, grid, north, tools select/wall/line/room/delete, endpoint+axis snapping, tap-selection, move, undo/redo) + `HouseDrawActivity` (persistent top bar with always-visible Undo/Redo, bottom tool row, **autosave from first stroke** to `filesDir/draw_debug.json`, load-on-open). Manifest: `.drawing.HouseDrawActivity` added `exported="true"` **(TEMP — set false before release)**. Launched via `adb am start`; verified: draw walls+room, snapping, undo removes room, tap-select highlights a wall, force-stop→relaunch reloads the drawing. Stable debug keystore now at `scratchpad/stable_debug.keystore` (install -r across iterations).
- 2026-08-07: **M3 DONE (device-verified, debug).** Calibrate tool (TOOL_CALIBRATE) + 2-step assistant dialog («الخطوة 2 من 2»: enter metres), real-metre dimension labels drawn in screen space on walls/rooms (dims layer, only when calibrated), always-visible calibrated state («معاير • 1م = N px» green / «غير معاير» gold), one-tap re-calibration, and an export gate (تصدير disabled until calibrated; assistant banner explains the 2 steps first). Verified on device: uncalibrated → export dimmed + no dims; draw ref line → enter 5 → «معاير • 1م=80px» + dims 4.93/4.95/4.88م + export enabled; re-calibrate to 10 → px/m 80→40 and all dims recomputed (9.92/9.96/9.82م). Spec updated with engineering-grade requirements (auto area/m², room count, closed/open, deed comparison, PDF/DXF specs, reference-croquis conventions). Checkpoint 1 approved.
- 2026-08-07: **M4 DONE (device-verified).** Layers as a bottom sheet (Dialog, gravity bottom, rounded top, dim scrim): 5 toggles (الجدران/الغرف والخطوط/الأبعاد/الملاحظات/الأضرار) flipping `layer.visible` live; note «الإخفاء لا يحذف أي بيانات». Verified: hide dims+walls → canvas clears; re-show → walls+dims return identically (5.11/5.10م). Visibility persists in JSON (already serialized).
- 2026-08-07: **M5 DONE (device-verified).** Per-beneficiary save: `HouseDrawActivity` reads intent extra `draw_key` and stores `files/drawings/house_<key>.json` (no DB change — association by filename; M8 passes the real beneficiary key). Autosave on every change + onPause. Verified: draw+calibrate under key TEST-0001 → back/force-stop/relaunch same key = fully restored (walls+dims+scale); relaunch key TEST-0002 = empty & uncalibrated (isolation). Checkpoint 2 approved.
- 2026-08-07: **M6 (PDF) + M7 (DXF) DONE (PC-verified).** New classes: `DxfWriter` (pure Java, ASCII DXF), `PdfExporter` (android.graphics.pdf), `DrawExportMeta`; export dialog in the activity (ceiling field + PDF/DXF), files to `getExternalFilesDir/exports/house_<key>.{pdf,dxf}`, export gated on calibration. Model gained `ceilingHeightM` (+JSON). Verified by pulling both files to PC: **DXF** parsed clean — `$INSUNITS=6` (metres), layers Walls/Rooms/Dimensions/Notes/Damage, 2×LINE on Walls (5.11/5.10 m matching the app), 1×LWPOLYLINE on Rooms (3.42×2.28 m), dimensions as independent TEXT on Dimensions, Latin-safe ROOM-01; PIL render matches the app geometry exactly. **PDF** rendered (fitz): A4 landscape frame, north arrow, scale note, wall+room dimensions in metres, faint UNRWA watermark, and a full title block (project AR+EN, beneficiary/file-no/floor/engineer/date, UNRWA logo) — **Arabic shapes and orders correctly**. Fixed: DXF entity layer names now mapped by layer-id to canonical spec names (Walls/Rooms/…) so table+entities always match regardless of older JSON. Checkpoint 3 approved.
- 2026-08-07: **M8 DONE (device-verified).** «مخطط المنزل» card added to the beneficiary file (`housePlanCard` in showBeneficiary) — **no DB change**: the drawing lives at `files/drawings/house_<beneficiary.id>.json`, and PDF/DXF are generated on demand into `cacheDir/embedded_cad/` and opened via the existing `CadFileProvider` (added PDF mime). No-plan state → «رسم مخطط جديد» launches `HouseDrawActivity` with `draw_key = beneficiary.id` (+ benef_name/file_no/engineer extras). Has-plan state → preview thumbnail (`DrawThumb`), calibration state, **calculated area (m² via `DrawModel.roomAreaM2` + room count)**, last-modified, and تعديل/فتح PDF/فتح DXF. Verified on device: created AAA → card no-plan → draw new (editor opened EMPTY, not another record's) → calibrate+room → reopen AAA = has-plan card showing preview + «11.10 م² • 1 غرفة» + «معاير 1م=79px» + date; «فتح PDF» fired the system PDF chooser (file generated); created BBB → card no-plan (isolation, AAA's plan not shown). **Drawing module MVP (M0–M8) COMPLETE.**

## Pre-release cleanup (MANDATORY before any release build)
- `AndroidManifest.xml`: `.drawing.HouseDrawActivity` is `android:exported="true"` **only** for adb test-launch — set `exported="false"` and launch it solely from the beneficiary card.
- The debuggable temp-manifest is build-time only (source manifest untouched); release via BUILD_ANDROID.ps1 is already non-debuggable.

## REGISTERED NEXT STAGE — Wall Thickness Module (before architectural elements)
Approved to record 2026-08-07; not yet implemented. Goal: move from a line croquis toward an architectural (CAD-like) plan by giving walls real thickness. Doors / windows / stairs / BOQ linking stay OUT of this stage.

**Requirements**
1. A wall is stored as an **axis** (2 points, as today) **plus an editable thickness** — not just a line.
2. Default thicknesses: **10 cm, 15 cm, 20 cm, and a custom value** the engineer sets. A current/default thickness applies to newly drawn walls; a selected wall's thickness can be changed.
3. Canvas: a wall renders as a **closed area with two parallel edges** (axis offset by ±thickness/2, perpendicular). Dimensions stay measured on the **axis** (or per engineer's choice — axis default).
4. DXF: export each wall as a **closed POLYLINE (LWPOLYLINE)** of its real thickness outline — not a bare LINE. Layer `Walls` carries the true wall borders. Units stay **metres**.
5. PDF: walls appear **with thickness** (like the reference croquis) — thickness walls + rooms + dimensions + north arrow + title block.
6. NOT in this stage: doors, windows, stairs, BOQ linking.

**Data (additive, no destructive change):** `DrawElement` gains `thicknessM` (default e.g. 0.15). Serialized in JSON (default when absent, so old drawings still load — a thickness-less wall renders at the default). Room/line elements ignore it.

**Proposed sub-stages (each: compile → debug build → device/PC test):**
- **W1 — model + canvas + thickness picker. DONE (device-verified 2026-08-07).** `DrawElement.thicknessM` (0=unset→shown at `DrawModel.DEFAULT_WALL_THICKNESS_M`=0.15, never auto-rewritten) + JSON; reserved `DrawModel.dimensionRef="axis"` (+JSON) so a future net-inner-dimension mode needs no migration; walls render as a closed band (axis ±thickness/2, two parallel edges + light fill) — metric, so to-scale once calibrated, line fallback before; new walls stamped with the current thickness; a «سماكة» picker (10/15/20 سم + custom cm) that targets the SELECTED wall if one is selected («سماكة الجدار المحدَّد») else sets the new-wall default; dimensions stay on the axis centre-line. Verified on device: calibrate → draw wall (15cm slim band, «5.01 م» on axis) → select → سماكة → custom 40cm → wall visibly thickens as a closed band, toast «سماكة الجدار المحدَّد: 40 سم». UX stayed field-simple (same draw gesture; thickness is one optional picker with a sensible default). **STOP — approval (does it stay easy, or feel like CAD?).**
- **W2 — DXF walls as closed POLYLINE outlines. DONE (PC-verified 2026-08-07).** `DxfWriter` now exports each WALL as a CLOSED `LWPOLYLINE` (4 verts, group 70=1) of its real thickness outline (axis ± t/2, metres) on layer `Walls` — no more bare LINE for walls; LINE elements stay LINE; dimension TEXT stays on the axis centre-line. Only the DXF export changed — drawing UX untouched. Verified by pulling the DXF to PC: `$INSUNITS=6` (metres), layers Walls/Rooms/Dimensions/Notes/Damage, **0 LINE / 1 LWPOLYLINE** for the wall = closed 4-vert band **length 5.013 m × thickness 0.400 m (40 cm)** matching the app, dimension TEXT "5.01" on Dimensions; PIL render shows a clean filled rectangular band with square corners (no distortion). **STOP — W2 approval before W3 (PDF thickness).**
- **W3 — PDF walls with thickness. DONE (PC-verified 2026-08-07).** `PdfExporter` draws each WALL as its real thickness band (axis ± t/2, light fill + navy outline) instead of a line; rooms, axis dimensions, north, scale, frame, watermark, title block all unchanged. PDF-only — model, DXF, and drawing UX untouched. Verified by exporting three walls (20/15/40 cm) and rendering the PDF on PC: the three bands show **visibly different thickness** (40 cm clearly thickest, 15 cm thinnest), dimensions stay on the axis (4.90/4.92/4.84 m), Arabic + title block correct, DXF unaffected. Minor polish: dimension text can overlap a thick band's edge — nudge dim offset by thickness later. **Wall Thickness Module (W1–W3) COMPLETE — first architectural field-plan engine version.** STOP before architectural elements (doors/windows/stairs).

## Polish / deferred (post-MVP, not blocking)
- Numerals→Latin sweep (app-wide), top-bar title wrap when crowded, PDF ceiling-header (works when a value is entered).
- Deferred features: architectural symbols (door/window/stair/column/opening), room-naming tool (Arabic labels → PDF), BOQ linking, edit-length-by-number.
- Engineering extras (spec'd): whole-house closed-boundary area via shoelace (MVP sums room rectangles), deed-area comparison field in the beneficiary file, cm dimension presentation option, title-block floor field.
- Wire the card into the FINAL beneficiary-profile redesign when that screen is built (currently slotted into the existing profile dialog).
