# cm30-mdb-mqtt — repo rules for every agent

This repo is worked on by a small AI team (see `team/README.md`). Whoever you are, read this
file first, then your own `team/<you>/HANDOFF.md` and `team/<you>/NOTEBOOK.md`.

## What this repo is

Android libraries for the CM30 payment terminal acting as an MDB Cashless Device #1 inside a
vending machine, plus the demo/sample apps that exercise them.

| Module | Role |
|---|---|
| `hardware-lib/` | The product. `HardwareLib` (MDB slave state machine, config store, settings, CMD-code schema `MdbCmd`), `PulseLib` (pulse output over CM30 digital IO), `Rs232Lib`. No networking. Wraps the vendor AAR in `hardware-lib/libs/`. |
| `mqtt-lib/` | MQTT 3.1.1 transport + Rabbah compact-log layer (`RabbahLog`, `RabbahMqtt`). No MDB knowledge. |
| `app/` | Demo/test app "MDB-Slave-2". Hosts `MdbMqttBridge.kt`, the only meeting point of the two libraries. |
| `mdblib/` | Older 4-function wrapper (`MdbSlave.initMDB` …). Kept for existing integrators. |
| `sampleapp/`, `rabbahlog-sample/`, `pulse-sample/`, `rs232-sample/` | Minimal samples built on the libraries. |
| `dist/` | Versioned AAR/APK deliverables. Never overwrite an existing file there. |
| `LIBRARY_README.md` | The developer-facing integration guide. Must stay true after every change. |
| `SESSION_HANDOFF.md` | Historical notes from the first build. Partly outdated (the `simulator` flavor no longer exists). |

## Build and check

```bash
./gradlew :hardware-lib:assembleRelease      # the library AAR
./gradlew :mqtt-lib:assembleRelease
./gradlew :app:assembleDebug                 # demo app APK
./gradlew assemble                           # everything
```

JDK 17, Gradle 8.13 wrapper, AGP 8.5.2, Kotlin 1.9.24, compileSdk 36, minSdk 30.
`local.properties` holds the SDK path and MQTT broker credentials. It is gitignored. Never
commit it, never paste its contents into an issue, PR, notebook or chat.

## Hard constraints (breaking any of these fails review)

1. **`MdbCmd` codes are append-only.** Never renumber or reuse a CMD code. New exchanges get
   the next free number.
2. **MQTT payload format stays byte-identical** for the existing dashboard unless the task
   explicitly says the dashboard changes too.
3. **Bump versions before building a deliverable:** `HardwareLib.VERSION` (hardware-lib),
   `MqttLib.VERSION` (mqtt-lib), `versionCode` + `versionName` in `app/build.gradle` (app).
   New artifacts go into `dist/` with the version in the filename.
4. **The vendor AAR is `armeabi-v7a` only.** Hardware paths run only on the real CM30. On an
   emulator `HardwareLib.start()` fails gracefully; `HardwareLib.simulateExchange(...)` still
   drives the full classify → template → listener pipeline and is the no-hardware self-test.
5. **Listeners run on the engine's offload thread, never the bus thread.** Nothing may block
   the MDB reply window. Attach listeners before `HardwareLib.init(context)`.
6. **Every public call returns `Boolean`** (or `null`/error sentence for the hex setters).
   Keep that contract for new API.
7. **No networking in hardware-lib, no MDB in mqtt-lib.** The bridge in `app/` is the only
   place they meet.
8. **Whoever builds something never approves it.** Only the tester merges.

## Three rules for everyone

1. **Don't wait for the owner.** If something is unclear, write the question in
   `team/QUESTIONS_FOR_OWNER.md`, pick the safest option, say which one you picked in the
   issue or PR, and keep going.
2. **Keep your docs updated.** Before you finish a session: append to your
   `team/<you>/NOTEBOOK.md` (what you did, what you decided, what is open, what you learned the
   hard way). Update `LIBRARY_README.md` when behaviour or API changed. Record cross-team
   decisions in `team/DECISIONS.md`.
3. **Keep it short when talking to the owner.** One message, three lines: done / needs you /
   waiting. No progress narration.

## Working conventions

- One GitHub issue per task, labelled `ready` when the owner approves it. Task format:
  `team/TASK_FORMAT.md`.
- One branch per task: `task/<issue-number>-<short-slug>`, from `main`. Each agent session
  works in its own git worktree so parallel sessions never share a working tree.
- One PR per task, using `.github/pull_request_template.md`. Reference the issue (`Closes #N`).
- Commit messages: imperative, one line, body explains why. End with
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Limits: 3 fix rounds per PR, then it goes to the owner. 3 tasks per day across the team.
  A single session stops itself after 2 hours of work on one task and writes a handover note.
