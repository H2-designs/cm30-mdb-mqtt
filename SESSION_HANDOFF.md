# Session Handoff — CM30 MDB Slave (Cashless Device #1)

Wrap-up of everything built/decided in this session, so a new conversation window can pick up
with full context.

## Where everything lives

```
C:\Users\Hamdan\Desktop\MVP\
├── CM30-HardwareLibrary-1.0.9.aar          ← original vendor library (armeabi-v7a native code)
├── Development specification for CM30-EN V1.0.7 (1).pdf   ← vendor's own spec (transport layer only)
├── HardwareLibraryDemo-v1.0.9\             ← original vendor demo app, left untouched
└── MdbSlaveApp\                            ← the actual project built this session
    ├── app\                                ← demo/test app, two build flavors
    │   └── src\
    │       ├── main\java\...\MdbSlaveActivity.kt      ← shared state machine + UI (both flavors)
    │       ├── device\java\...\MdbSlaveWrapper.kt      ← REAL transport (uses the vendor .aar)
    │       └── simulator\java\...\MdbSlaveWrapper.kt   ← FAKE transport (scripted, for emulator)
    ├── mdblib\                             ← standalone library for other developers
    │   ├── src\main\java\com\ciontek\mdblib\
    │   │   ├── MdbSlave.kt                 ← the public API (4 functions + combinePrice helper)
    │   │   └── MdbSlaveWrapper.kt          ← internal transport (real hardware only)
    │   ├── README.md                       ← developer-facing docs for the library
    │   └── build\outputs\aar\              ← mdblib-debug.aar / mdblib-release.aar
    ├── sampleapp\                          ← minimal sample app built ON TOP of mdblib
    │   └── src\main\java\com\ciontek\mdbsample\MainActivity.kt
    │       (initMDB on launch, listener auto-approves vends ≤ 20.00, polls fetchVendState
    │        every 500ms showing Dispensing…/Success/Failed, force-reset button;
    │        built APK: mdb-kotlin-sample-v1.0.apk in the project root)
    └── build.gradle (app module)           ← versionCode/versionName, bump before every build
```

A reference doc used throughout for protocol accuracy: `C:\Users\Hamdan\Downloads\MDB_Complete_v5.html`
(a comprehensive MDB spec — checksum algorithm, command tables, worked examples). Worth keeping
around for any future MDB work.

## What the demo app (`app` module) does

Two Gradle product flavors, same `MdbSlaveActivity.kt` (shared state machine + UI), different
`MdbSlaveWrapper.kt`:

- **`device`** — real CM30 hardware. `MdbSlaveWrapper` calls `android.hardware.mdbSlave.MdbSlave`
  from the vendor's `.aar`. Only installable on the real CM30 board (32-bit ARM, `armeabi-v7a`
  only — confirmed via ELF header inspection, no x86/x86_64/arm64 variant exists at all).
- **`simulator`** — no native dependency, installs on any emulator. Fakes a scripted VMC command
  sequence (`RESET → SETUP → EXPANSION → READER ENABLE → BEGIN SESSION → VEND REQUEST → VEND
  APPROVED → VEND SUCCESS → SESSION COMPLETE → END SESSION`, looping the vend cycle forever) so
  the whole state machine can be watched on screen without hardware.

**Current version: `versionCode 21` / `versionName "21.0"`.** Standing instruction from the user:
**always bump the version before building**, and always send back the freshest APK.

The on-screen UI shows: a live RX/TX log (idle `POLL → ACK` is suppressed entirely — too noisy —
everything else logs), a version label (`vX.0 (build N) - DEVICE/SIMULATOR`), and `open`/`close`/
`clear`/`vend approved` buttons. (`begin session` button was removed — session start is now fully
automatic, see below.)

### State machine (`MdbSlaveActivity.kt`)

Ported from the user's own Arduino C source, four states: `INACTIVE_STATE → DISABLED_STATE →
ENABLED_STATE → VEND_STATE`. Key behavioral decisions made along the way:

- **Session begin auto-fires** — no real card-tap detection exists yet, so `sessionBeginPending`
  is set automatically right after `READER ENABLE`, and **re-armed every time a session ends**
  (`END SESSION` → back to `ENABLED_STATE` → auto-arm again), so cycles repeat indefinitely. This
  applies on **both** device and simulator builds (explicit user decision — accepted that real
  hardware will claim a session began without a genuine card tap, until real detection exists).
- **Vend approval is button/listener-gated, not automatic.** The `vend approved` button (demo
  app) and `vendRequestListener` (library) are the only paths to `VEND APPROVED`. Fixed a real bug
  where pressing early (before any `VEND REQUEST`) got queued and fired automatically once a
  request arrived — now gated **at press time**, so an early press is discarded outright.
- **`VEND SUCCESS`/`VEND FAILURE` from the VMC** just get ACK'd (no state change) — neither closes
  the session. Only an explicit `SESSION COMPLETE` (`0x13, 0x04`) does that, per spec.
- Idle `POLL → ACK` is never logged (was flooding the screen). `JUST RESET` repeats for 3 polls
  before switching to plain ACK (was only sending it once).
- Checksum (`MdbSlaveWrapper.calculateChecksum`/`withChecksum`) is computed and appended as the
  **actual last byte** before every multi-byte send — confirmed via `jshell` arithmetic against
  the spec's worked example, not just asserted.

### Real bugs found and fixed via actual hardware testing

1. **`SETUP CONFIG DATA` silently rejected** any VMC feature level other than `0x02` (a real VMC
   reported level `0x03` and got ignored). Fixed to accept any level.
2. **`READER CONFIG INFO` packet was one byte too long** — the source C array had a
   pre-computed checksum baked in as a trailing byte, which got included as data and
   double-checksummed. Fixed by stripping it and letting `withChecksum()` compute the real one.
3. **`REVALUE LIMIT AMOUNT` used the wrong response ID** (`0x0E`, which is actually `REVALUE
   DENIED`) — corrected to `0x0F` per spec, and given an actual 2-byte limit value instead of
   nothing.
4. Traffic addressed to other MDB peripheral types (Bill Validator `0x30`, Coin Changer `0x08`,
   Cashless #2 `0x60`, etc. — the VMC probing the whole bus) is filtered out entirely rather than
   logged as "unhandled" — only our own address range (`0x10-0x17`) is shown at all.

## `mdblib` — the standalone library for other developers

A brand-new Android library module, separate from the demo app, exposing **exactly 4 things** for
an app developer to call — everything else (the whole protocol) is `internal`/private:

```kotlin
MdbSlave.initMDB(): Boolean                                  // start it
MdbSlave.vendRequestListener = VendRequestListener { ... }   // approve/deny — called synchronously
MdbSlave.fetchVendState(): Int?                              // read-once: null/1/2/3
MdbSlave.forceReset(): Boolean                                // close+reopen, automated
MdbSlave.combinePrice(priceHigh, priceLow): Double            // bonus helper for payment gateways
```

- `VEND_STATE_IN_PROCESS=1`, `VEND_STATE_SUCCESS=2`, `VEND_STATE_FAILURE=3`.
- `priceHigh`/`priceLow` passed into the listener are the **real bytes from the actual incoming
  VEND REQUEST** — the library extracts them off the wire and hands them to the developer's
  callback; the developer never supplies them.
- If no listener is set, vend requests are **denied by default** (safe default).
- Built with `minifyEnabled false` for both debug/release build types — the two AARs are
  functionally identical right now (no obfuscation either way); only real difference is minor
  debug metadata. Flagged that enabling real release hardening later needs consumer ProGuard
  rules verified against the CM30 native library's JNI method names.
- Full usage guide with working code examples: `mdblib/README.md`.

## Known limitations (told to the user, not silently hidden)

- **No real card-tap detection.** Session begin is purely state/timing-driven.
- **No refund-failure distinction.** `VEND FAILURE` (`0x13,0x03`) just ACKs (implying refund
  succeeded via the default idle-ACK) — the spec's `MALFUNCTION 0xC0-0xCF` ("refund failed, credit
  lost") path isn't implemented.
- A harmless `Variable 'b5' is never used` compiler warning persists in `MdbSlaveActivity.kt`
  (`scaleFactor` was wired up at one point, then the assignment got removed externally; the
  variable itself is cosmetic, doesn't affect behavior).

## Environment quirk worth knowing

The Android emulator in this sandbox **crashes/disconnects intermittently** — happened repeatedly
throughout the session. Recovery pattern that worked reliably: relaunch via
`Start-Process ... studio64.exe`-style detached process (not a plain backgrounded Bash command,
which gets killed when the tool call returns), wait for `adb wait-for-device` +
`getprop sys.boot_completed`, and if it won't come up, a full cold boot
(`-no-snapshot -wipe-data`) tends to recover it. Not a code problem — don't waste time debugging
the app over it.

## Suggested next steps (not started)

- Real card-tap detection to replace the auto-arm session-begin logic (in both `app` and `mdblib`).
- `MALFUNCTION 0xC0-0xCF` handling for genuine refund-failed reporting.
- Decide whether `mdblib` should ship as a real hardened release build (`minifyEnabled true`) once
  it's closer to production.
