# HANDOFF - Yarmouk Camp Shelter Android

## Current release

- Version: `1.3.1` (`versionCode 131`)
- APK: `dist/Yarmouk_Camp_Field_Android_v1.3.1.apk`
- SHA-256: `722019F236D6E267D58A968872DA2E0DE4F00BCFECF4301AFE814CCD90D8B7E4`
- Package: `org.unrwa.yarmoukfield`
- Minimum Android: 7.0 / API 24
- Target SDK: 36

## Implemented in V1.3.1

- Professional header hierarchy with enough fixed space for the project title and identity.
- Primary credit: تصميم وتطوير التطبيق: المهندس محمد أمين عودة.
- Separate collaboration credit: بالتعاون مع المهندس عبد المالك أبو حرب.
- Sector circles are now tappable and open the matching real PDF page directly.
- The selected sector is highlighted and synchronized with the sector selector.
- The in-app user guide now explains the XMLReader fix and the XLS-to-XLSX requirement.

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
- Design and development credit: المهندس محمد أمين عودة; collaboration: المهندس عبد المالك أبو حرب.
- Existing seven-sector PDF mapping and XLSX XML reader fix retained.

## Verification completed

- Full Java/resource/DEX build for V1.3.1 succeeded.
- APK signature v2 and v3 verification succeeded with the existing release key.
- APK version, package, embedded maps, address JSON, DEX, and SHA-256 were checked.
- Folder naming policy test: `PHOTO_PATH_POLICY_OK`.
- Real workbook smoke test: 32 sheets, 1,598 rows, 37,559 cells.
- A4 landscape print-header preview with 25 rows was rendered and visually inspected.
- Delivery and private-source archives were opened and checked; embedded APK hash matches.

## Required device checks

No Android phone or emulator was connected during the final verification. Before field rollout:

1. Install V1.3.1 over an existing installation and confirm beneficiaries remain after the update.
2. Select a local photo folder, restart the phone, and confirm the retained folder permission still works.
3. Capture one image in each restoration phase, add a note, open the thumbnails, and verify the files in the chosen folder.
4. Cancel one camera capture and confirm no empty image remains.
5. Print or save routes with 1, 25, and 26 beneficiaries, including a selected page range.
6. Tap all seven sector circles, verify that each opens the matching PDF page, and test pinch zoom/drag.
7. Test portrait and landscape layouts on the intended field device.

## Build

Set `YARMOUK_SIGNING_PASSWORD` in the build process, then run `BUILD_ANDROID.ps1` with PowerShell execution policy bypass if required by the machine. The current script no longer embeds the signing password.

Keep `signing/yarmouk-field-release.jks` private and backed up. It is required to install future releases as updates to the same app.

## Repository state

This project currently has no `.git` directory, so there is no Git history, tracked/untracked state, or commit baseline. Initialize Git only after making a separate verified backup of the current release and private signing material.
