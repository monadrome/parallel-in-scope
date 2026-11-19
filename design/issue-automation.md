# Issue Automation: Triage and the Unattended Agent

An optional mechanism: triage issues, hand suitable tasks to a local agent for completion, and
merge into the current dev line (`dev/vX.Y.Z`). When and whether to release to `main` is always
the maintainer's decision; this mechanism never touches `main`. Without running the runner or
using its labels, the repository process is exactly as before. The implementation is in
`scripts/issue_agent/`, with `scripts/issue-agent.py` as the entry (`--help` lists commands and
local configuration).

## 1. Motivation

The bottleneck is not generating code but verifying and merging: unattended output is valuable
only when it can be trusted without line-by-line human reading. So this mechanism automates
exactly two things: triage, and enforcing the verification regimen [AGENTS.md](../AGENTS.md)
already has (tests, formatting, PIT, independent re-review) without a human in the loop. It
lowers no existing gate.

- MUST NOT merge changes into any branch other than the dev line; the target branch is the
  remote's latest `dev/vX.Y.Z`.
- MUST NOT close an issue on triage judgment alone; an issue is closed only when its fix has
  landed, with a `Landed in <sha>` comment, matching the maintainer's manual-closing
  convention.
- An issue with neither maintainer authorization nor a triage recommendation is left untouched
  by the runner.

## 2. Trust boundary

In a public repository anyone can write an issue, and the execution agent has a local shell —
issue text is an injection carrier.

- Trusted identities: author association OWNER/MEMBER/COLLABORATOR, and not a bot.
- The execution agent MUST only see text written by trusted identities: external authors'
  titles and bodies are withheld, and the task is defined by a maintainer comment; an external
  issue with no maintainer comment MUST NOT be executed. Bot comments (including CI summaries)
  and the runner's own marker comments do not count.
- The triage agent may read external text, but it has read-only tools (no shell, no network),
  and its output is validated against an enum whitelist; an external author's issue MUST be
  assigned to a human, and comments MUST NOT echo the model's free text.
- `agent/ready` counts as authorization only when the labeler has write or higher permission on
  the repository.
- Triage also invokes the model for external issues, so anyone opening an issue can make the
  runner spend money: total triage spend per tick is capped, maintainer issues are processed
  first, and the rest defer to the next tick.
- Residual risk: auto-merged changes entering the dev line trigger snapshot publication. When
  the snapshot workflow runs tests, the signing private key is already in the keyring, so
  agent-written test code runs in an environment holding that key. The passphrase appears in
  only two steps: the setup-java step importing the key, and the deploy step; Central
  credentials appear only in the deploy step. The step running tests has neither; the deploy
  step skips tests, and plugins are decided by `pom.xml`, which requires human merging. This is
  the same risk as a maintainer committing test code directly, constrained by the trust
  boundary and the gates.
- MUST NOT run a code-executing agent on GitHub `issues`/`issue_comment` events: the snapshot
  and release workflows restore the maven cache and hold publishing credentials, and an
  issue-triggered job would write into the default branch's cache scope — the combination is a
  Clinejection-style poisoning chain. The runner runs only on the local machine.
- In the execution agent's environment, GitHub credentials are neutralized (an invalid token,
  the credential helper emptied, the clone's push URL invalidated), and push, gh, and network
  commands are denied; pushing, opening PRs, and merging are done by the runner itself.

## 3. The label state machine

| Label | Applied by | Meaning |
|---|---|---|
| `priority/p0`–`p2`, `bug`/`documentation`/`enhancement`/`question` | triage suggests; humans may change | priority and type |
| `agent/eligible` | triage | judged suitable for unattended handling |
| `needs-decision` | triage or humans | awaiting the maintainer's decision; a veto for the runner |
| `agent/ready` | maintainer | authorizes the runner until the issue closes |
| `agent/working` | runner | claimed |
| `agent/review` | runner | all gates passed; awaiting human merge per the rules |
| `agent/blocked` | runner | stopped this time; a human removing it allows retry |

- State lives only in labels and the runner's two marker comments (triage, run); the runner
  itself stores no state.
- `needs-decision`, `agent/blocked`, `agent/working`, and `agent/review` are all vetoes: with
  any of them present, the runner does not select the issue.

## 4. The triage contract

- Reruns only when the hash of the issue's content (title, body, non-runner comments) changes;
  marker comments are updated in place, not spammed.
- For each label family (type, priority, routing), triage manages only the one it applied: if a
  human changes a label in the family, triage leaves that family alone; if a human deletes a
  triage-applied label, that label is recorded as suppressed and never re-added.
- The reasons for assigning to a human are a fixed enum: direction unsettled, public API shape
  unsettled, sensitive, insufficient information, already resolved, duplicate, external author.
  "Sensitive" covers the categories AGENTS.md requires asking first: dependencies and plugins,
  file deletion, releases and credentials, and low-level primitives needing human confirmation.
- Triage does not close issues and does not apply `duplicate`; suspected duplicates are only
  hinted in a comment.

## 5. Selection and claiming

- Candidates = no veto labels, plus one of: an authorized `agent/ready`; or `agent/eligible`, a
  trusted author, priority within the automatic set (default p0, p1), and a triage conclusion
  still valid for the current content.
- Ordering: priority, then explicit authorization, then smaller issue number.
- Backpressure: when agent PRs awaiting human merge reach the cap, no new tasks are started. An
  issue explicitly named on the command line counts as authorization and is exempt from this
  limit, but still subject to veto labels.
- Only one runner per machine at a time (a file lock). Across machines, the claim label
  `issue-agent-claim/issue-<n>` provides mutual exclusion: it is created atomically on GitHub,
  and a creation failure means giving up; it points at a claim commit recording the machine ID,
  the run, and the creation time. The machine ID is a random value stored in the state
  directory — not the hostname, because hostnames can collide; the creation time is written in
  the commit message, not relying on GitHub's date format.
- Deleting a claim MUST first confirm the label still points at this run's claim commit
  (compare-then-delete), never deleting someone else's claim. A claim whose ownership or time
  is unreadable is left alone, with the maintainer notified once; a claim created more than one
  hour in the future is treated as unreadable likewise.
- Each run's wall-clock time is shorter than the claim's time limit minus a margin; it stops at
  the limit. Before merging, it MUST confirm this run still holds the claim, otherwise abandon
  the merge.
- Recovery follows the claim label. A local claim is necessarily stale (while holding the lock,
  no run is running locally); it is settled per §8's recovery order and then the claim is
  deleted. Another machine's claim counts as expired only after its time limit: when the run
  marker belongs to that run, recover per §8 likewise, otherwise transition to
  `agent/blocked`; then delete. An issue with no claim but a local `agent/working` label is
  recovered per its run marker likewise.

## 6. Execution and gates

Each run uses an independent clone and a unique branch `auto/issue-<n>-<slug>-<run>`, never
touching the maintainer's working tree (a shared working tree gets rewritten by concurrent
sessions and jdtls).

1. Implementation: the agent completes per AGENTS.md and commits locally, returning a
   structured report (title, summary, contract items, verification, PIT classification,
   whether the issue is completed, whether compatibility is broken).
2. Local gates are executed by the runner, not trusting the report: a clean working tree; new
   commits exist; the path policy; `spotless:check` and `clean verify` on a clean export of
   HEAD, confirming javac really compiled the main sources; the repository check scripts;
   reverse verification for bug issues (revert only main sources, and the new tests must go
   red; main sources changed without test classes changed → inconclusive, to a human); changed
   main sources require a PIT report newer than the last edit, counted item by item by PIT's
   raw statuses (`TIMED_OUT`, `MEMORY_ERROR` count as detected per PIT convention but are not
   merged into `KILLED`); when a mutant has no test conclusion (such as `RUN_ERROR`) or the
   report evaluated no mutants at all, the conclusion is inconclusive → to a human; the title
   is a lowercase Conventional Commit. Failures go back to the same session for fixing, with a
   round cap.
3. Push and open a PR targeting the dev line; old agent PRs for the same issue are closed.
4. Independent re-review: another model and tool (codex, read-only sandbox) reviews the diff
   against the contracts. Blocker/major findings go back to the agent for reproduction and
   disposition (retain, downgrade, reject — rejection needs evidence) and fixing; re-review
   rounds are capped, and each round's conclusion is posted on the PR. When the re-review
   output cannot be parsed into a conclusion, retry once with a "JSON only" requirement; still
   unparseable → to a human.
5. CI: read check runs by commit SHA; the run is finished only when the check set is stable and
   all complete; failure logs go back to the agent, and re-review follows the fix. A workflow
  chained by `workflow_run` that appears later than other checks is outside the wait scope;
   this repository has none.
6. Rebase onto the dev line's latest before merging; on any change, rerun the gates (except
   PIT, since the patch is unchanged) and CI. Conflicts, post-rerun gate failures, or
   inconclusive conclusions all go to a human.

Path policy:

- Forbidden (stop immediately, do not push): suspected secret files; suspected credentials in
  added lines; modifying an existing ADR.
- Local-only (gates still run, but MUST NOT be pushed): `.github/workflows/`,
  `.github/actions/`. GitHub would run their workflows on push with repository secrets, before
  a human ever looks. Such changes are saved as patches in the run directory, transitioned to
  `needs-decision`, and pushed by the maintainer personally. The runner's token should also
  lack the `workflow` scope, so GitHub rejects such pushes as a backstop.
- Human-merge-required: deletions or renames, `.github/`, `.mvn/`, `pom.xml`, new ADRs,
  `scripts/` (the runner and gate scripts), any `AGENTS.md`/`CLAUDE.md`. An agent must not
  loosen its own guardrails, rewrite its gates, or rewrite its own instructions unattended.

These guardrails defend against mistakes and naive injection, not a sandbox: the execution
agent still has local filesystem read access. The trust boundary (§2) guarantees the
instructions fed to it come only from the maintainer, with credential scanning and GitHub's
push protection as backstops.

When triaging a maintainer-opened issue, only maintainer comments are given to the model:
external comments must not speak through a maintainer issue's public comments.

## 7. Merging

- When no human is needed, squash-merge into the dev line with `--match-head-commit` pinning
  the verified head; the merge commit carries contract items and `Closes`/`Refs`.
- Before merging, MUST re-read the PR's target branch: a PR may have been retargeted, and
  anything other than the current dev line refuses the merge. A window remains between reading
  and merging (GitHub's merge API cannot be conditioned on the target branch), so read once
  more after merging; if the target is not the dev line at that point, judge failure and
  notify the maintainer.
- When the maintainer merged the same PR first, treat it as merged, not as a failure.
- When a human is needed, stop at `agent/review` with the PR kept mergeable; after the
  maintainer merges, the next tick closes the issue.
- When the report says "issue not completed", merge without closing, transitioning to
  `needs-decision`.

## 8. Failure and recovery

- Each agent call's spend cap is the smaller of "the per-call cap" and "the run's remaining
  budget"; no further calls once the remaining budget is exhausted. The re-review side (codex)
  has no spend parameter, is bounded only by timeout, and does not count into run spend.
- Wrap-up:
  1. Write the terminal state into the local run directory first.
  2. Confirm the claim. When taken over, only notify the maintainer; MUST NOT rewrite the
     successor run's markers and labels. Reading a claim: only 404 means no claim; other
     failures count as "unknown" and wrap-up proceeds as usual (claim deletion is
     compare-then-delete and cannot hurt a successor).
  3. Write the run marker, change labels, convert blocked PRs to drafts.
  4. Delete the claim last.

  A GitHub failure at any step only leaves the claim for the next tick — it never crashes the
  tick.
- Wrap-up, recovery, timeout reclamation, and post-maintainer-merge cleanup share one settling
  step: write the marker, change labels, convert blocked or needs-decision PRs to drafts. When
  the PR state is unreadable, no draft conversion; when the PR is already merged or closed,
  draft conversion is rejected by GitHub and the runner does not treat it as a failure.
- When recovering a locally stale run, take the terminal state in this order:
  1. The local terminal-state record. The record must belong to the same issue and this
     machine, or it counts as absent.
  2. The terminal state on the run marker.
  3. Read the PR recorded on the run marker:
     - merged: transition to `needs-decision`, not `agent/blocked`, to avoid redoing landed
       work;
     - still open: convert to draft, transition to `agent/blocked`;
     - unreadable: keep the claim for the next retry; only after 3 consecutive unreadables
       transition to `agent/blocked`.
- Cleanup is isolated issue by issue: one issue's error only defers it to the next tick, never
  interrupting the others.
- When the target branch is unreadable after merging, transition to `needs-decision` rather
  than closing the issue as "merged into the dev line".
- On stop: apply `agent/blocked` (or `needs-decision`), convert the PR to draft, state the
  reason in the run comment, keep the local log; when a notification command is configured,
  receive a one-line summary.
- Retry = the maintainer removes `agent/blocked`; `agent/ready` is kept as authorization and
  need not be re-applied.
- Scheduled tasks (launchd) carry only a minimal environment like `PATH` and `HOME`: model
  credentials are read from `~/.claude/settings.json`'s `env`, gh uses the keychain login, and
  codex reads its own configuration.

## 9. Rejected alternatives and reasons

- Cloud execution (gh-aw, claude-code-action, etc.): this repository's release workflows share
  a cache scope with issue events, and the independent re-review depends on the local codex
  configuration. Cloud suits read-only triage at most, and the current issue volume does not
  need it.
- Unconditional auto-merge: builds, CI, dependencies, and the runner's own changes must be seen
  by a human. Everything else only enters the dev line, with the maintainer as the gate before
  release to `main`.
- Auto-closing (dedup, expiry): the lesson from the Claude Code repository is that bot closing
  scatters real problems. This mechanism closes no issue without a landed fix.
- One long-lived session looping over the queue: a session can be silently interrupted by
  external causes such as Stop hooks. Replaced by an external loop, one bounded headless
  session per task, judging progress by side effects like pushes and PRs.

## 10. Independent review record

Round 1 (codex `deepseek-v4-pro`, read-only sandbox, reviewing a snapshot of the then-dev
line): 5 major, 5 minor.

| Finding | Disposition | The fix's actual effect |
|---|---|---|
| Run spend caps checked only before repair rounds; a single call could exceed the whole run budget | Retained | Each call's cap is the smaller of the per-call cap and the remaining budget; the re-review side has no spend parameter — written into §8 |
| Cross-machine claiming not atomic; two machines could work one issue simultaneously | Retained | Switched to atomically creating the claim label on GitHub; creation failure means giving up |
| Merging validated only the configured target branch; a retargeted PR could merge into `main` | Retained | Re-read the PR's target branch before merging |
| Wrap-up changed labels before writing the marker; a mid failure could stick at `agent/review` | Retained | Terminal-state marker written first; stale states recovered by marker |
| Wrap-up, rejection, credential neutralization, and merge guardrails had no tests | Retained | Those tests added; every guardrail reverse-verified |
| CI waiting judged completion by "stable across two polls", missing late-appearing checks | Downgraded to known limitation | Only checks chained by `workflow_run` appear late, and this repository has none; judging by check suite could hang on a suite that never runs. Written into §6 |
| Gate scripts not in the human-merge path | Retained | `scripts/` as a whole requires human merge; `mkdocs.yml` excluded (adding a page must change it) |
| A bug fix without test changes skipped reverse verification outright | Retained | Changed to judge "inconclusive" and go to a human |
| The runner misjudged failure when the maintainer merged first | Retained | Treated as merged |
| Marker parsing lacked tests for nested payloads | Retained | Tests added; `--` in payloads is escaped and cannot close early |

The review also found one problem: that custom provider did not enforce `--output-schema`, so
re-review output was wrapped in a markdown code block and the original parser treated it as "no
conclusion". Switched to lenient parsing, with unknown severities treated as major.

Round 2 (same model, fresh session; baseline the round-1 dispositions, reviewing their fixes):
4 major, 3 minor.

| Finding | Disposition | The fix's actual effect |
|---|---|---|
| A crash between claiming and the run marker left the claim label forever | Retained | The claim commit records machine ID and run; recovery follows the claim, and a local claim is always reclaimed |
| After wrap-up's claim deletion failed, labels stayed and the issue could not retry | Retained | Claim deletion moved to the very end of wrap-up, executed only on success; a failed claim is reclaimed by the next tick |
| Ownership judged by hostname; same-named machines could reclaim others' claims | Retained | Switched to a random machine ID in the state directory; other machines' claims require timeout for reclamation |
| The budget-clamp call sites had no tests; round 1's reverse verification missed them | Retained | Call-site tests added, with defects injected at both call sites and at "no more calls under $1 remaining" |
| A race remained between reading the target branch and merging | Retained, downgraded to known limitation | GitHub's API cannot be atomic; re-check after merging, judging failure and notifying on mismatch |
| A conclusion missing the `findings` field was treated as clean | Retained | A missing `findings` list means no conclusion — to a human |
| `design/issue-automation.md` could be rewritten unattended | Retained | That file joined the human-merge path; other `design/` documents did not (contract and implementation must be one commit) |

Reverse verification: defects injected one by one at 38 guardrails, each caught by its
corresponding test. The budget call site round 1 missed is among them.

Round 3 (same model, fresh session; reviewing round-2 fixes, walking each crash point): 2
major, 2 minor.

| Finding | Disposition | The fix's actual effect |
|---|---|---|
| Claims expired by age only: after a slow run was reclaimed by another machine, its wrap-up would delete the successor run's claim | Retained | Switched to compare-then-delete; confirm the claim is still held before merging; run wall-clock shorter than the claim limit |
| A claim with unreadable creation time never expired, sticking the issue forever | Retained | Creation time written into the commit message; dates parsed in any timezone-bearing format; unreadable claims notify the maintainer once |
| The post-merge target-branch re-check failing still closed the issue as merged | Retained | An unreadable re-check transitions to `needs-decision` |
| A crash between merging and wrap-up was misjudged blocked at recovery, and a retry would redo | Retained | PR number recorded after opening; recovery checks the PR, transitioning to `needs-decision` when merged |

Reverse verification: defects injected one by one at 46 guardrails, each caught by its
corresponding test.

Round 4 (same model, fresh session; reviewing round-3 fixes): 2 major, 2 minor.

| Finding | Disposition | The fix's actual effect |
|---|---|---|
| Wrap-up of a run whose claim had expired still rewrote the successor run's markers and labels | Retained | Confirm the claim is still held before wrap-up, otherwise notify only; reading a claim distinguishes 404 from other failures |
| A PR read failure at recovery aborted the whole tick, leaving the claim too | Retained | Unreadable means treated as blocked; cleanup isolated per issue |
| A claim with a future creation time had negative age and never expired | Retained | More than one hour ahead is treated as unreadable, notifying the maintainer |
| After recovery the PR was still mergeable, never converted to draft | Retained | Recovery to blocked converts the PR to draft |

Reverse verification: defects injected one by one at 52 guardrails, each caught by its
corresponding test.

Round 5 (same model, fresh session; reviewing round-4 fixes): 2 major.

| Finding | Disposition | The fix's actual effect |
|---|---|---|
| Occasional claim-read failures aborted wrap-up wholesale; the next tick could only infer from the PR, losing real terminal states like `merged`/`review` | Retained | Terminal state written to the local run directory first; wrap-up proceeds as usual when the claim read is "unknown"; wrap-up failures never crash a tick, and the next tick recovers completely from the local record |
| At recovery an occasionally unreadable PR was written down as terminal blocked, and the PR was converted to draft | Retained | On unreadable: keep the claim, count attempts, re-read next tick, give up only after 3 consecutive; draft conversion only when the PR is confirmed still open |

Reverse verification: defects injected one by one at 60 guardrails, each caught by its
corresponding test.

Round 6 (same model, fresh session; reviewing round-5 fixes): 1 major, 1 minor.

| Finding | Disposition | The fix's actual effect |
|---|---|---|
| Recovering a blocked terminal state from the local record did not convert to draft: round 4's problem reproduced through the new path | Retained | Wrap-up, recovery, timeout reclamation, and post-merge cleanup share one settling step; the draft-conversion rule is written in one place |
| Local records did not validate ownership, and run ids were second-precise — replayable onto another run | Retained | Records carry issue number and machine ID, mismatches at recovery count as absent; run ids gained a random suffix |

Reverse verification: defects injected one by one at 64 guardrails, each caught by its
corresponding test.

Round 7 (same model, fresh session; reviewing round-6 fixes): 1 major, 2 minor.

| Finding | Disposition | The fix's actual effect |
|---|---|---|
| Reclaiming another machine's claim on timeout settled with empty data, never converted to draft, and flushed the PR number from the run marker | Retained | When the run marker belongs to that run, follow the same recovery rules as local (including reading the PR, retries, draft conversion); otherwise settle as blocked |
| Terminal-state replay converted to draft without reading the PR state first | Downgraded, contract rewritten | For an already merged or closed PR, draft conversion is rejected by GitHub and the runner does not treat it as a failure; the contract now says so honestly instead of "does not convert" |
| Round 6's record ended with "results below" and no results followed | Retained | Results added |

Round 8 (same model, fresh session; reviewing round-7 fixes): no new findings. In this round
the re-review side replied with a plain-text conclusion and no JSON. The runner goes to a human
on such output — safe but obstructive to automation — so one "JSON only" retry was added.

Reverse verification: defects injected one by one at 66 guardrails, each caught by its
corresponding test, plus one for the re-review retry.

When this runner was committed, GitHub rejected the push: the runner's token lacked the
`workflow` scope. This exposed a gap in the path policy: changes to `.github/workflows/` run
with secrets the moment they are pushed, and "human-merge-required" could not stop that.
Changed to local-only: save a patch and transition to `needs-decision` (§6). The runner
therefore needs no token with the `workflow` scope either.

Round 9 (same model, fresh session; reviewing this change): conclusion clean. The re-review
checked the repair rounds, the re-push after the CI fix, the post-rebase state, renames, and
the symlink paths one by one, and none could push such changes through. 1 minor: one residual-risk
sentence in §2 disagreed with `snapshot.yml` (the passphrase also appears in the setup-java
step) — corrected.

Reverse verification: defects injected one by one at 70 guardrails, each caught by its
corresponding test.

Round 10 (`/multi-review`, read-only snapshot; reviewing the two fixes for PIT raw statuses and
the post-rebase inconclusive gate): kimi read the diff, the changed code, the tests, and the
contract, ran all 184 script tests green, and found nothing. codex gave no conclusion twice:
the first time a 503 from the model channel, the second a timeout after repeated reconnects —
recorded as blocked, not clean.

Reverse verification: defects injected one by one at 6 fixes, each caught by its corresponding
test.
