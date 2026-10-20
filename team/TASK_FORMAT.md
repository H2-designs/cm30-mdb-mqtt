# Task format

Every task is one GitHub issue in this shape. Lina writes them; Rakan rejects any that are
missing a section; Dana tests against the "Done when" list literally.

```
## What to build
One or two sentences. Which module (hardware-lib / mqtt-lib / app / mdblib / sample).

## Why
One sentence: who needs this and what breaks or is missing without it.

## Every situation it must handle
- Normal path
- Each failure or edge case, one bullet each (port not open, VMC level 1 vs 2/3, listener
  throws, emulator vs real CM30, command arrives twice, ...)

## Out of scope
What this task deliberately does NOT do.

## Done when
- [ ] Observable, checkable statements. "simulateExchange("13 00 01 F4 00 03") fires code 121
      with price 500", not "vend requests work".
- [ ] LIBRARY_README.md updated (or "no doc change needed, because ...")
- [ ] Version bumped: <which constant(s)>
- [ ] New artifact in dist/ named <name>-<version>.aar (if a deliverable)

## Bench needed?
yes / no. If yes: what exactly must be observed on the real machine.

## Owner decisions already made
Links or quotes from team/DECISIONS.md that constrain this task.
```

Size rule: if "Every situation it must handle" has more than ~8 bullets, split the task.
