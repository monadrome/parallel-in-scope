# LLM Review Benchmark Contract

This document is the live contract for `tools/llm-review-bench` and the in-project
`/multi-review`: it constrains who may produce review evidence, how evidence is stored, how
judgments take effect, and which numbers may be reported. Command arguments, JSON fields, and
parsing details are governed by the tool's [README](../tools/llm-review-bench/README.md),
[protocol.md](../tools/llm-review-bench/docs/protocol.md), and
[clusters.md](../tools/llm-review-bench/docs/clusters.md); this document fixes only decisions
and invariants, and does not copy drift-prone file inventories, line numbers, or test counts.

## 1. Background

Multi-agent review is already this repository's daily process, but "which reviewer, which
model, which effort actually finds defects" has long had no reproducible evidence. The old
process merged four reviewers' output into one session's findings and triage; once the fix was
done, the evidence vanished with the session. Runs could not be compared, a reviewer's hit
rate could not be recomputed, and "which previous finding this fix corresponds to" could not be
answered. Treating reviewer count, severity, or one demonstration as a quality conclusion
mistakes "looks reviewed" for "defects were found".

`tools/llm-review-bench` splits review into three layers: immutable raw evidence, explicit
human/agent judgment, and recomputable metrics. It does not judge semantics automatically, and
it does not package a single case as a model ranking.

## 2. User story

A concurrency library's maintainer wants to know whether a new review agent is more reliable
than the current combination. The maintainer needs to:

1. have multiple reviewers look at the same immutable git snapshot, not each at a drifting
   working tree;
2. keep every reviewer's raw responses, logs, elapsed time, timeout and failure states,
   auditable afterwards;
3. use a unified finding protocol and explicit clustering rules, separating "same root cause"
   from "different defects on the same line";
4. use a four-state verdict to distinguish true defects, false positives, valid-but-actionable-
   not, and insufficient evidence;
5. when an independent fix appears later, turn a stored open-world review into a gold-assisted
   case without rerunning models;
6. at any time, recompute metrics from the raw and derived records on disk alone, independent
   of any session's memory.

## 3. Before and after

### 3.1 Best user operation before

The old process was a user-level multi-agent script outside the repository — full-featured but
serving a single session:

```bash
# The Python script bundled with the user-level multi-review skill, not in this repository
S=/path/to/multi-review-skill/scripts/multi_review.py
python3 $S prepare --repo . --working
python3 $S review <run> --detach
python3 $S wait <run> --max 1500
python3 $S merge <run>          # findings.md, clustered by file+line
# the main agent triages; contested items go to a kiro/codex/claude three-way vote
```

Its strengths were fast startup, mature context preparation, and a closed fix loop; its
weaknesses were non-recomputable evidence, no gold/recall, no unified protocol across runs,
clustering approximated by file+line, and vote results unusable as auditable measurement truth.

### 3.2 The same scenario after

Run from the repository root; build first when `dist/` is missing:

```bash
if [ ! -f tools/llm-review-bench/dist/cli.js ]; then
  npm --prefix tools/llm-review-bench ci && npm --prefix tools/llm-review-bench run build
fi

# Daily: review uncommitted changes (open-world, no gold)
node tools/llm-review-bench/dist/cli.js review --working --json

# Read the runId and <runDir>/reviews/<agent>/attempt-N.json, verify personally, then write clusters/verdicts
node tools/llm-review-bench/dist/cli.js apply-judgment <run-id> \
  --clusters clusters.json --verdicts verdicts.json \
  --judge-kind agent --judge-name <name> --judge-model <model> --json

# Reports are rendered by a fixed-version evaluation
node tools/llm-review-bench/dist/cli.js report <run-id> --json
```

An explicit fix benchmark:

```bash
node tools/llm-review-bench/dist/cli.js run --fix <fix-sha> --json
# fix produces only candidate gold; import confirmed gold after confirmation
node tools/llm-review-bench/dist/cli.js apply-judgment <run-id> \
  --clusters clusters.json --verdicts verdicts.json --gold gold.json \
  --confirmed-by <name> \
  --judge-kind agent --judge-name <name> --judge-model <model> --json
```

When an independent fix appears later, reviewers are not rerun:

```bash
node tools/llm-review-bench/dist/cli.js link --run <run-id> --fix <fix-sha> --json
node tools/llm-review-bench/dist/cli.js rescore <run-id> --json
node tools/llm-review-bench/dist/cli.js report <run-id> --json
```

The in-project entry is the thin skill
[`.agents/skills/multi-review/SKILL.md`](../.agents/skills/multi-review/SKILL.md): it only
builds, selects cases, reads raw findings, produces judgment JSON per the rules, and invokes
the CLI — it implements no evaluation algorithm and no longer calls the old Python process.

### 3.3 Failure modes removed

- **Evidence vanishing with the session**: run directories keep raw responses, logs, and input
  hashes — auditable and recomputable across sessions.
- **timeout/failed treated as an empty review**: failures record `findings: null`, are excluded
  from denominators, and are reported separately — never converted to 0.
- **File+line or text similarity masquerading as semantic dedup**: the tool provides only the
  identity baseline; real clustering must be an explicit partition.
- **A "score" without gold**: open-world/candidate recall is `null`; reports show `n/a`.
- **Decision votes treated as measurement truth**: votes may decide whether to fix, but they
  produce no recomputable gold; the benchmark accepts only explicit judgments.
- **Self-fix backfilling gold**: a same-run fix is remediation and cannot retroactively prove
  the run found the defect.
- **One case stated as a model ranking**: provenance and case counts are recorded, and
  single-case conclusions are explicitly limited.
- **Unauditable "optimization"**: derived artifacts append as `vN`; old evaluations remain
  replayable.

## 4. Architecture

### 4.1 A single TypeScript orchestrator

`tools/llm-review-bench` is a standalone CLI running on Node.js 22+, ESM, using only the Node
standard library at runtime, with `typescript` and `@types/node` as its only build
dependencies; sources compile to `dist/`, with `dist/cli.js` as the entry. It is not a Maven
artifact and does not change the library's runtime behavior.

The orchestrator handles case preparation, reviewer process supervision, protocol validation,
storage, evaluation, and reporting. In-project, benchmark evidence MUST be produced only
through this CLI; its orchestration logic MUST NOT be copied into skills, hooks, or ad-hoc
scripts.

### 4.2 Adapters

Each reviewer is an adapter: it only builds the argv plan, runs under shared process
supervision, and parses the final text into unified findings. Production adapters cover Codex,
Claude, Kimi, OCR, and Kiro; fixture adapters are for tests and local end-to-end only and are
never auto-selected.

The shared supervision layer provides independent process groups, per-attempt stdout/stderr
logs, hard timeouts (`SIGTERM` → grace → `SIGKILL`), and before/after `git status` dirty
checks. Adapters' read-only flags and the bypass-rejection rules for user `extraArgs` are in
[adapters.md](../tools/llm-review-bench/docs/adapters.md).

### 4.3 Unified protocol and storage

A finding is deterministically normalized before entering evaluation: repository-relative POSIX
paths, ordered line ranges, `P0`–`P3`, `0..1` confidence, stable IDs; raw responses are kept
separately — normalization does not destroy the original text. All JSON reads reject duplicate
keys, trailing commas, and comments; run ids, agent names, cluster ids, and gold ids must be
single safe path segments, and file paths must be repository-relative POSIX paths with escape
checks.

Storage is the filesystem: run directories hold immutable inputs and raw records, the benchmark
root's `gold/` tree holds gold, and derived records append by version. No database, service, or
background process.

### 4.4 Evaluation and reporting

The evaluation layer reads only raw reviews, derived dedup/adjudication, and gold, computing
per-reviewer, union, micro, macro rates plus known-fix/regression recall, and writes versioned
HTML, CSV, and JSON. Reports are static files with no scripts; all user- or model-controlled
strings are HTML-escaped, and CSV is sanitized against formula prefixes.

## 5. Case types and relationships

| Operation | Input | Calls reviewers | gold | Relationship |
|---|---|---|---|---|
| `review` | One commit (default `HEAD`), `base` default `head^` | yes | open-world | live, no gold, no recall |
| `review --working` | Staged, unstaged, untracked changes | yes | open-world | snapshot commit + namespaced ref; the real index/worktree untouched |
| `run --fix` | A fix commit | yes | candidate | `buggy = fix^`, reviewing `buggy^..buggy`; the fix diff only generates candidate labels |
| `resume` | An existing run | only unfinished reviewers | reused/re-derived | same run; completed records not replaced |
| `rerun` | An existing run | all rerun | new candidate/open-world | new run id, reusing immutable inputs; old reviews/verdicts/gold not inherited |
| `link` | An existing run + a later fix | no | new gold version | the fix must descend from the run head; no model calls |
| `rescore` | An existing run | no | reused | recomputes from raw and derived records only |
| `report` | An existing run | no | n/a | renders the exact versions referenced by the latest evaluation |

`rerun` is not `resume`: the former is a new measurement case, the latter only completes the
same measurement. `link` only adds gold/evaluation versions and produces no new reviewer
results; `rescore` and `report` call no models.

## 6. Raw immutability and derived versioning

- `run.json`, `inputs/` (context, diff, prompt, case manifest, structured context),
  `reviews/<agent>/attempt-N.json`, per-attempt logs, `derived/*/vN.json`,
  `gold/<run-id>/vN.json`, and report files are all write-once; a second write to the same
  path fails.
- `state.json` is the only replaceable run file, atomically replaced with a same-directory
  temporary file + fsync + rename; `events.jsonl` is append-only with monotonically increasing
  sequence numbers.
- Later commands (`resume`, `rerun`, `link`, `rescore`, `report`, `apply-judgment`) verify
  input sha256 before reading; a changed input aborts — old evidence is never "repaired".
- New clusters, judgments, or gold always write a new `vN`; existing versions MUST NOT be
  overwritten, hand-edited, or patched.
- An evaluation records the dedup/adjudication/gold versions and review hashes it references;
  a report loads those exact versions, not "the latest", so old reports are replayable.

## 7. Judgments, gold, and recall

### 7.1 The four-state verdict

Each cluster allows exactly four verdicts:

| verdict | Meaning |
|---|---|
| `confirmed` | A real defect introduced or materially exposed by the reviewed change, or a real test/documentation contract gap |
| `rejected` | Not a defect: false positive, pure style, already fixed, misread of the change |
| `neutral` | A valid observation, but not actionable in this case, or only an optional precision improvement |
| `uncertain` | Insufficient evidence or undecidable; kept visible, but counted in neither confirmed nor rejected |

A cluster with no verdict is `pending`, not `rejected`; pending is excluded from rate
denominators and reported separately. A verdict binds the `dedupVersion` at import time; if a
new dedup version appears afterwards, old verdicts count as pending with a note recorded, and
must be re-imported against the new cluster set.

Clustering is an explicit input: an import must partition every finding of every completed
reviewer exactly once; omissions, duplicates, and unknown IDs are all validation errors. The
tool infers no semantics from titles, line numbers, or text similarity.

### 7.2 Gold isolation and the three states

Gold is stored in the benchmark root's `gold/` tree, outside run directories, and MUST NOT
appear in review worktrees, prompts, or agent output directories. Historical context contains
no fix commit SHA; the fix exists only in audit metadata and gold records.

| State | Source | recall |
|---|---|---|
| `open-world` | live/working review, no fix | `null` |
| `candidate` | generated from the fix diff by `run --fix` or `link` (default) | `null` until explicitly confirmed |
| `confirmed` | explicit JSON, declaring `introducedByBuggyCommit` and `confirmedBy` per label | computed when conditions hold |

`link --gold` imports confirmed gold directly, skipping candidate. A candidate label's
`introducedByBuggyCommit` is `null`, not a truth value; confirmation must give a boolean. A
label marked `false` means the buggy commit did not introduce it — excluded from the
denominator rather than counted as a miss, with the reason recorded in `excludedFixScope`.
Confirmed gold with no labels at all is rejected.

### 7.3 Recall boundaries

- `known_fix_recall` and `regression_recall` count only labels in `confirmed` gold with
  `introducedByBuggyCommit === true` and matching `kind`.
- A confirmed cluster hits only when its adjudication explicitly lists `goldLabelIds`; the tool
  does no title, line-number, or text matching.
- The denominator is the number of eligible confirmed labels of that kind; the numerator is the
  number of labels hit by at least one confirmed cluster.
- With open-world/candidate gold, no adjudicated clusters yet, or reviewers unfinished, recall
  is `null`; reports show `n/a` with the reason, never `0`.
- Any rate with a zero denominator is likewise `null`/`n/a`, not `0`.
- A test gap can be a standalone confirmed cluster and enter rates; it enters regression recall
  only when the gold label itself is of `regression` kind.

## 8. The 51-minute layered budget and failure semantics

The default total budget is 51 minutes (3060 seconds), layered as:

- reviewer phase 45 minutes (2700 seconds);
- adjudication reserve 5 minutes (300 seconds);
- cleanup 1 minute (60 seconds);
- the default grace from `SIGTERM` to `SIGKILL` after a hard timeout is 10 seconds.

The budget available to the reviewer phase is `min(reviewSeconds, totalSeconds -
adjudicationSeconds - cleanupSeconds)`. The review clock starts at the reviewer phase;
`resume` opens a new reviewer phase for unfinished reviewers. Adjudication is an explicit
import, not an automatic model call — the 5 minutes is a budget guardrail, not a fixed wait.

A timeout must terminate by process group and record `timed_out`; a failure records
`findings: null`. Neither is an empty review, and neither enters rate denominators; `resume`
may retry with a new attempt, with old records and logs kept as-is. Budgets MUST NOT be
silently extended, and timeouts MUST NOT be converted into "no problems found".

## 9. Daily multi-review and passive evaluation

In-project `/multi-review` is the daily entry: it defaults to `review --working` or
`review --commit` producing open-world cases, reads raw findings, has the main agent verify
personally and write explicit clusters/verdicts, then invokes `apply-judgment`, `rescore`,
`report`. It keeps normal code review's fixing duties: confirmed P0/obvious defects get minimal
fixes, tests added, project checks run, and commits made as usual.

Every daily review **passively** accumulates one open-world evaluation: per-reviewer rates and
evidence, but no gold and no recall. This is deliberately low-threshold data collection, not a
"quality score".

Independent gold must not come from the same run's self-fix. The reason is not a tool
limitation but measurement semantics: that finding was produced by the same run's reviewers and
adjudicated in the same run, and using the fix this run prompted as gold measures
"self-consistency", not "defect-finding ability"; it also lets the agent write gold after
seeing the answer. A same-run fix appears in the review report only as remediation. Only when a
fix **independent of that run** appears later (a regression someone else fixed, a defect
independently found and fixed later) is `link --run <id> --fix <sha>` used to turn the stored
review into a gold-assisted case, followed by explicitly importing clusters/verdicts/gold.
`link` can only verify the fix descends from the run head — it cannot verify independence; the
independence basis must be written by the judge into the gold's `provenance.note` (the input
JSON's `note`).

## 10. Security boundaries

- **Gold isolation**: gold lives outside run directories and never enters worktrees, prompts,
  or agent-output; historical context hides the fix SHA. A reviewer could still actively
  traverse the benchmark root or the shared git object store, so adapters are trusted benchmark
  participants, not malicious code.
- **Reviewer isolation is a trust boundary, not an OS sandbox**: snapshot read-onlyness is
  guaranteed by policy and adapter flags, and checked by before/after `git status`; a CLI
  carrying its own escape means could still write, and detection fails that attempt.
- **Bypass flags rejected**: `--dangerously*`, `--yolo`, `--allow-all-tools`,
  `--trust-all-tools`, `--auto`, `--full-auto` in user `extraArgs` are rejected; Kiro's
  required `--trust-all-tools` exists only inside the adapter and is recorded as a risk.
- **Local data**: the benchmark root defaults to `.review-benchmark/`, already in the root
  `.gitignore`; raw logs may contain model output or sensitive fragments and must not be
  committed.
- **Tests call no real models**: tests use temporary git repositories and fixture adapters;
  real CLIs are triggered only by explicit runs.
- **Concurrency**: only one runner process per run; state updates serialize in-process, there
  is no cross-process lock, and concurrent commands are unsupported.
- **Working snapshot refs**: `--working` creates a namespaced ref in the repository's git
  directory, and cleanup does not delete it; this is the design price of rerun reproducibility,
  and there is currently no automatic cleanup command.

## 11. MUST / MUST NOT

1. In-project `/multi-review` MUST produce review and benchmark evidence through
   `tools/llm-review-bench/dist/cli.js`; it MUST NOT call the old Python `multi_review.py`,
   three-way vote scripts, or build another orchestration.
2. The main agent MUST verify findings personally before writing verdicts; reviewer severity
   MUST NOT serve directly as a conclusion.
3. A same-run self-fix MUST NOT serve as that run's or its rerun's independent gold; `link`'s
   gold MUST record the independence basis.
4. After a run is created, raw evidence (run manifest, inputs, review records, logs) MUST be
   write-once; later commands MUST verify input hashes.
5. Derived dedup/adjudication/evaluation, gold, and reports MUST append as `vN`; existing
   versions MUST NOT be overwritten or hand-edited.
6. `state.json` MUST be atomically replaced; `events.jsonl` MUST be append-only.
7. Gold MUST be stored outside run directories; it MUST NOT appear in worktrees, prompts, or
   agent-output; historical context MUST hide the fix commit.
8. Clusters MUST partition every finding of every completed reviewer exactly once; imports
   MUST reject omissions, duplicates, and unknown IDs.
9. A verdict MUST be one of `confirmed`/`rejected`/`neutral`/`uncertain`; a missing one MUST
   count as `pending`, MUST NOT be treated as `rejected` or counted into denominators.
10. A verdict MUST bind the dedup version at import time; after a dedup change, old verdicts
    MUST count as pending with a note recorded.
11. Gold MUST be explicit; candidate/open-world MUST NOT enter recall; a confirmed label MUST
    give an explicit `introducedByBuggyCommit` boolean.
12. Recall MUST be computed only from confirmed gold, `introducedByBuggyCommit === true`,
    matching kind, and confirmed clusters' explicit `goldLabelIds`; it MUST NOT be inferred
    from text, titles, or line numbers.
13. Open-world/candidate recall MUST be `null` (reported `n/a`); a `0` or percentage MUST NOT
    be fabricated.
14. A zero-denominator rate MUST be `null`/`n/a`; it MUST NOT be recorded as `0`.
15. A timed-out/failed reviewer MUST record `findings: null` and be excluded from rate
    denominators; it MUST NOT be treated as an empty review.
16. One case MUST NOT be stated as a model ranking; results MUST record
    agent/version/provider/model/revision/effort provenance.
17. The default budget MUST stay the 51-minute layering (45m review + 5m adjudication + 1m
    cleanup); timeouts MUST terminate by process group and record `timed_out`, and MUST NOT be
    silently extended.
18. Dirty snapshots, changed inputs, and stale adjudications MUST explicitly fail or pend; they
    MUST NOT be auto-repaired.
19. Adapters MUST use the available read-only flags and `git status` dirty checks; user
    `extraArgs` MUST have bypass flags rejected.
20. Review isolation MUST be stated as a trust boundary, not an OS sandbox; it MUST NOT claim
    malicious CLIs are isolated.
21. The first phase MUST NOT introduce services, databases, dashboards, trend analysis, or
    automatic issue creation.
22. MUST NOT introduce automatic semantic judges, text-similarity dedup, or a single total
    score.
23. Tests MUST NOT call real models; protocol and process behavior MUST be verified with
    temporary git repositories and fixture adapters.
24. When `dist/cli.js` is absent, the build sequence MUST install the locked build dependencies
    with `npm ci` before compiling; it MUST NOT rely on another checkout's ignored
    `node_modules/` or `dist/`.

## 12. Explicit non-goals

- No hosted service, database, web console, trend analysis, or automatic issue creation.
- No automatic semantic dedup, semantic judgment, or defect fixing; those are explicit inputs
  or the main agent's duties.
- No treating one case as a model ranking; a rerun is itself a non-deterministic repeated
  measurement.
- No Python core and no second parallel orchestration.
- No change to the library's public API, thread model, or build artifacts; this tool is an
  in-repository tool, not a Maven artifact.
- No automatic cross-run gold inference; `link` only relates a later fix to a stored review.

## 13. Rejected alternatives

**Installing review dependencies in worktree skill checks.** The worktree manifest verifies
that required skills are tracked; making it install ignored Node dependencies would add network
and mutation side effects to a presence check used by unrelated tasks. The point-of-use build
sequence owns this bootstrap instead. Reconsideration condition: a general worktree dependency
manager replaces that manifest's narrow provenance role.

**Pure Skill / Hook orchestration.** Skills or hooks alone cannot stably own process groups
with a 51-minute lifecycle, hard timeouts, per-attempt logs, input-hash verification, and
versioned storage; every session would re-implement parsing and the state machine, leaving
results non-recomputable. Hooks are event-level and cannot replace an auditable run store.
Reconsideration condition: the platform provides a durable, auditable run store while keeping
this document's protocol and failure semantics.

**A Python core (keep extending `multi_review.py`).** Two orchestrations would fork the finding
protocol, clustering rules, gold semantics, and metric definitions; the old vote flow is a
decision aid, not a measurement protocol, and keeping it as the core would mix "whether to fix"
with "whether it hit". A single TypeScript CLI gives the protocol, validation, and tests one
source of truth, with zero third-party runtime dependencies. Reconsideration condition: the
project migrates to Python wholesale, retiring this CLI in the same change.

**Text-similarity semantic dedup.** The same line can hold different defects, and the same root
cause can be phrased completely differently; similarity merging changes denominators and can
"improve" scores without evidence. The tool therefore provides only the identity baseline, and
real clustering must be an explicit partition with a judge-written note. No reconsideration
condition; if semantic assistance is introduced in the future, it can only suggest, never
replace explicit partitions.

**A single total score.** A total score hides misses, false positives, pending, and the
difficulty differences between cases, and invites targeted optimization. Four-state rates,
per-reviewer/union counts, recall numerators/denominators, and notes keep explainability.
Reconsideration condition: a calibrated scoring model with published weights is proposed, and
it does not replace the raw metrics.

**A first-phase service or database.** Filesystem storage is diffable, auditable, and
operations-free — sufficient for a single machine, single runner's daily reviews; a
service/database would introduce deployment, permission, concurrency, and migration costs,
with no hard need for cross-machine aggregation today. Reconsideration condition: real needs
for multi-user concurrent writes or cross-machine aggregation appear, and the migration
preserves raw immutability and versioning semantics.

## 14. Adversarial review record

The initial implementation went through independent review. An independent Kimi re-review
confirmed one P1: after a timeout, when the leader received `SIGTERM` and exited, its `close`
handler cleaned up the not-yet-fired grace timer; if the descendant ignored
`SIGTERM`/`SIGHUP`, the process group would never receive the follow-up `SIGKILL`, surviving
past the runner's return. That finding was reproduced with a detached Node parent/child
scenario, with the child's survival as the observable failure signal.

The disposition was confirm and fix: on the timeout path, the leader's `close` no longer
cancels the grace kill; when the grace callback fires, it clears the timer reference first,
then sends `SIGKILL` to the whole process group, then settles the attempt as `timed_out`. A new
regression test covers the leader exiting early with the descendant ignoring both signals.
Reverse verification — temporarily restoring the old implementation — observed the descendant
still alive; the test passed again after re-applying the fix.

The re-review also observed `context.json` retaining the fix SHA. That field is audit metadata;
the protocol only requires historical context and reviewer inputs to hide the fix SHA and
allows audit records to keep it, so the observation was recorded as a rejected finding and the
implementation was unchanged.

The other independent seats produced no pass evidence: Codex did not finish due to an upstream
503/stall, Claude/GLM did not finish due to model unavailability or timeout, the OCR seat timed
out, and this follow-up's read-only re-check seat was terminated after no response within the
wait window. Their states are recorded as blocked and cannot count toward independent-review
passes or quality conclusions.
