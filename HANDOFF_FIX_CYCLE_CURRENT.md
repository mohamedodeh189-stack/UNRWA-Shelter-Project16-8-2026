# Yarmouk Field Android — Fix Cycle Operational Detail (companion to HANDOFF_MASTER.md)

Read `HANDOFF_MASTER.md` first for full project context. This file is the operational detail: exact build/sign/install commands, exact device state, exact immediate next steps.

## Build / sign / install (exact commands)

```powershell
# Build (compiles/packages/dexes/aligns; the final signing step is EXPECTED to fail here on purpose):
cd "F:\New folder\Yarmouk_Camp_Shelter_Android"
$env:YARMOUK_SIGNING_PASSWORD='placeholder-not-real'
powershell -ExecutionPolicy Bypass -File .\BUILD_ANDROID.ps1
```
A clean run through dexing/aligning with only the final `apksigner` step failing on `keystore password was incorrect` = a fully successful compile. Verify permissions afterward:
```bash
aapt2 dump permissions build/yarmouk-aligned.apk   # must show ONLY ACCESS_FINE_LOCATION, READ_CONTACTS, WRITE_CONTACTS — never INTERNET
```

**Real signing** (only after the user explicitly approves *that specific install*):
```bash
python find_and_test_password.py   # recovers + verifies the real keystore password locally, saves to a local .verified_pw temp file — NEVER print it
python sign_and_install.py         # signs with the real key, verifies SHA-256 fingerprint == d2c1cfc6f33ba0fcc12efd33e12098ad6d50dd832a1f8e88feb774337c6e8566, then `adb install -r` (only if the fingerprint matches)
rm -f .verified_pw                 # delete the temp password file immediately, every time, no exceptions
```
These two helper scripts previously lived in a session-specific scratchpad directory that no longer exists — **recreate them from this logic** (they're short, self-contained Python scripts):
- `find_and_test_password.py`: extract `$StorePassword` from `release/Yarmouk_Camp_Field_Android_v1.0.0_Private_Source/BUILD_ANDROID.ps1` via `re.search(r"\$StorePassword\s*=\s*'([^']+)'", text)`, verify read-only with `keytool -list -keystore <path> -storepass <pw> -alias yarmouk-field`, write the verified password to a local temp file if it matches.
- `sign_and_install.py`: read that temp file, run `apksigner sign --ks <keystore> --ks-key-alias yarmouk-field --ks-pass pass:<pw> --key-pass pass:<pw> --out <signed.apk> <aligned.apk>`, run `apksigner verify --verbose --print-certs <signed.apk>` and programmatically compare the SHA-256 certificate digest against `d2c1cfc6f33ba0fcc12efd33e12098ad6d50dd832a1f8e88feb774337c6e8566`, and only if it matches run `adb -s <SERIAL> install -r <signed.apk>`.

**Install on a device**: always `adb -s <SERIAL> install -r <path-to-signed-apk>` — never a bare `adb install` when more than one device might be attached.

## Current device state (re-verify, don't trust this blindly — it goes stale fast)

- A dedicated **TEST DEVICE** (Realme RMX3430, serial `657HVWS8EEIVIBCQ` as of the last direct check) was adopted specifically so testing never risks real field data again, after an accidental-but-disclosed real GPS write happened on a different phone (see `HANDOFF_MASTER.md` §8). **Confirm this serial is still what's connected** with `adb devices -l` before sending it any command — do not assume.
- There is also an original Xiaomi/HyperOS phone (serial `kzt4xombeikboznv` as of the last direct check) that carries real field data, including that one disclosed-not-reverted GPS write on beneficiary "احمد موسى يوسف" (registration `1-00226023`). **Never send this phone a command unless the user explicitly reconnects it and says so.**
- Neither device was confirmed connected at the moment this file was last written — check fresh.
- **No app install had completed on the TEST DEVICE as of the last direct check in this project's history** — if that's still true, it's the very first thing to do (after confirming which serial is the right target).

## Immediate next steps (in order)

1. `adb devices -l` — identify exactly which device(s) are connected and confirm status is `device` (not `unauthorized`). If more than one device is attached, get explicit confirmation from the user on which one is the TEST DEVICE before sending any other command.
2. Build fresh from the current F: source (§ above) — don't assume a previously-built APK is still current.
3. Verify permissions (`aapt2 dump permissions`) show no `INTERNET`.
4. Sign with the real key only after explicit user approval for that specific install, following the protocol above exactly.
5. `adb -s <SERIAL> install -r <signed.apk>` on the confirmed TEST DEVICE only.
6. Start a fresh diagnostic logcat capture before testing anything: `adb -s <SERIAL> logcat -s YarmoukDiag -v time > <local logfile> &`
7. Work through `HANDOFF_MASTER.md` §4's device test checklist, using only TEST-labeled beneficiary records for anything that writes data.
8. Produce a PASS/FAIL/NOT-TESTED report per checklist item once done, and stop for approval before any further signing/install or before starting the §7 backlog.
