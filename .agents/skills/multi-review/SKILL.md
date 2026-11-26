---
name: multi-review
description: In-project parallel multi-agent code review with recomputable evaluation. Use for multi-review, par-review, parallel review, cross review, reviewing the current worktree or a commit. Runs reviewers through the tools/llm-review-bench TypeScript CLI by default, keeps raw findings, applies explicit judgments, and produces recomputable reports; do not use the old Python multi_review.py or three-way vote scripts.
---

# /multi-review

This file is the project-level entry: it handles orchestration and judgment only, and defines no
evaluation algorithm. The storage protocol, finding normalization, and metric definitions are
governed by
[tools/llm-review-bench/docs/protocol.md](../../../tools/llm-review-bench/docs/protocol.md);
the clustering and verdict rules by
[tools/llm-review-bench/docs/clusters.md](../../../tools/llm-review-bench/docs/clusters.md);
the live contract is [design/llm-review-benchmark.md](../../../design/llm-review-benchmark.md).
A user-level skill of the same name still exists on this machine, but in-project daily
`/multi-review` follows this file.

## Hard constraints

- The only execution path is `tools/llm-review-bench/dist/cli.js`. MUST NOT call
  `~/.agents/skills/multi-review/scripts/multi_review.py`, `vote`/`tally`, or any self-built
  Python orchestration.
- Do not modify the implementation inside `tools/llm-review-bench`; use it only through the
  CLI. Report needed new capabilities to the user instead of changing the tool in place.
- A reviewer's severity is only a reference; the main agent must read the code and verify
  personally before writing a verdict.
- A timed-out / failed reviewer is not 0 findings and must not be counted into the denominator
  as an empty result; record and explain by status.
- Setup failure, empty output, and parse failure are not "no problems found"; report the actual
  error and stop or retry.
- An open-world run MUST NOT fabricate recall; report `n/a` without gold and never write gold
  for the sake of a number.
- A same-run self-fix is remediation and MUST NOT be backfilled as that run's independent gold.

## 1. Build

Run from the repository root; build first when `dist/` is missing:

```bash
set -e
if [ ! -f tools/llm-review-bench/dist/cli.js ]; then
  npm --prefix tools/llm-review-bench ci
  npm --prefix tools/llm-review-bench run build
fi
```

Use `--systems <file>` to pin reviewer provenance; use `--agents` to run a subset of reviewers.
By default, available real CLIs on `PATH` are auto-selected; fixtures are never used.

### Reviewer selection

Select seats from what runs on this machine today, not from a fixed model list. A full review
needs two reviewer seats whose model families differ from the implementer's and from each
other ([review procedure](../project-delivery/references/review.md)); a lightweight review needs
one seat that differs from the implementer. Done when every seat has a concrete, pinned model
whose family is known and whose smoke prompt answered.

1. **Inventory seats.** A seat is a bench adapter (`ls tools/llm-review-bench/src/adapters/`)
   whose CLI is on `PATH`, paired with a model that CLI can route:
   - When `magpie` is on `PATH`, `magpie ls` lists the managed agents and their current
     selection, and `magpie models <agent>` lists every model the agent can route. The
     `magpie <agent> model` / `effort` commands print a labelled line; the value is its last
     whitespace-delimited token, and `default` means `null`.
   - For a CLI outside Magpie, read its own model list (for example
     `kiro-cli chat --list-models`).
2. **Resolve families.** Name each candidate's model family (Claude, GPT, GLM, Kimi, DeepSeek,
   MiniMax, Qwen, ...) from the concrete model id. An aggregator alias whose target is hidden
   (for example an `auto` route) has an unknown family: use it only after its routed model is
   confirmed, and never count it as distinct. Include the implementer's own model.
3. **Choose seats.** Prefer the current Magpie selection when its family qualifies; otherwise
   pick another routable model of a qualifying family. Pin it per run in the systems file
   (`model`, `provider` = the model id's prefix before the first `/`, `effort` from the
   model's supported levels or `null`); adapters pass `--model`, so the shared Magpie selection
   stays untouched. If a seat can only change through Magpie, restore the previous selection
   afterwards.
4. **Smoke-test each seat** with a one-line prompt through the same CLI and model, bounded by a
   short timeout (macOS has no `timeout`; use
   `perl -e 'alarm shift; exec @ARGV' 120 <command>`). The CLI's own probe only checks
   `--version`, so a seat that hangs passes it.
5. **Replace failing seats.** A seat that fails or hangs in the smoke test or in the run moves to
   the next qualifying candidate; keep the failed attempt as evidence. When no qualifying
   candidate is left, record the review as blocked.

Each systems file names an adapter at most once; two seats on one adapter (for example two
`codex` seats on different Magpie models) run as separate cases on the same range. A CLI with
no bench adapter (for example ZCode) gives an external review recorded separately, not a
benchmark-counted seat. Record each run's routed model, provider and effort.

Example: two `codex` seats routed by Magpie, one file per run:

```json
{"systems": [{"agent": "codex", "provider": "deepseek", "model": "deepseek/deepseek-v4-pro", "effort": "high"}]}
```

```json
{"systems": [{"agent": "codex", "provider": "zcode", "model": "zcode/GLM-5.4", "effort": "high"}]}
```

## 2. Choose a case and run

Pick one per the user's goal; do not mix:

```bash
# Uncommitted changes (default first choice; use --commit when the worktree is clean)
node tools/llm-review-bench/dist/cli.js review --working --json

# A single commit; a root commit needs an explicit --base
node tools/llm-review-bench/dist/cli.js review --commit <sha> --json
node tools/llm-review-bench/dist/cli.js review --base <base> --commit <sha> --json

# Explicit fix benchmark: reviews buggy = fix^, with fix producing only candidate gold
node tools/llm-review-bench/dist/cli.js run --fix <fix-sha> --json

# Retry only failed/timed-out reviewers (not a new case)
node tools/llm-review-bench/dist/cli.js resume <run-id> --json
```

`run --fix` does not accept `--working`/`--commit`/`--base`; `review --working` cannot combine
with `--commit`. The default output root is `<repo>/.review-benchmark`, overridable with
`--output`.

Before starting, the CLI probes the selected reviewers: missing binaries, non-zero exits, and
probe timeouts are all reported before the run is created instead of surfacing after a long
review. At runtime each reviewer has a hard timeout and a SIGTERM→SIGKILL wind-down; shorten or
relax per task:

```bash
node tools/llm-review-bench/dist/cli.js review --commit <sha> \
  --review-timeout 900 --timeout 1200 --kill-grace 10 --json
```

Status and progress are queryable during a run:

```bash
node tools/llm-review-bench/dist/cli.js status <run-id> --json
node tools/llm-review-bench/dist/cli.js status <run-id> --watch --interval 5
```

The status lists each reviewer's attempt, state, log bytes, last activity time, and `stale`
flag. The run directory's `events.jsonl` periodically writes `review.progress`, so even before
a reviewer finishes you can tell whether it is producing output, stalled, or already
timeout-terminated. `stale` only means no heartbeat within the progress observation window —
not a review failure; the attempt's `completed`/`failed`/`timed_out` is authoritative.

## 3. Read the run and raw findings

Read `runId`, `runDir`, `systems`, `gold`, `reports` from the JSON stdout; the run id is also
printed without `--json`.

- Raw findings are at `<runDir>/reviews/<agent>/attempt-N.json`.
- When `status` is not `completed`, `findings` is `null`; this is not an empty review. Read
  `<runDir>/logs/<agent>-attempt-N.*.log` first, and `resume` when needed.
- Do not read `derived/` to guess conclusions; judgment input is raw findings plus code, not
  the previous verdict version.
- Pending, stale adjudication, unclustered, and incomplete reviewers in the report `notes` must
  be kept and explained.

## 4. Verify personally and produce versioned judgments

Read and follow [docs/clusters.md](../../../tools/llm-review-bench/docs/clusters.md) first.
Verify each finding back against `<runDir>/snapshot/`, callers, and tests; write a failing test
when you can, mark `uncertain` when you cannot prove — never lower the bar to fill `confirmed`.

Produce three explicit JSONs (shapes in [docs/protocol.md](../../../tools/llm-review-bench/docs/protocol.md)
sections 7–8):

- `clusters.json`: partition every finding of every completed reviewer exactly once; omissions,
  duplicates, and fabricated IDs are all rejected.
- `verdicts.json`: one of `confirmed` / `rejected` / `neutral` / `uncertain` per cluster;
  unwritten clusters are `pending` and excluded from denominators.
- `gold.json`: for historical cases only; top-level fields include
  `status`/`confirmedBy`/`note`/`labels`/`excludedFixScope` (`provenance` is a record field the
  tool writes, not an input field); each label must give `introducedByBuggyCommit` explicitly,
  and confirmed gold also needs `confirmedBy`. An open-world/live run has no confirmable gold
  labels (the tool only records the open-world state).

```bash
node tools/llm-review-bench/dist/cli.js apply-judgment <run-id> \
  --clusters clusters.json --verdicts verdicts.json \
  --judge-kind agent --judge-name <name> --judge-model <model> --json

# add --gold only for historical cases; confirmed gold needs confirmedBy (in JSON or via flag)
node tools/llm-review-bench/dist/cli.js apply-judgment <run-id> \
  --clusters clusters.json --verdicts verdicts.json --gold gold.json \
  --confirmed-by <name> \
  --judge-kind agent --judge-name <name> --judge-model <model> --json
```

`apply-judgment` re-evaluates and renders reports by default; use `--no-rescore` to import
without evaluating. To recompute:

```bash
node tools/llm-review-bench/dist/cli.js rescore <run-id> --json
node tools/llm-review-bench/dist/cli.js report <run-id> --json
```

A new judgment must form a new `vN`; never hand-edit existing versions under `derived/` or
`gold/`.

## 5. Fixes and reporting

- Keep normal code review's fixing duties: confirmed P0/obvious defects get minimal fixes,
  tests added, the project's format and test commands run, and commits made per project rules.
  List unfixable items in the report.
- A same-run self-fix serves only as remediation — never written back into that run's confirmed
  gold and never treated as independent gold; `link` is only for later fixes independent of
  that run (see section 6).
- Report to the user: run id, each reviewer's status/findings,
  confirmed/rejected/neutral/uncertain/pending counts, recall (when gold exists and is
  computable) or `n/a` with the reason, report paths, and unfixed items.
- Wrap up: `node tools/llm-review-bench/dist/cli.js cleanup <run-id>` deletes worktrees and
  temporary agent output, keeping the evidence.

## 6. Later fixes and rescoring

When an independent fix appears later, do not rerun reviewers:

```bash
node tools/llm-review-bench/dist/cli.js link --run <run-id> --fix <fix-sha> --json
# Read the linked gold (candidate by default); after confirmation, import confirmed gold with
# apply-judgment --gold, and write the independence basis into gold.json's note
# (recorded as provenance.note)
node tools/llm-review-bench/dist/cli.js rescore <run-id> --json
node tools/llm-review-bench/dist/cli.js report <run-id> --json
```

`link` requires the fix to descend from the run head; it only writes new gold/evaluation
versions and calls no models. `rerun <run-id>` is a new run (new run id, same immutable inputs,
reviewers rerun, old judgments not inherited) — not `resume`.

## 7. Reading results

- A rate with a zero denominator is `n/a`/`null`, not 0.
- Candidate/open-world gold recall is `null`; never write it as 0 or a percentage in reports.
- One case says something about one measured system, not a model ranking; compare with
  `leaderboard`, keeping agent/model/revision/effort provenance.
- Failed/timeout and completed reviewers must not mix into one denominator; reports list them
  separately.
