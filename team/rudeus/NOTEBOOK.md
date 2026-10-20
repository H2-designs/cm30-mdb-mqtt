# Rudeus · notebook

Newest entry at the bottom. Format: date · did · decided · open · learned the hard way.

## 2026-10-21 · setup
- did: nothing yet; this notebook was created by the setup session.
- decided: —
- open: wait for Q-001; then write the first three issues listed in HANDOFF.
- learned: —

## 2026-10-21 · first task written
- did: checked Q-001 is cleared (`main` == `origin/main`, PR #1 merged, no open issues, so no
  duplicate). Read the real code, then filed issue #2 "hardware-lib: JVM unit tests for MDB
  checksum and MdbCmd frame classification" (https://github.com/H2-designs/cm30-mdb-mqtt/issues/2),
  no label. Added Q-006 and Q-007.
- decided: the task allows (and expects) extracting the checksum math and `classifyExchange`
  into pure `internal` objects, because neither is reachable from a JVM test as written:
  `MdbSlaveWrapper`'s object initializer calls `MdbSlave.getInstance()` (vendor class, native
  lib, armeabi-v7a only) and `classifyExchange` is private in `HardwareLib`, reads `mdbLevel`
  and calls `priceToMinorUnits` -> `MdbConfigStore` (SharedPreferences). Forbade
  `returnDefaultValues = true` / Robolectric so leakage of Android classes fails loudly. No
  version bump and no dist artifact, since no deliverable is produced (Q-007 asks the owner to
  confirm). No "wrong checksum rejected" test: hardware-lib never verifies incoming CHK (Q-006).
  Frames used as fixtures come from the CMD table in `LIBRARY_README.md` and the spec example
  already in `MdbSlaveWrapper` (`11 01 05 DC 00 64` -> `0x57`). Default READER CONFIG DATA
  scale byte is `0x01`, so `13 00 01 F4 00 03` is literally price 500, item 3.
- open: owner to add `ready` to #2 and answer Q-006/Q-007. Next issues in HANDOFF order:
  (b) no-hardware simulator path, (c) MALFUNCTION refund-failed handling. Issue (b) should
  reuse whatever `internal` classifier object #2 produces.
- learned: the Bash tool's heredoc choked on a long multi-line body; write issue bodies with the
  Write tool into the scratchpad and pass `--body-file`. `gh` needs `--repo` here because the
  working directory is reported as not-a-git-repo even though `.git` exists.
