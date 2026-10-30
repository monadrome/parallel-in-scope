# llm-review-bench protocol

This document is the public storage and evaluation contract for `llm-review-bench` 0.1.x. All JSON
examples are abbreviated; omitted fields still exist in the records.

## 1. Immutability, atomicity, and paths

- **Write-once**: `run.json`, everything under `inputs/`, `reviews/<agent>/attempt-N.json`, per-attempt
  logs, every `derived/*/vN.json`, every `gold/<run-id>/vN.json`, and report files are never overwritten.
  A second write fails with an `immutable_write` error.
- **Atomic replace**: `state.json` is written to a temporary file in the same directory, fsynced,
  renamed over the old file, and the parent directory is fsynced. Readers never see a partial state.
- **Append-only events**: `events.jsonl` is opened in append mode and fsynced after each line. Sequence
  numbers start at 1 and increase by one.
- **Strict JSON**: duplicate object keys, trailing commas, comments, NaN, and invalid UTF-8-adjacent
  syntax are rejected. A UTF-8 BOM is stripped before parsing.
- **Path safety**: run IDs, agent names, cluster IDs, and gold IDs are single safe path segments.
  Finding and gold file paths must be repository-relative POSIX paths; absolute paths, backslashes,
  NUL, `.`/`..`, and escapes are rejected. Every path derived from user input is checked to remain
  inside its intended root.
- **Versioning**: derived records are allocated `v1`, `v2`, ... by scanning the directory. Existing
  versions are never rewritten. Evaluation records reference the exact versions they used, and report
  commands load those exact versions rather than "the latest" so old evaluations remain reproducible.

## 2. Run manifest

`runs/<run-id>/run.json` is written once, before the first reviewer starts.

```json
{
  "schemaVersion": "llm-review-bench/run/v1",
  "runId": "repo-5195a218-20261029-174824-1a2b3c",
  "mode": "historical",
  "source": "fix",
  "rerunOf": null,
  "working": null,
  "createdAt": "2026-10-29T17:48:24.000Z",
  "tool": { "name": "llm-review-bench", "version": "0.1.0" },
  "repository": { "root": "/repo", "name": "repo" },
  "commits": {
    "base": "084faba...",
    "head": "5195a21...",
    "fixCommit": "c135f9f..."
  },
  "config": {
    "systems": [
      {
        "agent": "codex", "version": "codex-cli 0.162.1", "provider": "openai",
        "model": "gpt-5-codex", "revision": null, "effort": "high",
        "command": null, "extraArgs": [], "fixture": null
      }
    ],
    "budget": 20,
    "concurrency": 3,
    "timeouts": {
      "totalSeconds": 3060, "reviewSeconds": 2700,
      "adjudicationSeconds": 300, "cleanupSeconds": 60, "killGraceSeconds": 10
    },
    "maxOutputBytes": 8388608
  },
  "paths": {
    "inputsDir": "inputs", "snapshotDir": "snapshot", "reviewsDir": "reviews",
    "logsDir": "logs", "derivedDir": "derived", "reportsDir": "reports"
  },
  "hashes": {
    "contextSha256": "…", "contextJsonSha256": "…", "diffSha256": "…",
    "caseManifestSha256": "…", "promptSha256": "…"
  },
  "promptPath": "inputs/prompt.md"
}
```

`SystemIdentity` is the part that makes a result attributable: `agent`, `version`, `provider`, `model`,
`revision`, `effort`. Missing provenance is `null`, never guessed. `version` is detected by running the
CLI's `--version` unless the systems file pins it.

The case manifest in `inputs/manifest.json` records `source` (`fix`, `commit`, or `working`),
`rerunOf`, the `working` snapshot descriptor when applicable, the repository, commits, range label,
diff hash, file statistics, context hash/source, context JSON hash, snapshot head, and detected
repository rule files. `inputs/context.json` holds the same structured facts (source, rerunOf, working snapshot, objective,
commits, range, diff files, context source, snapshot policy, rules files) as an immutable
machine-readable audit artifact.

## 3. Case semantics

### Historical (`run --fix <fix>`)

1. Resolve `fix` to a full commit.
2. Require exactly one parent: `buggy = fix^`.
3. Require `buggy` to have a parent: `base = buggy^`.
4. Review range is `buggy^..buggy`; the worktree is checked out at `buggy`.
5. The fix diff (the fix commit's own change) is read only to generate **candidate** gold labels.

The fix commit itself is never included in the reviewed diff, and candidate labels are never confirmed
automatically.

### Working tree (`review --working`)

`review --working` captures the final working-tree state without changing the real index or worktree:

1. Build a temporary index from `HEAD` (`GIT_INDEX_FILE`), run `git add -A -- .` against that temporary
   index, and write a tree. Staged, unstaged, and untracked (non-ignored) files are included.
2. Create a snapshot commit with `git commit-tree`, parent `HEAD`, under an internal identity.
3. Point `refs/llm-review-bench/<run-id>` at the snapshot commit so cleanup, resume, rerun, and git GC
   retain it. The ref is namespaced and outside the benchmark root; cleanup does not delete it.
4. Record `source: "working"`, `base = HEAD`, `head = snapshot commit`, and
   `working = { baseCommit, snapshotCommit, ref }`. No fix commit exists, so gold is open-world.

A clean working tree is an error; use `review --commit` instead. `--working` cannot be combined with
`--commit`. `--working --base <rev>` is allowed: the review range is `<rev>..<snapshot>`, the snapshot
parent remains HEAD, and `<rev>` must be an ancestor of HEAD. The reviewed diff therefore includes
committed changes since `<rev>` plus the working changes.

### Live (`review`)

`head` is `--commit` or `HEAD`; `base` is `--base` or `head^`. A root commit requires an explicit
`--base`. No gold labels exist; the gold record is `status: "open-world"`. `link --run <id> --fix <sha>`
can later attach a fix that descends from `head`.

### Context reuse and rerun

`inputs/context.md`, `inputs/diff.patch`, `inputs/manifest.json`, and `inputs/prompt.md` are immutable.
`resume`, `rerun`, `link`, `rescore`, `report`, and `apply-judgment` verify their sha256 hashes against
`run.json`; a changed file aborts the command. A resume never regenerates context from the repository.
If the snapshot was removed by cleanup, `resume` recreates a worktree at the recorded head; if the
snapshot is dirty, resume fails and asks for cleanup.

`rerun <run-id>` creates a **new run id**, verifies the old inputs, copies `context.md`, `diff.patch`,
and `prompt.md` byte-for-byte, reuses the exact system config and commits, and writes a new
`context.json`, case manifest, and run manifest with `rerunOf = <old-run-id>`. It checks out the
recorded head (working snapshots use their ref), runs every selected reviewer from attempt 1, and
derives fresh identity dedup and candidate/open-world gold. It never copies reviews, dedup,
adjudication, or confirmed gold from the old run, so an old verdict can never become a new reviewer
result. The old run files are not modified. A rerun works after `cleanup` because inputs are retained
and the snapshot commit is reachable (`refs/llm-review-bench/<old-id>` for working runs).

The initial context is generated from the commit subject/body, diff statistics, file list, and detected
rule files. The historical context withholds the fix commit SHA from reviewers; the fix remains in the
audit metadata (`run.json`, `inputs/manifest.json`, `inputs/context.json`) and never in the prompt,
worktree, or agent-output directory. `--context FILE` supplies an explicit immutable context instead.
In both cases later commands read the stored copy.

## 4. State machine

`state.json` tracks run status and per-step status.

```json
{
  "schemaVersion": "llm-review-bench/state/v1",
  "runId": "…",
  "status": "partial",
  "createdAt": "…",
  "updatedAt": "…",
  "steps": {
    "case":       { "status": "completed", "updatedAt": "…", "error": null },
    "reviews": {
      "codex": {
        "agent": "codex", "status": "completed", "updatedAt": "…",
        "attempts": [
          {
            "attempt": 1, "status": "completed", "startedAt": "…", "endedAt": "…",
            "durationMs": 474000, "exitCode": 0, "timedOut": false,
            "error": null, "recordPath": "reviews/codex/attempt-1.json",
            "logBase": "logs/codex-attempt-1"
          }
        ]
      }
    },
    "derivation":  { "status": "completed", "updatedAt": "…", "error": null },
    "evaluation":  { "status": "completed", "updatedAt": "…", "error": null },
    "report":      { "status": "completed", "updatedAt": "…", "error": null },
    "cleanup":     { "status": "pending",   "updatedAt": "…", "error": null }
  },
  "eventSeq": 14
}
```

Step statuses: `pending`, `running`, `completed`, `failed`, `timed_out`.

Allowed transitions:

| From | To |
|---|---|
| `pending` | `running`, `completed`, `failed` |
| `running` | `completed`, `failed`, `timed_out` |
| `failed`, `timed_out` | `running` (resume/retry) |
| `completed` | none |

Run status is derived from the case and reviewer steps:

| Condition | Run status |
|---|---|
| case failed | `failed` |
| all selected reviewers completed | `completed` |
| some completed, remaining failed/timed out | `partial` |
| none completed, all failed/timed out | `failed` |
| case prepared and reviewers pending/running | `running` |
| initial state before case preparation | `created` |

A failed or timed-out reviewer is never represented as an empty review. Its review record has
`findings: null`; evaluation reports the system status and excludes it from rates. Resume retries
failed/timed-out reviewers with a new attempt number, leaving old records/logs intact.

## 5. Events

`events.jsonl` contains one strict JSON object per line:

```json
{
  "schemaVersion": "llm-review-bench/event/v1",
  "seq": 7,
  "at": "2026-10-29T17:50:00.000Z",
  "runId": "…",
  "type": "review.completed",
  "agent": "codex",
  "step": "review",
  "data": { "attempt": 1, "durationMs": 474000, "exitCode": 0, "findings": 3 }
}
```

Event types include `run.created`, `case.prepared`, `review.started`, `review.completed`,
`review.failed`, `review.timed_out`, `reviews.recovered`, `dedup.written`, `gold.written`,
`gold.linked`, `judgment.clusters`, `judgment.verdicts`, `judgment.gold`, `evaluation.written`,
`report.written`, `step.started`, `step.completed`, `step.failed`, `cleanup.completed`, `run.completed`,
`run.resumed`, and `rescore.completed`.

## 6. Review records and findings

`reviews/<agent>/attempt-N.json` is written after every attempt, including failures and timeouts.

```json
{
  "schemaVersion": "llm-review-bench/review/v1",
  "runId": "…", "agent": "codex", "attempt": 1,
  "status": "completed",
  "system": { "agent": "codex", "version": "…", "provider": "openai", "model": "…", "revision": null, "effort": "high" },
  "startedAt": "…", "endedAt": "…", "durationMs": 474000,
  "exitCode": 0, "timedOut": false,
  "usage": {
    "inputTokens": 390489, "outputTokens": 6340, "totalTokens": 396829,
    "cost": null, "currency": null
  },
  "command": { "file": "codex", "args": ["exec", "…"], "cwd": "/…/snapshot" },
  "summary": "…",
  "findings": [
    {
      "id": "codex:0", "reviewer": "codex", "attempt": 1,
      "file": "src/main/java/…/TaskGroup.java",
      "lineStart": 578, "lineEnd": 580,
      "title": "…", "trigger": "…", "consequence": "…", "evidence": "…",
      "severity": "P0", "confidence": 0.95, "suggestedFix": "…"
    }
  ],
  "rawFindings": [
    { "file": "src/main/java/…/TaskGroup.java", "lineStart": 578, "lineEnd": 580, "title": "…" }
  ],
  "budgetDropped": 0,
  "parseWarnings": [],
  "error": null,
  "stdoutPath": "logs/codex-attempt-1.stdout.log",
  "stderrPath": "logs/codex-attempt-1.stderr.log",
  "messagePath": null,
  "worktreeModified": false
}
```

`usage` records what the adapter reliably reported: OCR's `summary` token fields and Codex's explicit
`tokens used` footer are parsed; other adapters leave unknown fields null. Cost is only recorded when
the CLI reports it; it is never estimated, and a missing currency stays null.

`rawFindings` holds every canonically parsed finding before budget truncation; `findings` is the
budgeted, ID-bearing view used by evaluation. For `failed`/`timed_out`, `summary`, `findings`,
`rawFindings`, and `budgetDropped` are `null`, while `error` holds the reason. Reviewers may return at
most `config.budget` normalized findings; extra findings are counted in `budgetDropped` and the raw
response remains in the log/last-message file.

### Normalization

The prompt asks for camelCase fields. The parser also accepts common aliases so real CLIs can be used
without a schema shim:

| Canonical | Accepted aliases |
|---|---|
| `file` | `file_path`, `filepath`, `path`, `filename` |
| `lineStart` | `line_start`, `startLine`, `start_line`, `line`, `line_number` |
| `lineEnd` | `line_end`, `endLine`, `end_line`, `end_line_number` |
| `title` | `message`, `summary`, `name` |
| `trigger` | `description`, `reproduction`, `repro` |
| `consequence` | `impact`, `risk` |
| `evidence` | `proof`, `detail`, `details`, `code` |
| `suggestedFix` | `suggested_fix`, `suggestion`, `fix`, `recommendation` |
| `severity` | `priority`, `level`; `P0`-`P3`, `critical`/`high`/`medium`/`low`, `error`/`warning`/`info`, 0-3 |
| `confidence` | `certainty`; `0..1`, percentage `1..100`, default `0.5` with a warning |

Missing `trigger`, `consequence`, `evidence`, or `suggestedFix` becomes `""` with a parse warning.
Missing `file` or `title`, an unsafe path, an unknown severity, confidence above 100, or a malformed
JSON payload fails the attempt. The original response is never discarded.

The parser accepts one JSON object, a JSON array of findings, a fenced JSON block, a nested
`result`/`review`/`data`/`output` wrapper, or Kimi's `stream-json` event stream. Output with no JSON and
no findings array is a failure, not an empty review.

## 7. Dedup and adjudication

### Dedup versions

`derived/dedup/vN.json`:

```json
{
  "schemaVersion": "llm-review-bench/dedup/v1",
  "runId": "…", "version": 2, "createdAt": "…",
  "method": "imported",
  "note": "human adjudication: merge duplicate code findings",
  "clusters": [
    { "clusterId": "C01", "findingIds": ["codex:0", "claude:1"], "note": "same root cause" },
    { "clusterId": "C02", "findingIds": ["codex:2"], "note": "independent test gap" }
  ]
}
```

Without an import, dedup v1 is `method: "identity"` and every finding is its own cluster. This is a
deliberate statement that identity clustering is not semantic deduplication. An import must partition
every finding from every completed review exactly once; unknown, missing, or duplicate finding IDs are
errors. Cluster IDs are optional and default to `C01`, `C02`, ...

When the set of completed findings changes after an adjudication exists, evaluation keeps the existing
imported dedup (so old verdicts are not silently applied to different findings) and reports new
findings as unclustered/pending. If no adjudication targets the old dedup version, a new identity dedup
version is created and evaluation uses it.

### Adjudication versions

`derived/adjudication/vN.json`:

```json
{
  "schemaVersion": "llm-review-bench/adjudication/v1",
  "runId": "…", "version": 1, "dedupVersion": 2, "createdAt": "…",
  "judge": { "kind": "human", "name": "maintainer", "model": null, "note": null },
  "verdicts": [
    {
      "clusterId": "C01", "verdict": "confirmed",
      "goldLabelIds": ["G1"], "rationale": "trigger reproduces the failure",
      "confidence": 1
    },
    { "clusterId": "C02", "verdict": "uncertain", "goldLabelIds": [], "rationale": null, "confidence": null }
  ]
}
```

Four verdicts only: `confirmed`, `rejected`, `neutral`, `uncertain`. Missing clusters are `pending`.
`dedupVersion` binds the verdicts to one clustering. Evaluation applies an adjudication only when
`dedupVersion` equals the dedup version being evaluated; otherwise the verdicts are treated as pending
and a note explains the mismatch. Verdict imports may be partial. Duplicate cluster verdicts and
unknown cluster IDs are errors; `goldLabelIds` must exist if a gold record is present.

## 8. Gold

`<root>/gold/<run-id>/vN.json` is outside `runs/<run-id>/` on purpose.

```json
{
  "schemaVersion": "llm-review-bench/gold/v1",
  "runId": "…", "version": 2, "createdAt": "…",
  "status": "confirmed",
  "case": { "fixCommit": "…", "base": "…", "head": "…" },
  "provenance": {
    "source": "explicit-json", "confirmedBy": "maintainer",
    "note": "regression reproduced on buggy, passes on fix"
  },
  "labels": [
    {
      "goldId": "G1", "kind": "known-fix",
      "file": "src/main/java/…/TaskGroup.java", "lineStart": 578, "lineEnd": 580,
      "rootCause": "barrier increment skipped when an external callback throws",
      "fixMechanism": "move the increment into finally",
      "regressionTest": "TaskGroupConvergenceTest#…",
      "introducedByBuggyCommit": true,
      "evidence": "buggy: 3/3 timeout; fix: pass"
    }
  ],
  "excludedFixScope": [
    { "reason": "pre-existing, not introduced by this commit", "rootCause": "…", "goldId": null }
  ]
}
```

Statuses:

- `open-world`: live review, no fix, no labels.
- `candidate`: generated from the fix diff. Every label has `introducedByBuggyCommit: null`; recall is
  withheld. Candidate labels are a convenience for adjudication, not truth.
- `confirmed`: explicit JSON only. Every label must state `introducedByBuggyCommit` as a boolean, and
  a confirmer is required. Confirmed gold with no labels is rejected.

`kind` is `known-fix` or `regression`. Only labels with `introducedByBuggyCommit: true` enter recall
denominators. A label with `false` is pre-existing/out of scope and is excluded, not counted as a miss.
`excludedFixScope` records that reasoning in the gold record.

Gold status and version are immutable once written. Re-confirming creates a new version.

## 9. Evaluation

`derived/evaluation/vN.json` is both the metrics record and the JSON report.

Selected fields:

```json
{
  "schemaVersion": "llm-review-bench/evaluation/v1",
  "runId": "…", "version": 3, "createdAt": "…",
  "inputs": {
    "dedupVersion": 2, "adjudicationVersion": 1, "goldVersion": 2,
    "reviews": [{ "agent": "codex", "attempt": 1, "sha256": "…" }]
  },
  "case": {
    "mode": "historical", "source": "fix", "rerunOf": null, "openWorld": false,
    "base": "…", "head": "…", "fixCommit": "…",
    "goldStatus": "confirmed", "goldEligibleLabels": 1
  },
  "usage": {
    "inputTokens": 390489, "outputTokens": 6340, "totalTokens": 396829,
    "systemsWithUsage": 1, "systemsTotal": 2,
    "costsByCurrency": [{ "currency": "USD", "cost": 0.02 }]
  },
  "systems": {
    "codex": {
      "identity": { "agent": "codex", "version": "…", "provider": "…", "model": "…", "revision": null, "effort": "…" },
      "status": "completed", "durationMs": 474000, "attempts": 1, "timeout": false,
      "findings": {
        "total": 3, "adjudicated": 3, "confirmed": 3,
        "rejected": 0, "neutral": 0, "uncertain": 0, "pending": 0
      },
      "rates": { "confirmed": 1, "rejected": 0, "neutral": 0, "uncertain": 0 },
      "recall": {
        "knownFix": 1, "knownFixHits": 1, "knownFixEligible": 1,
        "regression": 1, "regressionHits": 1, "regressionEligible": 1,
        "reason": null
      },
      "usage": { "inputTokens": 390489, "outputTokens": 6340, "totalTokens": 396829, "cost": null, "currency": null },
      "goldHits": ["G1"], "error": null
    }
  },
  "union": {
    "clusters": { "total": 4, "adjudicated": 4, "confirmed": 3, "rejected": 1, "neutral": 0, "uncertain": 0, "pending": 0 },
    "findings": { "total": 6, "adjudicated": 6, "confirmed": 4, "rejected": 2, "neutral": 0, "uncertain": 0, "pending": 0 },
    "rates": { "confirmed": 0.75, "rejected": 0.25, "neutral": 0, "uncertain": 0 },
    "findingRates": { "confirmed": 0.666, "rejected": 0.333, "neutral": 0, "uncertain": 0 },
    "recall": {
      "knownFix": 1, "knownFixHits": 1, "knownFixEligible": 1,
      "regression": 1, "regressionHits": 1, "regressionEligible": 1,
      "reason": null
    },
    "goldHits": ["G1"]
  },
  "aggregate": {
    "micro": { "confirmed": 0.666, "rejected": 0.333, "neutral": 0, "uncertain": 0 },
    "macro": { "confirmed": 0.666, "rejected": 0.333, "neutral": 0, "uncertain": 0 },
    "systemsAdjudicated": 1
  },
  "notes": ["…"]
}
```

Definitions:

- **System rate**: over that reviewer's findings that belong to clusters with a verdict. A finding in a
  pending cluster is counted as pending, not in the denominator.
- **Union rate**: over deduplicated clusters with a verdict. `union.findingRates` gives the same four
  rates over the findings inside those clusters, and `union.findings` provides the counts.
- **Micro**: pooled across all reviewer findings (equivalent to `union.findingRates` when every finding
  belongs to exactly one cluster).
- **Macro**: arithmetic mean of each reviewer rate, skipping undefined rates. `systemsAdjudicated`
  counts reviewers with a nonzero adjudicated denominator.
- **Zero denominator**: each rate is `null`; reports render `n/a`.
- **Recall**: numerator = eligible confirmed labels referenced by at least one confirmed cluster that
  contains a finding from that reviewer (or any reviewer for the union). Denominator = confirmed labels
  with `introducedByBuggyCommit === true` and the matching kind. Candidate/open-world gold yields
  `null` plus a reason; a confirmed gold with no eligible labels of that kind also yields `null`, and so
  does a run with no adjudicated clusters. Recall is never inferred from a text match; a cluster must
  explicitly list the gold label in `goldLabelIds`.
- **Failed reviewer**: `status` is not `completed`; recall is `null` and the reviewer is excluded from
  rates. Its timeout/failure is never converted to an empty review.
- **Usage**: per-reviewer usage comes from the latest completed attempt; failed/timeout reviews have
  `null`. Evaluation totals sum only known values and report how many systems supplied usage. Cost is
  grouped by currency; different currencies are never added.
- **Recall counts**: each `RecallSet` carries `knownFixHits`, `knownFixEligible`, `regressionHits`,
  and `regressionEligible` alongside the rates. The leaderboard uses these counts to aggregate weighted
  recall over applicable cases instead of averaging rates over cases with different denominators.

`notes` is part of the contract: it explains identity clustering, candidate/open-world withholding,
pending clusters, stale adjudication versions, unclustered findings, and incomplete reviewers.

## 10. Command semantics

- `run` / `review` create a run, prepare the worktree and immutable inputs, execute all selected
  reviewers, create identity dedup if needed, create open-world/candidate gold if none exists, write
  an evaluation, and render reports. `review --working` first captures a working-tree snapshot and its
  ref as described in section 3.
- `resume` verifies immutable inputs, recreates a missing snapshot when reviewers remain, retries only
  non-completed reviewers, then re-derives and re-evaluates. Completed reviewer records are not
  replaced. Resume does not read a changed source context file.
- `link` verifies the run's head is an ancestor of the new fix, creates a new gold version (candidate
  from the fix diff or an explicit `--gold`), then creates a new evaluation and report. It never
  invokes an adapter.
- `rescore` reads raw reviews and existing derived/gold records, creates a new dedup only when needed,
  writes a new evaluation, and does not render reports or invoke an adapter.
- `report` loads the latest evaluation and its exact input versions, then writes versioned HTML/CSV/JSON.
  Existing report files for that evaluation version are left untouched.
- `apply-judgment` imports any combination of gold, clusters, and verdicts in dependency order
  (gold, clusters, verdicts), then by default rescoring and rendering. `--no-rescore` writes only the
  imported versions.
- `rerun <run-id>` is not `resume`: it creates a new run, copies immutable case inputs and system
  config, reruns every reviewer, and records `rerunOf`. It does not copy old reviews, dedup,
  adjudication, or confirmed gold. See section 3.
- `leaderboard` reads the latest evaluation version of each local run. It groups systems by
  `agent + model + revision + effort`, aggregates case/completed/timeout/failure counts, micro
  (pooled) and macro (per-case mean) rates, recall by weighted eligible-label counts, timeout rate,
  completed duration, tokens, and per-currency costs. Fixture systems are excluded unless
  `--include-fixture` is passed. It writes no files and never calls a model.
- `cleanup` removes the worktree and `agent-output/`, marks the cleanup step complete, and retains all
  evidence. It deliberately keeps `refs/llm-review-bench/<run-id>` so a working snapshot can be rerun;
  there is no automatic ref-pruning command. `--dry-run` reports without removing. `--all` iterates
  runs and continues past per-run errors.

## 11. Leaderboard output

`leaderboard --json` emits `llm-review-bench/leaderboard/v1`:

```json
{
  "schemaVersion": "llm-review-bench/leaderboard/v1",
  "generatedAt": "…",
  "root": "/repo/.review-benchmark",
  "includeFixture": false,
  "runsScanned": 12,
  "evaluationsUsed": 10,
  "skipped": [{ "runId": "…", "reason": "no evaluation version" }],
  "groups": [
    {
      "agent": "codex", "model": "gpt-5-codex", "revision": "r1", "effort": "high",
      "providers": ["openai"], "versions": ["codex-cli 0.162.1"],
      "cases": 5, "completedCases": 4, "timeoutCases": 1, "failedCases": 0,
      "notRunCases": 0, "openWorldCases": 2, "confirmedGoldCases": 3, "rerunCases": 1,
      "findings": { "total": 12, "adjudicated": 10, "confirmed": 7, "rejected": 2, "neutral": 1, "uncertain": 0, "pending": 2 },
      "micro": { "confirmed": 0.7, "rejected": 0.2, "neutral": 0.1, "uncertain": 0 },
      "macro": { "confirmed": 0.65, "rejected": 0.25, "neutral": 0.1, "uncertain": 0 },
      "knownFixRecall": { "rate": 0.75, "hits": 3, "eligible": 4, "cases": 3, "reason": null },
      "regressionRecall": { "rate": null, "hits": null, "eligible": null, "cases": 0, "reason": "no case had an applicable regression recall denominator" },
      "timeoutRate": 0.2,
      "durationMs": { "total": 120000, "mean": 30000, "count": 4 },
      "usage": {
        "inputTokens": 100000, "outputTokens": 5000, "totalTokens": 105000,
        "casesWithUsage": 4,
        "costsByCurrency": [{ "currency": "USD", "cost": 0.42 }]
      }
    }
  ],
  "notes": ["…"]
}
```

Only the latest evaluation version per run is used. Reruns are separate cases (`rerunCases`). Recall
aggregates only per-case denominators that were applicable (confirmed gold and adjudicated); missing
cases are excluded rather than treated as misses. Grouping is by `agent + model + revision + effort`;
provider and CLI versions are metadata. Different currencies are separate entries in
`costsByCurrency`, never summed.

## 12. Error behavior

- Usage errors (bad flags, missing arguments, impossible timeout budget) exit 2 with usage text.
- Validation/state/immutable/path errors exit 1 with a lowercase, actionable message naming the
  offending value or path.
- Adapter failures are not command failures: they are recorded in the run, the remaining reviewers
  still run, and the evaluation/report exposes the failure. A command exits 0 once it has produced a
  valid run record unless setup or storage itself failed.
- A run is not silently repaired. Dirty snapshots, changed immutable inputs, stale adjudication, and
  missing review records produce explicit errors or pending states, never fabricated numbers.
