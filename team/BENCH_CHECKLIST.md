# Bench checklist

What only the owner can verify, with the real CM30 on a real VMC. Dana copies the relevant
block into the PR, fills the blanks, labels `needs-bench`, and merges only after the owner
pastes results into a PR comment.

## Before any bench run
1. Install the PR's APK from `dist/` (the filename names the version). Boot banner on screen
   must show the expected `hardware-lib X.Y.Z` and `mqtt-lib A.B.C`, else the build is stale.
2. Note the VMC make/model and its reported feature level (code 113 line on screen).

## A · MDB session cycle (any change to HardwareLib state machine or MdbCmd)
- [ ] Power-cycle the VMC. Screen shows RESET → SETUP → EXPANSION → READER ENABLE within 10 s.
- [ ] BEGIN SESSION appears (auto-arm, D-001).
- [ ] Select a product on the VMC. VEND REQUEST line shows the correct price and item.
- [ ] Press vend approved. VMC dispenses. VEND SUCCESS then SESSION COMPLETE then END SESSION.
- [ ] Next session auto-arms. Repeat once.
- [ ] No "unhandled" lines for our address range 0x10–0x17 during the whole cycle.
- [ ] Deny path: let a VEND REQUEST time out or cancel; VMC shows its own "denied" behaviour.

## B · Dashboard / MQTT (any change to mqtt-lib or the bridge)
- [ ] Device appears online on the dashboard (retained status).
- [ ] One full session from block A shows up as log lines in the correct order.
- [ ] A command sent from the dashboard (`simulateExchange:10 10`) produces the code-111 line
      on the device screen.
- [ ] Pull the network for 60 s, restore. Device reconnects, queued lines arrive, no duplicates.

## C · Pulse output (any change to PulseLib)
- [ ] `initPulse(true)` then `sendPulse(50, 100, 3)`: scope or machine counter sees exactly 3
      pulses, 50 ms wide, 100 ms period.
- [ ] Same with `initPulse(false)` (inverted idle level).
- [ ] With ready-feedback armed and the machine not ready, `sendPulse` returns false and
      nothing is emitted.

## D · RS232 (any change to Rs232Lib)
- [ ] Loopback: TX connected to RX, a known frame echoes back intact.
- [ ] Target device responds to one real command from the task's "Done when".

## Reporting back
Paste into the PR: APK filename, VMC model, each checkbox as pass/fail, and the screen log
(or a photo) for any fail. Dana does the rest.
