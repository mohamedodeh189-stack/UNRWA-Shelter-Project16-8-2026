# HANDOFF - Yarmouk Camp Shelter Android

## Current release

- Version: `1.5.4` (`versionCode 154`)
- APK: `dist/Yarmouk_Camp_Field_Android_v1.5.4.apk`
- SHA-256: `2B4ACEDDA51B23BD376E42E4E35E5DE855D2BA636BD0FD9629247445CE98F7AE`
- Package: `org.unrwa.yarmoukfield`
- Minimum Android: 7.0 / API 24
- Target SDK: 36
- Database version: 5
- JSON backup schema: 1.3

## Implemented in V1.5

### V1.5.4 map legibility, sector boundaries, and movable header

- The full map is explicitly presented as an overview because its source street names are outlined CAD geometry rather than replaceable PDF text.
- Seven translucent colored sector envelopes and thinner compact tappable labels were added over the supplied full-camp plan.
- Sector pages are presented as the detailed reading mode because their NART font is embedded.
- A visible header handle supports tap and vertical swipe to expand/collapse the app header and reveal the designer, app description, and acknowledgements.
- The in-app FastView help now gives six explicit Arabic steps for saving and installing `NART.TTF`.
- The V1.5.4 Arabic guide was regenerated and all seven rendered pages were visually inspected.

### V1.5.3 maps, screenshots, CAD fonts, and landscape header

- Screenshots are allowed; automatic Android backup remains disabled and the PIN gate remains active.
- The schematic sector route was replaced with the full Yarmouk camp plan and seven polished, tappable sector badges.
- PDF maximum zoom was raised substantially and render quality scales with device memory; the viewer also applies system-bar insets.
- In landscape, the full identity header is vertically scrollable inside a shorter viewport, preserving access to the designer and acknowledgements.
- `NART.TTF` is embedded and exportable. The map dialog explains how to import it into FastView Fonts and then reopen DWG/DXF.
- PDF guide controls apply the Android bottom inset, and quantity dialogs use `adjustResize` plus focused-row auto-scroll so the keyboard does not cover the active field or save controls.

### V1.5.2 safe system-bar layout

- The actual status/navigation/cutout insets are applied to the header and bottom navigation, preventing overlap with Android's three-button and gesture navigation areas.

### V1.5.1 field usability and security

- Every BOQ item has a unit-aware calculator: M² uses length × width × count; M3 uses length × width × height × count; MR uses length × count; kg uses quantity × unit weight × count; remaining units use count × factor.
- A calculator result can replace the current quantity or be added to it, with immediate subtotal, total, and payment refresh.
- The 60% and 40% payments display as whole numbers without decimal fractions; the second payment is adjusted so both equal the rounded total.
- The map dialog now uses two visible buttons: in-app PDF and external CAD DWG/DXF.
- A mandatory six-digit PIN gate uses salted PBKDF2 hashing, a three-minute background relock, and a 30-second lockout after five failed attempts.
- Disabled Android backup, no Internet permission, a non-exported CAD provider, and private local preferences reduce exposure of field data while screenshots remain user-controlled.
- The app header, About screen, and guide acknowledge المهندسة يمام أمين عودة alongside the existing acknowledgements.

### V1.5.0 BOQ and progress foundation

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
- Acknowledgement: المهندس عبد المالك أبو حرب والمهندس محمد عبد العال والمهندسة يمام أمين عودة.

## Reference workbook findings

- Workbook sheets: `الكميات`, `المخطط`, `الوثائق`, `صور قبل الترميم`, `صور بعد الترميم`, and `نسبة الإنجاز`.
- `الكميات` contains 37 rows. Estimated cost is quantity multiplied by unit price; total is the row sum; payments are 60% and 40%.
- `المخطط`, `الوثائق`, `صور قبل الترميم`, and `صور بعد الترميم` are empty template sheets in the supplied workbook; these functions are handled natively by the app.
- The workbook's `نسبة الإنجاز` name link contains `#REF!`; V1.5 stores progress directly in SQLite and does not carry that broken reference forward.

## Verification completed

- Full Java/resource/DEX build for V1.5.3 completed successfully.
- APK signatures v2 and v3 were verified with the existing release key.
- APK package/version verified: `org.unrwa.yarmoukfield`, `1.5.3` / `153`.
- BOQ test: 37 sequential items; sample total 375 USD; rounded 827 USD payment split 496/331; calculator samples 24/4.8/18; progress 100.
- `Reference.xlsx` smoke test: 6 sheets, 65 rows, 585 cells; workbook reader succeeded.
- APK BOQ JSON, DWG, DXF, user-guide PDF, full map PDF, and sector map PDF match their source files byte-for-byte.
- DWG header is valid (`AC1021`); DXF includes `SECTION`, `ENTITIES`, and final `EOF`.
- The 7-page V1.5.3 user guide was regenerated, rendered, and visually inspected page by page.
- Static security checks confirmed `FLAG_SECURE` is absent, `allowBackup=false`, and no Internet permission is requested; the PIN gate remains active.
- APK content checks confirmed the BOQ, DWG, DXF, NART font, and guide match their source files; the Android-recompressed overview map is pixel-identical to its source (1172 x 1501).
- Quantity entry uses `adjustResize`, dialog resize handling, and focused-field auto-scroll so the keyboard does not cover the active input.
- Static layout checks confirmed modern `systemBars + displayCutout` insets plus the Android 7-10 stable-inset fallback and dynamic header/bottom padding.
- Existing optimized maps and embedded NART font behavior are retained from V1.4.

## Required device checks

No Android phone, emulator, or ADB target was connected during final verification. Before field rollout:

1. Install V1.5.3 over the current app and confirm previous beneficiaries and photos remain available after the database upgrade.
2. Set a temporary six-digit PIN, close/reopen the app, test a wrong code, and then change the PIN from Settings.
3. Create a test study, use M², M3, MR, and No calculators, and test both replacing and adding their results.
4. Confirm a total of 827 displays the payments as 496 and 331 without decimal fractions.
5. Confirm incomplete studies are rejected and a complete study can be marked ready.
6. Use test data to confirm exactly 50 ready studies move into one follow-up batch.
7. Set progress to a partial value, save follow-up, then mark completed and confirm it becomes 100%.
8. Capture one image in each restoration phase and verify the generated project/beneficiary/stage folders.
9. Open both full and sector maps as PDF and test zoom/drag; verify both PDF and CAD buttons are visible.
10. Install a CAD viewer and open the embedded DWG and DXF options; also verify the clear fallback message when no viewer is installed.
11. Open all seven pages of the embedded Arabic user guide.
12. Export JSON, import it into a temporary project, and confirm BOQ, workflow stages, and progress are restored.
13. Print or save routes with 1, 25, and 26 beneficiaries, including a selected page range.
14. On the target phone, verify the five app navigation buttons remain fully above Android's three-button bar in portrait and landscape; repeat once with gesture navigation if available.

## Build

Set `YARMOUK_SIGNING_PASSWORD` in the build process, then run `BUILD_ANDROID.ps1` with PowerShell execution policy bypass if required. The script compiles all Java sources, packages all references, signs the APK, verifies signatures, checks package metadata, and validates required APK entries.

Keep `signing/yarmouk-field-release.jks` private and backed up. It is required to install future releases as updates to the same app.

## Repository state

This project currently has no `.git` directory, so there is no Git history, tracked/untracked state, or commit baseline. Initialize Git only after making a separate verified backup of the current release and private signing material.
