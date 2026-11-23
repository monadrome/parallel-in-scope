---
name: refactoring-review
description: Refactoring review of code changes: the required step after the behavior commit and before code review (/multi-review). The script decides by diff whether it is needed, how many read-only detectors to dispatch, and which lens each takes; after the main agent verifies, behavior-preserving refactors land as separate refactor commits. Use for refactoring-review, refactor review, pre-review refactoring, post-commit structure cleanup. Runs only for code changes; documentation-only changes are skipped by the script's judgment.
---

# /refactoring-review

This file handles orchestration only. Rule routing, principles, lenses, and thresholds are
governed by [refactoring-review/AGENTS.md](../../../refactoring-review/AGENTS.md);
the deterministic parts (gates, planning, merging, verification) are done only through
`scripts/refactoring-review.py` — never substituted by hand.

## Hard constraints

- Detectors are read-only: they edit no files, run no builds, commit nothing, invoke no skills,
  and spawn no agents. The brief states this; do not delete or alter these clauses when
  dispatching.
- One round per change (principle R7); refactor commits get no refactoring-review.
- Only T0/T1 land; T2 is only recorded with its destination. Triage can only tighten a tier,
  never loosen it.
- Refactor commits are separate from behavior commits, use `refactor:` in the message, and
  carry no behavior changes.
- A detector timeout, failure, or unparseable output is not "no findings"; rerun once, and if
  it still fails, record blocked.
- Do not modify `tools/llm-review-bench`; when handing over to code review, use it per the
  original `/multi-review` process.

## 0. Prerequisites

The behavior change has passed local verification and is committed, with no uncommitted
modifications in code paths (the script checks). The review baseline defaults to the merge-base
of `config.json`'s `defaultBase` and HEAD; run `git fetch` first.

## 1. Gate and plan

```bash
python3 scripts/refactoring-review.py plan [--base <ref>] [--reviewers <a,b,c>]
```

- `required: false`: write one reason line in the delivery report and go straight to step 8.
- `required: true`: read `<runDir>/plan.json`. `mode`, each detector's lens, files, budget,
  routes, and reviewer are all decided by the script; when `uncovered` is non-empty, list it
  honestly in the report and suggest splitting the change.
- `--reviewers` gives the available model or harness roster, and the script rotates
  deterministically by base/head; when not given, the main agent chooses, but must still record
  which reviewer each detector actually used.

## 2. Detection (parallel)

Dispatch with the script by default:

```bash
python3 scripts/refactoring-review.py detect <runDir> [--reviewer kimi|codex|claude] [--concurrency 3] [--only <ids>]
```

- The worktree must be at the plan head with code paths clean (the script checks); each
  detector's prompt is the full text of `<runDir>/agents/<id>/brief.md`; the reviewer comes
  from the plan's assignment, or `--reviewer` when unassigned.
- Read-only parameters match `tools/llm-review-bench/docs/adapters.md`; `git status` is
  compared before and after the run, and when the worktree was modified, all detectors are
  recorded `snapshot-modified`.
- Exit code 1 means a detector did not finish: rerun once with `--only <id>`; if it still
  fails, record blocked.
- You can also dispatch manually with this harness's subagents: the prompt is likewise the full
  brief text, and the final JSON is saved verbatim as
  `<runDir>/agents/<id>/findings.json`.

## 3. Merge

```bash
python3 scripts/refactoring-review.py merge <runDir>
```

Exit code 1 means a detector is missing/invalid: rerun once per the hard constraints, then
merge; if it still fails, record that detector blocked and continue. `merged.json` gives the
deduplicated findings (strictest tier wins), `flags` (`unknown-rule`,
`outside-agent-files`), and `conflicts` (different approaches for the same symbol).

## 4. Triage (the main agent verifies personally)

For each merged finding, in order:

1. Go back to the worktree and read the symbol; confirm the referenced code exists and the
   description is accurate; verify the flag first for flagged findings.
2. Recheck the tier against R1 (behavior-preserving) and R10 — tighten only.
3. Check `design/decision-log.md`, `docs/zh/design/idea-graveyard.md`, and `adr/` for whether
   the same direction was already rejected; if rejected, mark rejected with a citation.
4. For `out-of-diff` findings, check whether `BACKLOG.md` already registers them; record only,
   never fix.
5. Only for T0/T1 candidates to accept: read their `guardingTests` and confirm they cover the
   behavior to preserve; without coverage, downgrade to T2 or add tests first.
6. `conflicts` are not voted: prefer deletion and inlining per R9, and record both sides'
   reasons.
7. When acceptances exceed `limits.maxAcceptedFindings`, stop accepting; the rest are deferred.

## 5. Landing

Implement each accepted T0/T1 item into one or a few `refactor:` commits; the commit message
lists the merged ids (`R3`, `R5`) and states behavior-preserving plus the guarding tests.
Then:

- `mvn spotless:apply`, targeted tests, then full `mvn test`;
- For T1 items touching concurrency routes: `python3 scripts/check-race-claims.py`;
- Production code changed: judge PIT applicability and scope per `verification/pit.md`.

## 6. Verify

```bash
python3 scripts/refactoring-review.py verify <runDir>
```

- `growthExceeded`: withdraw the excess, turning it into a standalone task.
- `oracleEdits`: confirm item by item that they are only mechanical edits following a rename;
  otherwise revert the corresponding refactor and record it reverted, T2 (R8).
  When all are confirmed as renames, write "reviewed: rename-only" in the report.
- `nonRefactorCommits`: move behavior changes out of the refactor range.

## 7. Report

Write `<runDir>/report.md` per [refactoring-review/report-template.md](../../../refactoring-review/report-template.md):
each detector's status, each finding's disposition with reasons (including rejections), each
check's status, proposed checks for mechanizable rules, and T2 destinations (`BACKLOG.md`,
`design/proposal/`, or open items).
Keep the run directory as the audit record; delivery preserves it on the
`llm-ledger` branch before removing the worktree (Checkout Isolation in the
project-delivery workflow).

## 8. Hand over to code review

Review base..HEAD (including refactor commits) per `/multi-review`, for example
`node tools/llm-review-bench/dist/cli.js review --base <base> --commit HEAD --json`. In the
judgment phase:

- Findings already settled in the report serve as the baseline; when a reviewer repeats them,
  follow the original disposition;
- Additionally check the attack surfaces: whether each refactor commit truly preserves
  behavior, and each landed T1 item.
