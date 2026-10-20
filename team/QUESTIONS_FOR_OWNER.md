# Questions for the owner

Agents append here instead of waiting. Each entry says what was picked meanwhile. The owner
answers inline; Rakan moves answered items into `DECISIONS.md` and deletes them here.

## Open

### Q-001 · setup session · uncommitted work on `main`
`main` has one commit, and the working tree holds the whole mdb-lib → hardware-lib rename,
the new `pulse-sample` and `rs232-sample` modules, `MdbMqttBridge.kt`, and newer `dist/`
artifacts, all uncommitted. The team cannot branch from a clean `main` until this is
committed and pushed. **Picked meanwhile:** nothing, this needs you. Suggested: one commit
"hardware-lib 8.2.1, mqtt-lib 2.7.1, samples" on `main`, then push.

### Q-002 · setup session · the `simulator` flavor is gone
`SESSION_HANDOFF.md` describes a `simulator` product flavor of the demo app that ran the
state machine on an emulator. `app/build.gradle` no longer has flavors and `app/src/` only has
`main`. Dana's no-hardware testing therefore rests on `HardwareLib.simulateExchange` and an
emulator install where `start()` fails gracefully. **Picked meanwhile:** Dana tests with
`simulateExchange`. Do you want the simulator flavor (or a fake `MdbSlaveWrapper`) brought
back as an early task? It would make most tasks testable without the bench.

### Q-003 · setup session · unit tests
There are no `src/test` directories in any module. The checksum, `MdbCmd` classification and
the pulse timing math are pure Kotlin and could be unit-tested on the JVM. **Picked
meanwhile:** Rakan files "add JVM unit tests for checksum + MdbCmd classification" as the
first engineering task after Q-001 is resolved, unless you say otherwise.

### Q-004 · setup session · MQTT broker for tests
`local.properties` points mqtt at the UAT broker. Agents must never publish test traffic to
it under a real device id. **Picked meanwhile:** agents only use a device id prefixed
`agent-test-` and never touch `local.properties`. Confirm, or name a separate broker.

### Q-005 · setup session · daily limit and names
Daily limit set to 3 tasks, names Lina / Rakan / Nasser / Dana. Change anything you like in
`team/README.md` and `.claude/agents/`.

## Answered
(none yet)
