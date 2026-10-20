# Nasser · handoff

## Who you are
The engineer for hardware-lib, mqtt-lib, the demo app, mdblib and the samples. You build;
Dana approves.

## The architecture you are protecting
```
VMC bus -- CM30 serial -- MdbSlaveWrapper -- HardwareLib (state machine, NO networking)
                                                |  listeners out / functions in
                                     MdbMqttBridge (about 30 lines, lives in app/)
                                                |
                                            MqttLib <-> broker <-> dashboard
```
Every exchange carries a stable integer CMD code from `MdbCmd` (110–136 today). Codes are
append-only. The MQTT payload the dashboard sees is byte-identical across refactors.

## Where things are
- Engine: `hardware-lib/src/main/java/com/rabbah/mdb/HardwareLib.kt`. `VERSION` constant
  near line 42. `simulateExchange(rxHex, txName)` near line 2082 pushes a fake RX frame
  through classify, template and listeners synchronously on the calling thread.
- Schema: `MdbCmd.kt`. Transport: `MdbSlaveWrapper.kt` (calls
  `android.hardware.mdbSlave.MdbSlave` from the vendor AAR; `calculateChecksum` and
  `withChecksum` live here).
- Pulse: `PulseLib.kt`, engine-owned timing on a `PulseWorker` thread, ready-feedback gate
  via `digital_in_get_value`. Do not use the vendor `digital_out_pulse` for trains (D-006).
- MQTT: `mqtt-lib/src/main/java/com/rabbah/mqtt/MqttLib.kt`, `VERSION` near line 87. Topics:
  `<prefix>/<id>/logs` out, `<prefix>/<id>/passthrough` in (dashboard), `cmd` (backend-owned),
  retained `status`.
- Demo app version: `app/build.gradle` lines 18–19 (`versionCode 104`, `versionName "2.17.0"`).
- Deliverables: `dist/`. Newest are `hardware-lib-8.2.1.aar`, `mqtt-lib-2.7.1.aar`,
  `MDB-Slave-2-v2.17.0-build104-debug.apk`.
- Integration guide you keep true: `LIBRARY_README.md` (about 590 lines; the CMD table and
  the listener table are the parts integrators actually read).

## Build facts
- `./gradlew :hardware-lib:assembleRelease`, `:mqtt-lib:assembleRelease`, `:app:assembleDebug`.
- `minifyEnabled false` everywhere for now. Enabling R8 later needs consumer keep rules
  checked against the vendor JNI names (`hardware-lib/consumer-rules.pro`,
  `mqtt-lib/consumer-rules.pro`).
- The vendor AAR is armeabi-v7a only. Expected behaviour on an x86 emulator: the app
  installs, `HardwareLib.start()` reports `open() failed` through the log listener, and
  `simulateExchange` still works. Verify this on your first session and correct this
  paragraph if it is wrong.
- Emulator AVDs on this machine: `Rabbah_POS_2` to `Rabbah_POS_6`, `Medium_Phone_API_36.1`.
  The emulator has crashed intermittently in the past: relaunch detached, `adb wait-for-device`,
  and cold boot with `-no-snapshot -wipe-data` if it will not come up. Not a code problem.

## Lessons the owner paid for on real hardware (do not re-learn them)
1. SETUP CONFIG DATA must accept any VMC feature level, not just 0x02.
2. Never bake a checksum into a byte array; `withChecksum()` appends the real one.
3. REVALUE LIMIT AMOUNT is reply 0x0F with a 2-byte limit. 0x0E is REVALUE DENIED.
4. The vendor pulse call sends the wrong count and ignores direction. Engine timing only.
5. Single-slot listener vars get silently overwritten; the bridge uses the add/remove API so
   the vars stay free for integrators. Keep it that way.
6. An early "vend approved" must be discarded, never queued (D-002).

## Open items for you
- Nothing assigned. Do not start work until an issue is `in-progress` with your name on it.
- Q-003 (unit tests) will likely be your first task: JVM tests for `withChecksum` against
  the spec's worked example and for `MdbCmd` classification of the frames in the CMD table.
