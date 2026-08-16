# HANDOFF - Yarmouk Camp Shelter Android

## Current release

- Version: `1.3.0` (`versionCode 130`)
- APK: `dist/Yarmouk_Camp_Field_Android_v1.3.0.apk`
- SHA-256: `093A00B18B2D3021F1F66371460471E355F89496076C196A79537AA4E9AF1720`
- Package: `org.unrwa.yarmoukfield`
- Minimum Android: 7.0 / API 24
- Target SDK: 36

## Implemented in V1.3

- Manual beneficiary creation without an Excel list.
- User-selected persistent photo root using Android's Storage Access Framework.
- Automatic hierarchy by project, beneficiary ID/name, and restoration phase.
- Full-resolution camera capture for before, during, and after restoration.
- Photo metadata in SQLite: phase, content URI, filename, timestamp, and note.
- Stage photo counts, thumbnail list, and opening the original photo.
- `.nomedia` marker in each generated project folder for beneficiary privacy.
- JSON backup schema `1.1` with a photo index (the image files remain separate).
- Database migration from version 1 to 2 without dropping beneficiaries.
- Branded print header and correct handling of selected page ranges.
- Design credit: المهندس محمد أمين عودة بالتعاون مع المهندس عبد المالك أبو حرب.
- Existing seven-sector PDF mapping and XLSX XML reader fix retained.

## Verification completed

- Full Java/resource/DEX build succeeded.
- APK signature v2 and v3 verification succeeded with the existing release key.
- APK version, package, embedded maps, address JSON, DEX, and SHA-256 were checked.
- Folder naming policy test: `PHOTO_PATH_POLICY_OK`.
- Real workbook smoke test: 32 sheets, 1,598 rows, 37,559 cells.
- A4 landscape print-header preview with 25 rows was rendered and visually inspected.
- Delivery and private-source archives were opened and checked; embedded APK hash matches.

## Required device checks

No Android phone or emulator was connected during the final verification. Before field rollout:

1. Install V1.3 over an existing V1.1/V1.2 installation and confirm beneficiaries remain after the database upgrade.
2. Select a local photo folder, restart the phone, and confirm the retained folder permission still works.
3. Capture one image in each restoration phase, add a note, open the thumbnails, and verify the files in the chosen folder.
4. Cancel one camera capture and confirm no empty image remains.
5. Print or save routes with 1, 25, and 26 beneficiaries, including a selected page range.
6. Test portrait and landscape layouts on the intended field device.

## Build

Set `YARMOUK_SIGNING_PASSWORD` in the build process, then run `BUILD_ANDROID.ps1` with PowerShell execution policy bypass if required by the machine. The current script no longer embeds the signing password.

Keep `signing/yarmouk-field-release.jks` private and backed up. It is required to install future releases as updates to the same app.

## Repository state

This project currently has no `.git` directory, so there is no Git history, tracked/untracked state, or commit baseline. Initialize Git only after making a separate verified backup of the current release and private signing material.
