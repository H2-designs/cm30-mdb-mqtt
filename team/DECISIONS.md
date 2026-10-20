# Decisions

Append-only. Newest at the bottom. Every entry: date, who decided (owner or which agent under
the "safest option" rule), the decision, why. Agents quote the entry number in issues and PRs.

## D-001 · 2026-10-21 · owner (from the first build sessions)
Session begin auto-fires after READER ENABLE and re-arms after every END SESSION, on real
hardware too, because no card-tap detection exists yet. Accepted that the terminal claims a
session began without a real tap until detection exists.

## D-002 · 2026-10-21 · owner
Vend approval is never automatic. Only `approveVend()` / the vend listener / the demo button
move the state machine to VEND APPROVED, and an early press before any VEND REQUEST is
discarded, not queued. With no listener set, vend requests are denied.

## D-003 · 2026-10-21 · owner
VEND SUCCESS and VEND FAILURE only get ACKed and do not close the session. Only SESSION
COMPLETE (0x13 0x04) does, per spec. The MALFUNCTION 0xC0–0xCF refund-failed path is not
implemented yet.

## D-004 · 2026-10-21 · owner
`MdbCmd` codes are stable integers, append-only, never renumbered. The MQTT payload the
dashboard receives stays byte-identical across library refactors.

## D-005 · 2026-10-21 · owner
hardware-lib contains no networking; mqtt-lib contains no MDB. `MdbMqttBridge` in `app/` is
the only coupling and is about 30 lines by design. Other transports get their own bridge.

## D-006 · 2026-10-21 · owner
Pulse trains are timed by the engine's own `PulseWorker` thread, not the vendor's
`digital_out_pulse`, because on real hardware the vendor call sent the wrong count and ignored
direction. `vendorPulse` stays reachable for experiments only.

## D-007 · 2026-10-21 · owner
Versions are bumped before every build that produces a deliverable, and the freshest artifact
is always what gets handed over. Files in `dist/` are never overwritten.

## D-008 · 2026-10-21 · setup session (safest option, owner to confirm)
Team of four: Lina (PM), Rakan (tech lead), Nasser (hardware library engineer), Dana (tester,
sole merger). Limits: 3 fix rounds per PR, 3 tasks per day, 2 hours per task per session.
Automation waits until three tasks pass through by hand.
