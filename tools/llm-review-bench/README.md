# llm-review-bench

A standalone TypeScript CLI for measuring **which code-review system actually finds real defects** in a
repository. It runs independent reviewer systems (Codex, Claude, Kimi, OCR, Kiro, or a local fixture)
against an immutable git snapshot, records every raw response and log, and turns explicit human or
agent judgment into reproducible rates and recall numbers.

Node.js 22 or newer is required. The package uses only the Node standard library at runtime and
`typescript` plus `@types/node` for development. It is ESM, compiled with `tsc`; there is no Python
component and no runtime dependency.

## User story

A maintainer of a concurrency library wants to know whether a new review agent is better than the
current one, and whether adding a model or raising effort improves defect discovery. A single review
run is not enough: several reviewers must see the same code, their findings must be judged against a
shared definition of a real defect, and a later fix commit must be usable as ground truth. The
benchmark must preserve the raw evidence so a score can be re-derived, audited, and contested without
asking any model to run again.

The tool supports two workflows:

1. **Explicit fix benchmark.** A fix commit exists. `run --fix <sha>` reviews `buggy^..buggy`, where
   `buggy` is the fix commit's parent. The fix diff produces **candidate** gold labels, never confirmed
   truth. A maintainer imports confirmed labels and verdicts as versioned JSON, then gets recall.
2. **Passive multi-review trace.** Run `review` on each nontrivial commit during normal development.
   Raw findings are stored with hashes. When a later fix appears, `link --run <id> --fix <sha>`
   retroactively turns the stored review into a gold-assisted case **without rerunning any reviewer**.
   This is the low-cost way to accumulate a benchmark from work already being done.

## Project entry point: `/multi-review`

In this repository, the project-level `/multi-review` skill is the default daily entry point. It builds
this CLI when `dist/` is missing, runs `review --working` or `review --commit`, reads the raw findings,
and imports explicit clusters/verdicts only after the reviewing agent verifies them against the code. It
does not invoke the legacy Python `multi_review.py` flow and does not duplicate the evaluation rules.

- Skill: [`.claude/skills/multi-review/SKILL.md`](../../.claude/skills/multi-review/SKILL.md)
- Live contract: [`design/llm-review-benchmark.md`](../../design/llm-review-benchmark.md)

## What it measures

- Per reviewer, and for the deduplicated union: confirmed / rejected / neutral / uncertain rates over
  adjudicated findings, with `null` when the denominator is zero.
- Micro rates pooled over reviewer findings and macro rates averaged over reviewers.
- `known_fix_recall` and `regression_recall` over **confirmed** gold labels only. Open-world cases and
  candidate gold report `null`, never a fake zero.
- Duration, attempts, timeout status, and failure status per reviewer.
- Reported token usage and cost, only when an adapter supplies reliable values; unknown values remain null.

It does not compute a subjective quality score. One case does not rank models. Agent CLI, model,
effort, prompt, repository, and commit all form the measured system.

## Build and test

```bash
cd tools/llm-review-bench
npm install
npm run build       # tsc -> dist/
npm run typecheck   # tsc --noEmit over src and test
npm test            # builds, compiles tests, runs node:test
```

Run the CLI directly:

```bash
node dist/cli.js --help
```

The test suite uses temporary git repositories and local fixture adapters only; it never invokes a real
LLM.

## Quick start: explicit fix benchmark

```bash
# 1. Describe the reviewer systems. provider/model/effort are recorded in SystemIdentity.
cat > systems.json <<'JSON'
{
  "systems": [
    { "agent": "codex",  "provider": "openai",    "model": "gpt-5-codex", "effort": "high" },
    { "agent": "claude", "provider": "anthropic", "model": "claude-sonnet-4-5", "effort": "high" },
    { "agent": "kimi",   "provider": "moonshot",  "model": "kimi-k2", "effort": "native default" },
    { "agent": "ocr",    "provider": "ada",       "model": "claude-sonnet-5", "effort": "high" },
    { "agent": "kiro",   "provider": "aws",       "model": "claude-opus-5.5", "effort": "native default" }
  ],
  "budget": 20,
  "concurrency": 3
}
JSON

# 2. Review buggy^..buggy. The fix commit is only a candidate gold source.
node dist/cli.js run --fix <fix-sha> --repo . --systems systems.json --json

# 3. Inspect the run directory printed in the JSON summary. The report is static HTML.
#    Gold is at <root>/gold/<run-id>/v1.json and remains candidate status.

# 4. Adjudicate explicitly: clusters partition every finding, verdicts assign one of four states,
#    and gold confirms which labels were introduced by the buggy commit.
node dist/cli.js apply-judgment <run-id> \
  --clusters clusters.json --verdicts verdicts.json --gold gold.json

# 5. Recompute only from stored raw and derived records; no model is called.
node dist/cli.js rescore <run-id>

# 6. Re-render the latest evaluation if needed.
node dist/cli.js report <run-id>

# 7. Remove the worktree and temporary reviewer output; all evidence remains.
node dist/cli.js cleanup <run-id>

# 8. Optionally run every reviewer again as a new run with the same immutable case.
node dist/cli.js rerun <run-id>
```

The default benchmark root for a new run is `<repo>/.review-benchmark`. Use `--output DIR` to put it
elsewhere. Commands that operate on an existing run default to `./.review-benchmark`; run them from the
repository root or pass `--output`.

## Quick start: passive trace from daily multi-review

```bash
# During normal review work, store the raw evidence without gold.
node dist/cli.js review --commit HEAD --systems systems.json --json

# Later, after a follow-up fix exists:
node dist/cli.js link --run <run-id> --fix <fix-sha> --json

# Then import explicit clusters/verdicts/gold when the relationship is known.
node dist/cli.js apply-judgment <run-id> \
  --clusters clusters.json --verdicts verdicts.json --gold gold.json
```

`link` and `rescore` never start a reviewer process. Tests delete the fixture file before these commands
to prove it.

### Reviewing uncommitted working-tree changes

```bash
node dist/cli.js review --working --repo . --systems systems.json --json
```

`--working` captures the final working-tree state (staged, unstaged, and untracked files) through a
temporary index, so the real index and worktree are not modified. It creates a snapshot commit and a
durable `refs/llm-review-bench/<run-id>` ref, records `source: "working"`, `base = HEAD`, and the
snapshot commit as `head`; `base` defaults to HEAD. It cannot be combined with `--commit`; `--base <rev>` is allowed and reviews
`<rev>..<snapshot>` while the snapshot parent remains HEAD. `resume` and `rerun` reproduce the snapshot
from the recorded ref, including after `cleanup`.

## Commands

| Command | Purpose |
|---|---|
| `discover` | Read-only scan of recent commits and existing runs. Suggests fix-like or source+test candidates. |
| `run --fix <sha>` | Historical case: `buggy = fix^`, reviews `buggy^..buggy`, writes a candidate gold. |
| `review [--commit SHA] [--base SHA]` | Live open-world case. No gold, no recall. |
| `review --working [--base SHA]` | Record staged/unstaged/untracked working changes without touching the index; live/open-world. |
| `resume <run-id>` | Retry only non-completed reviewers in the same run; re-derive/evaluate when evidence changed. |
| `rerun <run-id>` | New run_id reusing the old immutable context/prompt/diff/config; fresh reviewers, fresh candidate/open-world gold, no old verdicts. |
| `link --run <id> --fix <sha>` | Create a new gold/evaluation version from stored reviews without invoking adapters. |
| `rescore <run-id>` | Recompute dedup/evaluation from raw and derived records only. No adapters. |
| `report <run-id>` | Render versioned HTML, CSV, and JSON from the latest evaluation. |
| `apply-judgment <run-id>` | Import versioned clusters, verdicts, and explicit gold, then optionally rescore. |
| `leaderboard [--include-fixture]` | Aggregate latest evaluations per run by agent+model+revision+effort. |
| `cleanup <run-id> \| --all` | Remove worktrees and temporary agent output; keep inputs, reviews, logs, derived, reports. |

Use `--json` for a single machine-readable object on stdout. Progress and errors go to stderr. Usage
errors exit `2`; runtime failures exit `1`. `--debug` prints stack traces.

## Configuration

Every reviewer is a `SystemConfig`. `provider`, `model`, `revision`, and `effort` are provenance
fields; `agent` selects an adapter. `extraArgs` are passed to the CLI as separate argv elements and
must not contain bypass flags.

```json
{
  "systems": [
    {
      "agent": "codex",
      "provider": "openai",
      "model": "gpt-5-codex",
      "effort": "high",
      "revision": "internal-build-42",
      "command": "/opt/codex/bin/codex",
      "extraArgs": ["-c", "model_reasoning_summary=concise"]
    },
    {
      "agent": "fixture",
      "fixture": "./test/fixtures/review.json",
      "model": "fixture-model"
    }
  ],
  "budget": 20,
  "concurrency": 3,
  "timeouts": {
    "totalSeconds": 3060,
    "reviewSeconds": 2700,
    "adjudicationSeconds": 300,
    "cleanupSeconds": 60,
    "killGraceSeconds": 10
  },
  "maxOutputBytes": 8388608
}
```

CLI precedence: explicit flags override the systems file, which overrides defaults. `--model` and
`--effort` override every selected system. `--agents` selects a comma-separated subset. Without
`--agents` or `--systems`, the tool auto-selects real CLIs found on `PATH`; it never auto-selects the
fixture adapter.

Defaults: **51 minutes total**, **45 minutes reviewer phase**, **5 minutes adjudication reserve**,
**1 minute cleanup**, **10 second SIGTERM grace**, budget 20 findings, concurrency 3. The review phase
gets `min(reviewSeconds, totalSeconds - adjudicationSeconds - cleanupSeconds)`. The overall review
clock starts when the review phase starts; `resume` starts a fresh review phase for incomplete
reviewers. Adjudication is an explicit import command, so the 5-minute reserve is a budget guard, not
an automatic model call.

## Directory protocol

```
<root>/
  .gitignore                        # "*" so benchmark data stays out of the repo
  gold/<run-id>/vN.json             # gold is outside the run dir and outside the worktree
  runs/<run-id>/
    run.json                        # immutable RunManifest (source, rerunOf, working, config, commits, hashes)
    state.json                      # atomically replaced run/step state machine
    events.jsonl                    # append-only events, monotonically sequenced
    inputs/
      context.md                    # immutable reviewer context (generated or supplied)
      context.json                  # immutable structured case facts for audit and tooling
      diff.patch                    # immutable unified diff for the reviewed range
      manifest.json                 # immutable CaseManifest
      prompt.md                     # immutable reviewer prompt, including budget
    snapshot/                       # git worktree at head; read-only by policy + adapter sandbox flags
    reviews/<agent>/attempt-N.json  # immutable raw review record; findings: null on failure/timeout
    logs/<agent>-attempt-N.stdout.log
    logs/<agent>-attempt-N.stderr.log
    derived/dedup/vN.json           # versioned clusters; never overwritten
    derived/adjudication/vN.json    # versioned verdicts; never overwritten
    derived/evaluation/vN.json      # versioned metrics; never overwritten
    reports/report-vN.html          # versioned static report
    reports/metrics-vN.csv
    reports/metrics-vN.json
    agent-output/<agent>/attempt-N/ # writable adapter output directory
```

Working runs also create a namespaced ref in the repository's git dir,
`refs/llm-review-bench/<run-id>`; it is not inside `<root>`. It keeps the snapshot reachable for resume,
rerun, and git GC after cleanup.

Raw review records, input files, prompts, logs, and derived versions are write-once. State and
`events.jsonl` are the only mutable run files; state is replaced atomically and events are appended.
All JSON reads reject duplicate keys and trailing commas.

Gold is never placed in a review worktree, prompt, agent output directory, or run directory. A
reviewer cannot be handed gold by this tool; it is stored under the benchmark root's `gold/` tree and
read only by evaluation/report commands. Working snapshots add a namespaced repository ref so the
snapshot survives cleanup and git garbage collection; cleanup does not delete that ref.

`rerun` never copies old reviews, dedup, adjudication, or confirmed gold. It creates a new run id,
records `rerunOf`, reuses byte-identical `context.md`, `prompt.md`, and `diff.patch` plus the exact
system config, and runs every selected reviewer again. A historical fix rerun gets a fresh candidate
gold; a live or working rerun stays open-world. Old data is left untouched.

## Evaluation semantics

Findings are normalized deterministically before clustering: repository-relative POSIX paths, ordered
line ranges, `P0`-`P3` severity, `0..1` confidence, and stable IDs. The raw response is preserved
separately, so normalization is never destructive.

**Deduplication is explicit.** With no clusters input, each finding is its own cluster and the
`dedup` record says `method: "identity"`. The tool does not use text hashing as a semantic dedup claim.
A `clusters` import must partition every finding from completed reviews exactly once; duplicate or
missing finding IDs are validation errors. Semantic clusters are agent/human-in-the-loop input.

**Verdicts are explicit.** A cluster without an imported verdict is `pending`. Pending clusters are
excluded from rate denominators and reported separately. A verdict targets the dedup version that was
latest at import time; if a later dedup version exists, the old verdict is treated as pending with a
note instead of being silently applied to different findings.

**Gold is explicit.** `run --fix` extracts candidate labels from the fix diff. Candidate labels have
`introducedByBuggyCommit: null` and recall is withheld. `apply-judgment --gold` confirms relationships
and requires a boolean `introducedByBuggyCommit` for every label. A label counts for recall only when
gold status is `confirmed` and `introducedByBuggyCommit === true`.

Usage is optional per review: `inputTokens`, `outputTokens`, `totalTokens`, `cost`, and `currency`
are recorded when the adapter can obtain them reliably (OCR's manifest summary and Codex's explicit
"tokens used" footer; otherwise null). Per-case and leaderboard totals sum only known token values,
and costs are grouped by currency; different currencies are never added.

Rates use an adjudicated denominator; zero denominator yields `null` in JSON and `n/a` in reports.
`known_fix_recall` and `regression_recall` use confirmed labels of the corresponding `kind`
(`known-fix` or `regression`). Recall is also `null` until at least one cluster has a verdict, and
reviewers that did not complete have `null` recall. The union reports
cluster-level rates (`union.rates`), finding-level rates (`union.findingRates`), and recall over all
completed reviewers; micro pools findings and macro averages reviewer rates. See [docs/protocol.md](docs/protocol.md) and [docs/clusters.md](docs/clusters.md).

## Reports

`report` (and `run`/`review`/`resume`/`rerun`/`link`/`apply-judgment`) write three versioned artifacts:

- **HTML**: a self-contained static page with a case layer and a system layer. Every user- or
  agent-controlled string is HTML-escaped. A restrictive CSP disables scripts; there is no JavaScript.
- **CSV**: RFC 4180-style quoting and a leading apostrophe for cells that could be interpreted as a
  spreadsheet formula (`=`, `+`, `-`, `@`, including after leading whitespace).
- **JSON**: the complete `EvaluationRecord`, including input versions and notes.

Open-world and candidate-gold cases show `n/a` with a reason instead of recall numbers. Pending
clusters are shown as pending, not rejected. System rows also show tokens and cost when reported.

## Leaderboard

`leaderboard` scans the latest evaluation version of each local run and aggregates rows by
`agent + model + revision + effort`. It reports case/completed/timeout/failure counts, micro rates
(pooled findings), macro rates (mean of per-case rates), known-fix/regression recall weighted over
applicable confirmed-gold denominators, timeout rate, average completed duration, tokens, and
per-currency costs. Fixture systems are excluded unless `--include-fixture` is passed.

Rows are measured systems, not pure model rankings: the same model under different agents, revisions,
or efforts is a different row, and one case is not evidence of model quality. Reruns count as separate
cases; the JSON has a `rerunCases` field if you need to filter them.

```bash
node dist/cli.js leaderboard --output .review-benchmark --include-fixture
```

## Timeouts and failures

Each reviewer runs as a detached process group. stdout and stderr are streamed directly to per-attempt
log files. On the hard timeout the supervisor sends `SIGTERM` to the group, waits the grace period,
then sends `SIGKILL`. A timed-out or failed reviewer is recorded with `findings: null`; evaluation
counts it as not completed and never as an empty review. A reviewer that exits 0 but returns malformed
JSON is a failed step, not an empty result.

After every attempt the tool checks the worktree with `git status`. A reviewer that modifies the
snapshot fails that step; cleanup recreates the snapshot. Adapters also use read-only sandbox flags
where available (Codex `--sandbox read-only`; Claude with no bypass; Kimi without `--auto`).

## Limitations

- Semantic deduplication and adjudication are explicit inputs. There is no automatic semantic judge,
  no text-hash dedup claim, and no "quality score".
- A snapshot is read-only by policy and adapter flags, not by OS file permissions. A reviewer with its
  own escape hatches can still write; the dirty check detects it and fails the step.
- Reviewer isolation is not an OS security boundary. Gold is stored outside the run directory and is
  never placed in the worktree, prompt, or agent-output directory, and the historical context withholds
  the fix commit. A reviewer that deliberately traverses the benchmark root or the shared git object
  database can still discover more; adapters are trusted benchmark participants, not adversaries.
- Generated and supplied contexts are capped at 200,000 bytes so the prompt remains a safe single argv
  element. Use a curated `--context` for unusually large cases.
- One runner process per run. State updates are serialized in-process; there is no cross-process lock.
  Concurrent commands against the same run are unsupported.
- Adapter output parsing is best effort for real CLIs whose formats are not stable public contracts.
  Raw logs are kept so parsing can be revisited.
- OCR reviews the commit's own parent range; a live review with a custom `--base` is approximate for
  the OCR adapter. Documented in [docs/adapters.md](docs/adapters.md).
- Kiro's non-interactive mode requires `--trust-all-tools`; that flag is confined to the adapter and
  documented as a risk. Extra bypass flags are rejected from user `extraArgs`.
- Model and effort flags are not uniformly supported by every CLI. Effort is always recorded as
  provenance; Codex and OCR receive adapter flags, others rely on their own defaults or `extraArgs`.
- Hard-timeout process-group termination is POSIX behavior. macOS and Linux are the supported
  environments; Windows process-group semantics are not validated.
- One case is evidence about one measured system, not a model ranking. Re-runs are nondeterministic.
- No database, web service, dashboard, trend analysis, or automatic issue creation.
- Usage/cost is only as reliable as the adapter output. Missing or partial values stay null; cost is
  never estimated, and currencies are never combined. A reported cost without a currency is grouped
  separately.
- Working capture uses a temporary index and creates a namespaced git ref. It does not change the real
  index or worktree, but the ref persists after cleanup by design so rerun remains reproducible; there
  is no automatic ref-pruning command.

## Development layout

```
src/
  cli.ts              command parser and dispatch
  case/               git worktree, context, prompt, case preparation
  adapters/           codex, claude, kimi, ocr, kiro, fixture, process supervisor
  runner/             review phase, concurrency, resume, timeouts
  protocol/           types, strict JSON validation, state machine, run store
  evaluation/         dedup, adjudication, gold, metrics, HTML/CSV/JSON
  commands/           discover, run, resume, rerun, link, rescore, report, judgment, leaderboard, cleanup
test/                 node:test suites with temporary git repositories and fixtures
docs/                 public contracts: protocol, cluster rules, adapters
```

## License

This package is part of the repository and is covered by the repository license at
`../../LICENSE`. No separate license file is added here.
