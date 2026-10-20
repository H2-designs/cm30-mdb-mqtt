# The team

Four AI agents run this repo's engineering. Each one is a Claude Code subagent defined in
`.claude/agents/`, with a handoff document (job + everything decided so far) and a notebook
(its own diary) in `team/<name>/`. They never talk to each other directly: everything goes
through GitHub issues and PRs, the handoff docs, `team/DECISIONS.md` and
`team/QUESTIONS_FOR_OWNER.md`.

| Name | Job | Talks to the owner? | May edit code? | May merge? |
|---|---|---|---|---|
| **Lina** | Product manager | Yes, the main contact | No | No |
| **Rakan** | Tech lead | Only when stuck | Docs and small fixes only | No |
| **Nasser** | Hardware library engineer | No | Yes | No |
| **Dana** | Tester | Bench requests only | No (only `team/dana/`) | **Yes, the only one** |

## How a task travels

```
Owner + Lina  →  issue written in TASK_FORMAT, owner adds label `ready`
                      ↓
Rakan picks it up, checks it is buildable, assigns Nasser (label `in-progress`)
                      ↓
Nasser: branch task/<n>-slug in his own worktree → builds → opens PR (label `in-review`)
                      ↓
Dana: builds it herself, runs what can run without hardware, reads the diff against the
      hard constraints, checks docs + versions
        ↓                                ↓                              ↓
   ✅ passes → squash-merge        ❌ fails → PR comment with         🔧 needs the bench →
   closes the issue                   exact repro, back to Nasser        label `needs-bench`,
                                      (3 rounds max, then owner)        one line to the owner
                      ↓
Leftovers Dana noticed but accepted → Rakan files them as new issues (no label yet)
```

## Limits

- 3 fix rounds per PR. After the third failed round Rakan labels the issue `needs-owner`.
- 3 tasks per day picked up across the whole team. Counting resets at midnight Riyadh time.
- 2 hours of agent work per task per session; at the limit the agent writes a handover
  comment on the issue and stops.
- Nothing is merged without Dana's approval comment on the PR.

## Labels used

`ready` · `in-progress` · `in-review` · `needs-bench` · `needs-owner` · `blocked`

One-time setup (run once by the owner, needs `gh auth`):

```bash
gh label create ready --color 0E8A16 --description "Owner-approved, team may pick it up"
gh label create in-progress --color FBCA04 --description "An engineer is on it"
gh label create in-review --color 1D76DB --description "PR open, waiting for Dana"
gh label create needs-bench --color D93F0B --description "Needs the owner at the real hardware"
gh label create needs-owner --color B60205 --description "Stuck, owner decision required"
gh label create blocked --color 5319E7 --description "Waiting on something external"
```

## Hardware reality

No agent can plug a VMC into a CM30. Dana approves on her own only what she can prove without
hardware (builds, the `simulateExchange` self-test, emulator smoke run, diff review against
the constraints). Anything touching real bus timing, the vendor AAR, pulse output or RS232
needs the owner at the bench: Dana writes the exact steps into the PR using
`team/BENCH_CHECKLIST.md`, labels it `needs-bench`, and merges only after the owner reports
the result in a PR comment.

## Running it by hand (phase 1, now)

In a Claude Code session in this repo:

- `Lina, I want to add X` → she asks what she needs and writes the issue.
- `Rakan, build task #12` → he runs the whole flow for that issue, handing to Nasser and Dana.
- `Dana, test PR #15` → a standalone test run.

Watch where it goes wrong, fix the rules in the handoff docs, repeat. Automate (the half-hourly
routine, the morning summary, Remote Control) only after three tasks in a row went through
cleanly.

## Each session, every agent

1. Read `CLAUDE.md`, then your `HANDOFF.md`, then the last 3 entries of your `NOTEBOOK.md`.
2. Do the work.
3. Append a notebook entry before finishing: date, what you did, what you decided, what is
   open, what you learned the hard way.
