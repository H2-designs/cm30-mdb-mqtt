# Questions for the owner

Agents append here instead of waiting. Each entry says what was picked meanwhile. The owner
answers inline; Igris moves answered items into `DECISIONS.md` and deletes them here.

## Open

### Q-001 · setup session · library work on `main` is committed locally, not pushed
The mdb-lib → hardware-lib rename, the new `pulse-sample` and `rs232-sample` modules,
`MdbMqttBridge.kt` and the newer `dist/` artifacts are now one local commit on `main`
(`142d11b`, 2026-10-21). `origin/main` does not have it yet, and the team setup sits on
branch `team/agent-setup` on top of it. **Needs you:** push `main`, then merge or PR
`team/agent-setup`. Until then agents must not start tasks.

### Q-002 · setup session · the `simulator` flavor is gone
`SESSION_HANDOFF.md` describes a `simulator` product flavor of the demo app that ran the
state machine on an emulator. `app/build.gradle` no longer has flavors and `app/src/` only has
`main`. Morty's no-hardware testing therefore rests on `HardwareLib.simulateExchange` and an
emulator install where `start()` fails gracefully. **Picked meanwhile:** Morty tests with
`simulateExchange`. Do you want the simulator flavor (or a fake `MdbSlaveWrapper`) brought
back as an early task? It would make most tasks testable without the bench.

### Q-003 · setup session · unit tests
There are no `src/test` directories in any module. The checksum, `MdbCmd` classification and
the pulse timing math are pure Kotlin and could be unit-tested on the JVM. **Picked
meanwhile:** Igris files "add JVM unit tests for checksum + MdbCmd classification" as the
first engineering task after Q-001 is resolved, unless you say otherwise.

### Q-004 · setup session · MQTT broker for tests
`local.properties` points mqtt at the UAT broker. Agents must never publish test traffic to
it under a real device id. **Picked meanwhile:** agents only use a device id prefixed
`agent-test-` and never touch `local.properties`. Confirm, or name a separate broker.

### Q-005 · setup session · daily limit and names
Daily limit set to 3 tasks, names Rudeus / Igris / Rick / Morty. Change anything you like in
`team/README.md` and `.claude/agents/`.

### Q-006 · Rudeus · hardware-lib never verifies the checksum of incoming frames
`processCommand` and `classifyExchange` read the command bytes and never check the last (CHK)
byte of what the VMC sent; only outgoing packets get a CHK (`withChecksum`). The vendor
transport may or may not filter bad frames before handing them over - nothing in our code or
docs says. **Picked meanwhile:** issue #2 tests only the outgoing checksum and states that no
"wrong checksum rejected" path exists; it does not add one. Do you want incoming CHK
verification (drop + log a frame whose CHK is wrong) as its own later task, or is the vendor
layer known to handle it?

### Q-007 · Rudeus · does a test-only / refactor-only task bump the version?
Issue #2 adds `src/test` and extracts two pure `internal` objects but changes no public API,
no behaviour and no wire bytes, and builds no AAR or APK for `dist/`. D-007 says versions are
bumped before every build that *produces a deliverable*. **Picked meanwhile:** no bump of
`HardwareLib.VERSION` and no new file in `dist/` for #2. If you want every merged PR on
hardware-lib to bump the patch version regardless, say so and I will add it to #2 and to
`TASK_FORMAT.md`.

## Answered
(none yet)
