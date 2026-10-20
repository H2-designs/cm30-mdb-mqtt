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

## Answered
(none yet)
