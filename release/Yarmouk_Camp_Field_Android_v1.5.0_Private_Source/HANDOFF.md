# HANDOFF - Yarmouk Camp Shelter Android

## Current release

- Version: `1.5.0` (`versionCode 150`)
- APK: `dist/Yarmouk_Camp_Field_Android_v1.5.0.apk`
- SHA-256: `5246AA7E7A24EA65BC396A6175357FCB92D762589C4675EA3E655124E9C56222`
- Package: `org.unrwa.yarmoukfield`
- Minimum Android: 7.0 / API 24
- Target SDK: 36
- Database version: 5
- JSON backup schema: 1.3

## Implemented in V1.5

- Imported the actual BOQ catalog from `F:/Project_Data17/Reference.xlsx`, sheet `الكميات`, range `B7:J43`.
- The app contains all 37 BOQ rows with category, item name, unit, fixed unit price, full description, and reason for intervention.
- Study users enter quantities only. The app calculates every subtotal, total USD amount, first payment (60%), and second payment (40%).
- Each beneficiary has normalized quantity rows in `beneficiary_quantities`; legacy beneficiary data is preserved during database migration.
- A study now requires at least one positive BOQ quantity and a positive calculated amount before approval.
- Follow-up records display the approved BOQ and payment split read-only.
- Construction progress is stored from 0 to 100; marking a beneficiary completed sets progress to 100 automatically.
- JSON backup/restore includes BOQ rows, workflow stage, drawing URI, batch, and progress percentage.
- The full plan offers PDF in-app or the embedded `DWG` through an external CAD app.
- The divided sector plan offers PDF in-app or the embedded `DXF` through an external CAD app.
- A read-only `CadFileProvider` grants temporary access to embedded CAD files without broad storage permissions.
- A polished seven-page Arabic PDF user guide is embedded and available from Settings and the in-app guide.
- Design/development credit: المهندس محمد أمين عودة.
- Acknowledgement: المهندس عبد المالك أبو حرب والمهندس محمد عبد العال.

## Reference workbook findings

- Workbook sheets: `الكميات`, `المخطط`, `الوثائق`, `صور قبل الترميم`, `صور بعد الترميم`, and `نسبة الإنجاز`.
- `الكميات` contains 37 rows. Estimated cost is quantity multiplied by unit price; total is the row sum; payments are 60% and 40%.
- `المخطط`, `الوثائق`, `صور قبل الترميم`, and `صور بعد الترميم` are empty template sheets in the supplied workbook; these functions are handled natively by the app.
- The workbook's `نسبة الإنجاز` name link contains `#REF!`; V1.5 stores progress directly in SQLite and does not carry that broken reference forward.

## Verification completed

- Full Java/resource/DEX build for V1.5.0 succeeded.
- APK signatures v2 and v3 were verified with the existing release key.
- APK package/version: `org.unrwa.yarmoukfield`, `1.5.0` / `150`.
- BOQ test: 37 sequential items; sample total 375 USD; payment split 225/150; progress 100.
- `Reference.xlsx` XML smoke test: 6 sheets, 61 rows, 494 cells; Android-compatible SAX reader succeeded.
- APK BOQ JSON, DWG, DXF, user-guide PDF, full map PDF, and sector map PDF match their source files byte-for-byte.
- DWG header is valid (`AC1021`); DXF includes `SECTION`, `ENTITIES`, and final `EOF`.
- The user guide is A4, seven pages, and all pages were rendered to PNG and visually inspected.
- Existing optimized maps and embedded NART font behavior are retained from V1.4.

## Required device checks

No Android phone, emulator, or ADB target was connected during final verification. Before field rollout:

1. Install V1.5.0 over the current app and confirm previous beneficiaries and photos remain available after the database upgrade.
2. Create a test study, select two BOQ rows, and compare the displayed total and 60/40 split with a calculator.
3. Confirm incomplete studies are rejected and a complete study can be marked ready.
4. Use test data to confirm exactly 50 ready studies move into one follow-up batch.
5. Set progress to a partial value, save follow-up, then mark completed and confirm it becomes 100%.
6. Capture one image in each restoration phase and verify the generated project/beneficiary/stage folders.
7. Open both full and sector maps as PDF and test zoom/drag.
8. Install a CAD viewer and open the embedded DWG and DXF options; also verify the clear fallback message when no viewer is installed.
9. Open all seven pages of the embedded Arabic user guide.
10. Export JSON, import it into a temporary project, and confirm BOQ, workflow stages, and progress are restored.
11. Print or save routes with 1, 25, and 26 beneficiaries, including a selected page range.

## Build

Set `YARMOUK_SIGNING_PASSWORD` in the build process, then run `BUILD_ANDROID.ps1` with PowerShell execution policy bypass if required. The script compiles all Java sources, packages all references, signs the APK, verifies signatures, checks package metadata, and validates required APK entries.

Keep `signing/yarmouk-field-release.jks` private and backed up. It is required to install future releases as updates to the same app.

## Repository state

This project currently has no `.git` directory, so there is no Git history, tracked/untracked state, or commit baseline. Initialize Git only after making a separate verified backup of the current release and private signing material.
