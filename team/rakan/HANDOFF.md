# Rakan · handoff

## Who you are
Tech lead. You run tasks through Nasser and Dana, you own the technical calls, you escalate
to the owner only when the limits in `team/README.md` say so.

## Repo map you must know cold
- `hardware-lib/src/main/java/com/rabbah/mdb/`: `HardwareLib.kt` (engine, over 2000 lines,
  `simulateExchange` near the end), `MdbCmd.kt` (the append-only code schema),
  `MdbSlaveWrapper.kt` (vendor transport), `MdbConfigStore.kt`, `MdbSettings.kt`,
  `PulseLib.kt`, `Rs232Lib.kt`, `Rs232Cmd.kt`.
- `hardware-lib/libs/CM30-HardwareLibrary-1.0.9.aar`: vendor driver, armeabi-v7a only. AARs
  do not nest, so integrators add it separately.
- `mqtt-lib/src/main/java/com/rabbah/mqtt/MqttLib.kt` (`VERSION` near line 87), `RabbahLog`,
  `RabbahMqtt`.
- `app/src/main/java/com/ciontek/HardwareDemo/`: `MdbSlaveActivity.kt` (demo UI),
  `MdbMqttBridge.kt` (the only coupling, D-005).
- Root `build.gradle` declares a `flatDir` repo on `hardware-lib/libs` so every module
  resolves the vendor AAR transitively.
- Toolchain: JDK 17, Gradle 8.13, AGP 8.5.2, Kotlin 1.9.24, compileSdk 36, minSdk 30.

## State of the repo at setup (2026-10-21)
- Remote `H2-designs/cm30-mdb-mqtt`, branch `main`, ONE commit. Everything newer (the
  hardware-lib rename, pulse/rs232 samples, bridge, newer dist artifacts) is uncommitted in
  the owner's working tree. See Q-001. Until it lands, do not start tasks.
- No tests. No CI. No labels yet on GitHub (`team/README.md` has the commands).
- `SESSION_HANDOFF.md` is partly stale (simulator flavor removed).

## Technical rules of thumb (from the owner's own bug history)
- Checksums are computed by `withChecksum()` at send time; never bake one into a byte array.
- Accept any VMC feature level in SETUP CONFIG DATA (a real VMC reported 0x03).
- REVALUE LIMIT AMOUNT reply id is 0x0F, not 0x0E.
- Traffic for other peripherals (0x30 bill validator, 0x08 coin changer, 0x60 cashless #2)
  is filtered, not logged.
- Idle POLL to ACK is never logged; JUST RESET repeats for 3 polls.
- Anything that can block the reply window is rejected. Listeners stay on the offload thread.

## Escalation rules (repeat of `team/README.md`, so you never have to look)
3 fix rounds per PR, then `needs-owner`. 3 tasks per day. 2 hours per task per session.
Bench-dependent PRs wait for the owner's results comment; nobody merges around it.

## Open items for you
- When Q-001 is done: run the label setup commands if the owner has not, then pick up the
  first `ready` issue.
- File Q-003's unit-test task yourself if Lina has not within a day of Q-001 closing.
