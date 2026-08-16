# Yarmouk Camp Field Android — MASTER Handoff (full project history)

**Purpose**: hand this whole project to a fresh Claude session on a different account, so development can continue there without re-deriving anything. Read this document fully before touching code, building, or connecting to any device.

> ⚠️ **Path note (read first)**: `F:\New folder\Yarmouk_Camp_Shelter_Android` (where you are reading this) is now the **only** copy of this project. A second copy on `C:\Users\Administrator\Pictures\New folder\Yarmouk_Camp_Shelter_Android` existed in parallel and was **deleted 2026-08-16** after a full file-by-file diff confirmed F: was a strict superset (F: was DB_VERSION 20 vs C:'s 19, and every differing file had F: containing 100% of C:'s content plus more — every bug fix from the fix cycle described below was independently verified already present in F:). Do not look for the C: copy, it is gone. All builds/edits happen on F: from now on.

There is a **separate, unrelated project**: the Windows desktop app (`Yarmouk_Camp_Shelter_Project_V9_0_Stable_Strict`), Python/Tkinter, with its own handoff (`HANDOFF_CURRENT.md`), which independently went through the exact same "consolidate onto F:" situation earlier. Do not confuse the two projects.

---

## 0. What this app is, in one paragraph

`Yarmouk_Camp_Shelter_Android` (package `org.unrwa.yarmoukfield`, versionName "19.1", versionCode 1901, currently `DB_VERSION` 20) is a native Java, offline-first Android field app for UNRWA engineers running shelter-repair studies in Yarmouk refugee camp: beneficiary intake → house study (address, damage description, photos, BOQ quantities, house plan attachment) → office review → approval → batching into "متابعة" (follow-up execution) → BOQ item delivery tracking → first/second payment milestones → real GPS home-location pinning → an operational map with sector boundaries and a GPS-density heatmap → route planning → contacts sync → multi-engineer sync export for merging on the Windows desktop app. No Gradle — a custom PowerShell build script (`BUILD_ANDROID.ps1`) drives `aapt2`/`javac`/`d8`/`zipalign`/`apksigner` directly. As of this writing: `MainActivity.java` ~3,634 lines, `AppDatabase.java` ~1,180 lines (both built up over many incremental phases — see §2).

## 1. Absolute standing rules (apply to every future session on this project)

- **Real beneficiary data is sacred.** Never create, edit, or delete it as a side effect of testing. Any write-testing must use a clearly TEST-labeled record (e.g. `TEST ACCEPTANCE DONOTUSE`), and ideally happen on a dedicated TEST DEVICE, not a phone carrying real field data.
- **Never display, print, or log the real APK signing password.** It lives in `signing/yarmouk-field-release.jks` (alias `yarmouk-field`). Recovery/use protocol is in §9 below — follow it exactly, and always delete the temp password file immediately after signing.
- **Never install a build on any device without the user's explicit go-ahead for that specific install.**
- **No refactors, no scope creep, no unrequested features.** Every change in this project's history has been either a targeted, named bug fix or something explicitly requested. A backlog of explicitly-deferred future features exists (§7) — do not implement any of it without explicit approval to start that phase.
- **One phase / one fix at a time, with an approval gate**, historically enforced very strictly by the user throughout this project. Don't bundle unrelated work into one change.
- **Additive-only DB migrations.** `DB_VERSION` only ever goes up, columns are only ever added, never dropped/renamed, existing data must survive every upgrade untouched.
- **No `INTERNET` permission, ever.** The app is offline-first by design; every build must be checked with `aapt2 dump permissions` before considering it finished.
- **Before deleting or overwriting anything that might represent independent work** (a second copy of the project, an unfamiliar file/branch), diff/compare first — don't assume, verify. This exact discipline is what caught the C:/F: divergence described above before it caused real data loss.
- Real-device testing convention: get exact tap coordinates from `adb shell uiautomator dump` + the `bounds="[x1,y1][x2,y2]"` attribute, never by eyeballing a screenshot — this hardware flips between portrait (1080×2400) and landscape (2400×1080) unpredictably, and stale coordinate assumptions cause mis-taps (this has caused a real incident — see §8).

## 2. High-level feature history (chronological, all DONE unless marked otherwise)

This app was built up in many small, gated phases. Grouped by theme rather than strict date order:

### Core workflow (studies → followup → payments)
- Beneficiary study lifecycle: draft → ready → office_review → approved, enforced at the DB level (no state can be skipped). A corrected revision of an approved study creates a new row (`study_uuid` stays constant, `revision_uuid` changes, `revision_no` increments, old row becomes `superseded` and is kept forever as a read-only historical snapshot — quantities/BOQ copied not moved, photos linked not duplicated).
- "دفعة 50" batching: once 50 studies are approved, they move together from "الدراسات" to "المتابعة" (execution/follow-up stage).
- BOQ delivery tracking per item (5 states: لم يُسلَّم/تم تسليمه/قيد التنفيذ/جاهز للاستلام/تم استلامه), work-delivery confirmation, first-payment-availability confirmation, a 35-day execution counter that only starts once **both** are confirmed (never auto-started), second-payment approval with a full audit trail (approve/revoke both logged, never a silent overwrite).
- Study Team + office review fields (DB v16→17): engineer names, social researcher, smart date field, in-place office-review approval action.

### Address / classification
- Address classifier + resolution system (`AddressClassifier`, `AddressResolution`): fuzzy match against the reference sector/street plan, with an explicit `needs_review` status whenever confidence is low or a conflict is detected. A separate `address_corrections` table stores only human-confirmed corrections (never auto-written). `original_address` is set once at creation and is immutable forever after — display-only, never edited or deleted, shown in a locked/protected UI card.
- **Fixed in the most recent cycle**: `saveStudy()` used to re-run the classifier and silently overwrite `sector`/`street` on every single save, even with unchanged address text. Fixed: only reclassifies when the address text actually changed, and never silently replaces a previously human-confirmed correction — forces `needs_review` instead. See §3.

### GPS / mapping
- Real GPS home-location pinning (`GpsLocationHelper`, GPS_PROVIDER only, no NETWORK_PROVIDER, no internet needed), with an accuracy warning above 30m requiring explicit accept/retry, and explicit confirmation before ever overwriting an existing pinned location (this confirm step now also applies to a good-accuracy re-capture, not just a weak one — most recent addition, see §3).
- Map calibration (`MapCalibration`): similarity-transform fit (uniform scale+rotation+translation, deliberately not a 6-parameter full affine, so it shows genuine non-zero residuals even at exactly 3 points) from ≥3 real, field-surveyed reference points. Requires ≥3 points before any GPS-based map feature activates — never guesses or invents a calibration.
- Operational map (`OperationalMapView`): sector boundary polygons (digitized from the project's DXF via `ezdxf`/`matplotlib`/`cv2`, with a corrected area-weighted/shoelace centroid formula after catching a naive-average bug that put a centroid outside its own concave polygon), beneficiary points (real GPS shown distinctly from sector-centroid-estimated points, never conflated), and a real-GPS-only density heatmap (estimated points never contribute).
- Separately, `SectorMapView` (the sector *guide*, not the operational map) draws its own canvas-painted sector name badges — this is the file that had an Arabic-RTL-reversed-label bug fixed this most recent cycle (a leading `\u200F` RTL mark fixed a mixed LTR-digit/RTL-Arabic bidi ordering bug — a naming mix-up along the way: it was initially misattributed to "OperationalMapView", which draws no text at all).
- "خطط جولتي" (route planning): nearest-neighbor ordering using only beneficiaries with a real GPS fix; beneficiaries without one are shown in a clearly separate section, never given an invented position.
- Geographic statistics: per-sector beneficiary counts, single-list or all-lists, tap-through to the matching beneficiary list.
- **Most recent addition**: a "📍 فتح الموقع للملاحة" (open in navigation) button using a standard `geo:` URI, GPS unified identically across both study and followup screens, "إعادة تحديد الموقع" re-capture with an explicit new-accuracy confirm before ever overwriting an existing fix, and a matching navigation button on the map's point-summary popup (only for real, non-estimated points). Required a manifest `<queries>` fix (Android 11+ package visibility) — see §3.

### Photos
- In-app Camera2-based sequential photo capture (`SequentialCameraActivity`): no per-shot confirmation, camera stays open, auto-saved to the right phase folder, with EXIF-based JPEG orientation normalization (physically rotates/flips the saved pixels so captures look correct everywhere — in-app, in the exported package, on a desktop viewer — independent of any viewer's own EXIF handling; imported reference photos are never touched by this).
- Photo picker with real thumbnails (not blind "صورة N" labels).
- Reference "before" photos extracted from embedded Excel images on import, with a "photograph the same angle" re-capture flow that never overwrites the original reference.
- Photo folder chosen once via SAF, auto-organizes into a fixed per-beneficiary structure; photo capture/study-start gated on the root still being usable, with re-pick on lost permission.
- **NOT yet implemented** (explicitly deferred, see §7): rapid-fire "burst" camera mode with a running counter, no per-shot confirmation, and a single phase-tag applied to the whole burst — distinct from the sequential capture above, which still requires a folder-root setup step and doesn't yet have the "single tag persists across the whole burst" UX explicitly requested for the next phase.

### Import / export / sync
- Offline XLSX/CSV/JSON importer (`SpreadsheetImporter`, hand-rolled, no third-party library) — handles both flat multi-beneficiary tables and a single-beneficiary-per-workbook shape (labeled evaluation form + BOQ sheet), with fuzzy header-matching and an optional field-definitions sheet for extending aliases.
- JSON backup/restore covering studies, followup, quantities, photo index.
- Multi-engineer sync: an isolated `sync/` package with a clean `SyncTarget` domain interface (zero Android/Intent/DB dependency), `study_uuid`-grouped + `revision_uuid`-deduped packages, `ShareIntentDesktopAdapter` as an explicitly-provisional/replaceable transport, `schema_version` in every package, and Windows-side merge logic (`sync_import.py` in the desktop project) that dedupes by `revision_uuid` and refreshes (never freezes) a row's data on re-import — a real bug here (a superseded status not propagating on re-sync) was found and fixed via true end-to-end testing.
- **Most recent addition**: `.zip` archive import support (`ZipImportHelper`, new file) — extracts supported files (`.xlsx`/`.xlsm`/`.xls`/`.csv`/`.json`, `~$`/`~`-prefixed Excel lock/temp files skipped) safely (Zip Slip guarded via basename-flattening + canonical-path recheck; Zip Bomb guarded via streaming uncompressed-size counters, not a raw compressed-size cap) and hands the chosen file to the *same* `SpreadsheetImporter.read()`, never a parallel importer.

### Contacts sync
- Optional, fully permission-gated phone contact sync, dedup by phone number (update not duplicate). Naming scheme ("F6", an earlier phase): dialer-friendly `<short name> ق<list number> مستفيد` as the contact NAME (never the registration number or full address), with registration/sector/address/follow-up-status detail placed in the contact's **Note** field via `contactNote()`.
- **Most recent fix(es) — two separate real-device platform bugs, found via real logcat evidence, not guessed**: (1) Android 14+ rejects a `RawContacts` insert with the old `ACCOUNT_TYPE=null/ACCOUNT_NAME=null` "local contact" idiom when the device's default-contacts-account setting is a cloud account (`IllegalArgumentException: Cannot add contacts to local or SIM accounts when default account is set to cloud`) — fixed by no longer forcing those fields to null. (2) On a second device, omitting the values entirely (rather than an explicit empty set) caused a `NullPointerException` inside `ContentProviderOperation`'s own batch marshalling — fixed with an explicit `.withValues(new ContentValues())`. Diagnostics (`SyncResult.failed`/`lastError`, PII-free logged exceptions) were added alongside both fixes so any future failure is immediately diagnosable instead of a blank "تعذّرت المزامنة".

### Security / app protection
- Optional 6-digit PIN gate (PBKDF2-hashed, salted, retry-throttled, 3-minute background auto-lock), opt-in from Settings, never forced on first launch.
- **Most recent fix**: the setup dialog used to trap the user (protection flagged "enabled" before any PIN was even saved, first-time dialog had no cancel path at all, causing a real observed lock-out incident). Fixed with a self-healing state machine: `enabled` only ever flips true after `setPin()` succeeds; the dialog is always cancelable with an explicit "إلغاء"; a device already stuck in the broken state self-heals the next time the dialog is shown and cancelled.

### House drawing / plan tool
- A field sketch tool exists today only as an **attach-a-file** flow (PDF/DXF/DWG/image linked to a study) — there is **no in-app manual drawing tool yet**. A full finger-drawn wall/measurement sketch tool with undo/redo was scoped and explicitly deferred (§7) — not started.

### UI / presentation
- Full visual identity pass: cover photo + header (UNRWA gate image, gradient, logo overlay), designer credit line, Latin-numerals-only rule, light/dark/system theme support (found and fixed an Android Force-Dark fight), font-size setting, home dashboard with animated stat cards and quick-action shortcuts, custom launcher icon (circular UNRWA medallion) replacing the old vector flag icon.
- An interactive in-app user guide plus a bundled offline PDF guide, reorganized around a "مسار العمل السريع" (quick workflow path) box summarizing the whole pipeline at a glance.
- **Most recent addition**: Settings "danger zone" — the destructive "حذف بيانات المشروع الحالي" action visually separated (its own labeled section, extra spacing) from routine settings, no behavior change.
- **Explicitly deferred, not yet done** (§7): remove the acknowledgement/thanks text from the header/cover (keep it in the About page, whose content will be *replaced* later with new text the user will supply — do not treat the current thanks text as final or say it needs no changes).

## 3. The most recent work: Final Device Acceptance Test → Fix Cycle (all of this is now merged into F: and reflected in §2 above)

A full Final Device Acceptance Test was run on a real Xiaomi/HyperOS phone across all the features in §2. It found real bugs: contacts sync completely broken, PIN setup trap, GPS navigation button non-functional, Arabic map label reversal, the address-reclassification-on-every-save bug, and a still-unsolved mystery of a TEST study record disappearing from the UI. A fix cycle followed: each confirmed bug was root-caused from real evidence (never guessed — the contacts-sync root cause specifically was only found by adding diagnostic logging and reading the actual exception off real device logcat), fixed, covered by a new regression test (both standalone-JVM and Python-mirror styles — see §6), and the app was rebuilt and signed with the real key.

**Status as of the last direct verification in this session**:
- **Fixed and confirmed present in the current F: source**: PIN setup self-heal, GPS navigation `<queries>` manifest fix, `saveStudy` no-reclassify-on-unchanged-address guard, `SectorMapView` RTL fix, both `ContactsSyncHelper` fixes (null-account + NPE), `ZipImportHelper` + its wiring, diagnostic logging (`YarmoukDiag` tag) across `AppDatabase.java`'s state-transition/delete paths.
- **Still unresolved (highest-priority open item)**: the disappearing-study bug. Every legitimate DB write path was reviewed and ruled out for a `draft`/`study`-stage row; the raw SQLite file could not be inspected directly (`android:debuggable=false`, `android:allowBackup=false`). Diagnostic logging is live and waiting to catch a reproduction.
- **Partially unresolved**: the general address-reclassification mechanism *was* found and fixed (the `saveStudy` bug above), but the *specific* real beneficiary instance originally observed (a `followup`-stage record, whose screen doesn't even call the classifier) remains unexplained by that fix alone. `diag("ADDRESS_RECLASSIFIED",...)` logging is live to catch any future occurrence.
- **Audited, no bug found**: the suspected "Arabic turning into Chinese-looking characters" issue — every text path already uses correct UTF-8/Unicode handling; a regression test locks this in.
- **Device re-testing status**: a dedicated TEST DEVICE (Realme RMX3430) was adopted mid-cycle specifically to avoid further risk to a real phone carrying field data (see §8 for why). Device-by-device PASS/FAIL confirmation for each fix above should be treated as **not fully re-verified** going into a fresh session — re-run the device test checklist (§4) before trusting any of the above as "done" in a shipped sense.

## 4. Device test checklist (re-verify these fresh, don't assume prior PASS carries forward)

1. PIN setup: enable, then cancel via BACK and via the explicit "إلغاء" button, at each point confirming protection stays OFF and no re-prompt trap occurs on relaunch.
2. GPS navigation button: a beneficiary with a real GPS fix → "فتح الموقع للملاحة" opens a real maps app at the right coordinates; a beneficiary with none → clear message, no app launch attempt, no invented location; re-capture ("إعادة تحديد الموقع") shows the new accuracy and requires explicit confirm before overwriting; cancel leaves the old fix untouched; a failed/timed-out capture leaves the old fix untouched.
3. ZIP import: a valid single-file zip, a multi-file zip (picker shown), a corrupt zip (clean error, no crash), a Zip-Slip-crafted zip (never escapes the sandbox), an oversized entry (rejected mid-stream, not from a spoofed header).
4. SectorMapView: visually confirm sector name badges read correctly (no reversed/garbled Arabic).
5. Address no-reclassify: open and re-save a study's own address multiple times unchanged → sector/street/confidence never change; actually edit the address → does reclassify; edit an address that had a prior human-confirmed correction → forces `needs_review`, never a silent new guess.
6. Contacts sync: sync a TEST record, confirm a real contact is created; re-sync the same record, confirm it updates (not duplicates); if it fails, `adb logcat -s YarmoukDiag` should show a specific, non-generic exception.
7. Settings danger zone: visually confirm "حذف بيانات المشروع الحالي" is separated from routine settings, and its existing confirm dialog still works.
8. General regression: app launches without crash, real data (if any is present on the device under test) renders correctly, no unrelated feature broke.

Use only TEST-labeled beneficiary records for anything that writes data; on a dedicated TEST DEVICE this is easy (freely create/delete/reinstall), on a real field phone it must be done with extreme care (see §8 for a real incident that happened from a coordinate mis-tap).

## 5. Codebase map (key files)

- `AppDatabase.java` — SQLiteOpenHelper, all schema + queries, `DB_VERSION`-gated `onUpgrade` (currently 20), diagnostic logging.
- `MainActivity.java` — the entire UI, built programmatically (no XML layouts), one method per screen/dialog.
- `AddressClassifier.java` / `AddressResolution.java` — pure-Java, no Android dependency, directly unit-testable.
- `SpreadsheetImporter.java` — XLSX/CSV/JSON parsing, no third-party library.
- `ZipImportHelper.java` — ZIP container extraction, hands off to `SpreadsheetImporter`.
- `ContactsSyncHelper.java`, `GpsLocationHelper.java`, `MapCalibration.java`, `SectorBoundaries.java`, `OperationalMapView.java`, `SectorMapView.java`, `BeneficiaryPhotoManager.java`, `BeneficiaryPackageExporter.java`, `SequentialCameraActivity.java`, `AppSecurity.java`, `PrintRouteAdapter.java`, `PdfMapViewer.java`, `QuantityCatalog.java` — one focused responsibility each.
- `sync/` package — `SyncTarget`, `StudyRevisionPackage`, `SyncResult`, `SyncPackageJson`, `SyncPackageBuilder`, `SyncFileProvider`, `ShareIntentDesktopAdapter`.
- `tests/` — a large suite of standalone JVM tests (pure-Java classes, compiled directly against the JBR + `android.jar`, no emulator needed) and Python mirror tests (for Android-dependent logic that can't run outside a device — an established, deliberate pattern in this project, not a compromise).

## 6. Test suite conventions

Two kinds of tests, both already numerous in `tests/`:
1. **Standalone JVM tests** (`*Smoke.java`) for pure-Java, zero-Android-dependency classes (`AddressResolution`, `MapCalibration`, etc.) — compiled with the JBR (`C:\Program Files\Android\Android Studio\jbr\bin\javac`) directly against the app source + `build/android.jar`, run directly, no emulator.
2. **Python mirror tests** (`*_smoke.py`) for Android-dependent logic (Context/Uri/ContentResolver/AlertDialog-coupled code) that can't run outside a real device — the exact same decision logic/algorithm is faithfully mirrored in Python and exercised there, including against real crafted files (e.g. real zip archives built with Python's `zipfile` module for the ZIP import tests, including malicious ones). This is an established, intentional pattern in this project (not a stopgap).

Always run the **full** suite after any change, not just new tests — `for f in tests/*.py; do python "$f"; done` plus re-compiling the JVM smokes — to catch regressions.

## 7. Explicitly deferred next-phase backlog (recorded, NOT started)

Recorded in memory as `yarmouk_next_phase_backlog.md`, approved to *record* but explicitly **not** to implement until the user gives the go-ahead to start that phase:

1. **Manual house-plan sketch tool**: finger-drawn wall sketching inside the study screen with real measured lengths entered manually, undo/redo, edit/delete elements, saved with the study. Explicitly *not* a CAD tool — must stay a fast field tool.
2. **Measurements → quantity suggestions**: each wall/element tied to a real meter measurement, usable *later* to suggest areas/quantities — but BOQ must never be auto-modified without explicit engineer approval.
3. **Rapid-fire camera mode**: open camera once, each shot saves immediately, camera stays open for the next shot (no per-photo confirmation, no re-opening), a running saved-count and a "إنهاء" finish button, review/delete after the session.
4. **Photo phase tagging for burst mode**: pick "قبل الترميم / أثناء الترميم / بعد الترميم / وثائق" once before a burst session; it applies to every photo in that burst until changed or the session ends.
5. **Remove the header/cover thanks text**: clean up the top of the app; keep the About page's thanks section, whose content will be *replaced* later with new text the user will supply — do not treat the current thanks text as final or say it needs no changes.
6. **Field-visit checklist**: help the engineer confirm required photos/measurements/data are complete before ending a visit.

## 8. Hard-won lessons worth carrying forward

- **Coordinate/orientation drift causes real incidents.** A mis-tap during this project accidentally wrote a real GPS location to a real beneficiary ("احمد موسى يوسف", registration `1-00226023`, accuracy 74.6m, app-flagged weak — a first-time pin, not an overwrite of prior good data; disclosed immediately, not reverted). This is exactly why the user later insisted on a dedicated TEST DEVICE separate from any phone carrying real data. Always re-dump `uiautomator` bounds before tapping when screen state is even slightly uncertain (after backgrounding, after a rotation, after any unexpected delay).
- **Diagnose from real evidence, never guess.** The contacts-sync root causes were only found by adding safe logging and reading the actual exceptions off real devices — the user explicitly and repeatedly insisted on this discipline ("لا تفترض... افحص logcat... حدد السبب الجذري الدقيق") and it worked: both fixes were correct on the first attempt because they targeted the exact documented Android platform restrictions, not a guessed-at OEM quirk.
- **A plausible-looking bug candidate can be wrong.** During the disappearing-study investigation, the "اعتماد الدراسة" button looked like a strong candidate but was proven, by reading its actual validation logic, to be incapable of causing the symptom — ruling out a candidate rigorously is as valuable as confirming one.
- **Package-visibility (Android 11+) and default-account (Android 14+) platform changes are a recurring theme.** Both the GPS navigation button and the contacts sync bugs trace back to relatively recent Android platform security/privacy changes that silently break code patterns that used to just work. Worth checking any *other* implicit-intent or account-touching code in this app against the same two changes proactively if similar symptoms ever resurface.
- **Never assume a second copy of a project is disposable.** The C:/F: divergence this document opens with was caught only because a full diff was done before deleting anything — the naive assumption ("the one I've been editing must be current") would have been wrong and would have destroyed real independent work (F: was actually ahead).

## 9. Signing password protocol (never violate this)

The real keystore password must never be displayed, printed, or logged, at any point, by any assistant working on this project. The established, safe recovery-and-use pattern:
1. Extract the password from `release/Yarmouk_Camp_Field_Android_v1.0.0_Private_Source/BUILD_ANDROID.ps1` via a Python regex (`\$StorePassword\s*=\s*'([^']+)'` — never a shell/grep pattern, which has a proven escaping-bug history in this project).
2. Verify it read-only via `keytool -list -keystore <path> -storepass <pw> -alias yarmouk-field` before trusting it.
3. Write it to a local temp file only, use it only via subprocess args/env for `apksigner sign`.
4. Verify the resulting APK's SHA-256 certificate fingerprint programmatically (string comparison against the known-good value `d2c1cfc6f33ba0fcc12efd33e12098ad6d50dd832a1f8e88feb774337c6e8566`) — never by eyeballing.
5. Only `adb install -r` (never a fresh install that would wipe data) if the fingerprint matches.
6. Delete the temp password file immediately afterward, every single time, success or failure.

## 10. Where to start on a fresh session

1. Confirm you're reading this from `F:\New folder\Yarmouk_Camp_Shelter_Android` — if anyone points you at a C: path for this project, that copy no longer exists; redirect to F:.
2. Re-verify anything in §3 you're about to rely on against the actual current source rather than trusting this document blindly — it was accurate at write time but code moves fast in this project.
3. Check `adb devices -l` for the current TEST DEVICE identity before sending any command to a phone; never assume a device serial from a prior session is still the right target.
4. Work through §4's device test checklist for whatever the user asks about, using only TEST-labeled data.
5. Do not start §7's backlog without explicit approval to begin that phase.
