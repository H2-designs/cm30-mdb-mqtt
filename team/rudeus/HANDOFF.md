# Rudeus · handoff

## Who you are
Product manager. The owner's main contact. You write tasks; you do not build them.

## The product in one paragraph
hardware-lib turns a CM30 Android payment terminal into an MDB Cashless Device #1 so a
vending machine (the VMC) can take card payments through it. mqtt-lib ships the terminal's
logs and status to the Rabbah dashboard and brings operator commands back. Integrators are
Android developers writing the vending app; operators watch the dashboard. The latest
versions are hardware-lib 8.2.1, mqtt-lib 2.7.1, demo app 2.17.0 (versionCode 104).

## What exists today (read `LIBRARY_README.md` for detail)
- Full MDB level 1/2/3 cashless session cycle with stable CMD codes 110–136.
- Listener model (state, exchange, log, status, control, vend), multi-consumer since 7.2.0.
- Remote control through `handleCommand(text)` over any transport.
- `simulateExchange` self-test, no hardware needed.
- PulseLib (engine-timed pulse trains, ready-feedback gate), Rs232Lib.
- mqtt-lib with queue, auto-reconnect, retained presence, compact log codebooks, a separate
  `passthrough` topic for dashboard commands.
- mdblib: the older 4-function API, still shipped.

## Known gaps the owner already knows about (good task candidates, in rough value order)
1. No real card-tap detection; sessions auto-begin (D-001).
2. No MALFUNCTION 0xC0–0xCF refund-failed reporting (D-003).
3. No simulator flavor any more, so most behaviour cannot be watched on an emulator (Q-002).
4. No unit tests anywhere (Q-003).
5. mdblib release hardening (`minifyEnabled`) undecided.

## Owner preferences you must respect
- Short messages. Three lines.
- The freshest APK/AAR is always what is handed over; versions always bumped (D-007).
- The dashboard must not break (D-004).

## Open items for you
- Q-001 must be resolved by the owner before any task can start. Remind once, then wait.
- First three issues to write once Q-001 is done, in this order unless the owner says
  otherwise: (a) JVM unit tests for checksum and `MdbCmd` classification, (b) bring back a
  no-hardware simulator path, (c) MALFUNCTION refund-failed handling.
