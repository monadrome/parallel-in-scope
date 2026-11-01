# Delivery ledger

Each delivery to the development line leaves one JSON record under `ledger/`.
The record keeps the process numbers of that change: review rounds, rule
findings and their dispositions, local gate failures, maintainer
interventions, PIT inconclusive rate, and implementer usage. The ledger exists
for two later questions: what the harness costs per change against what its
reviews catch, and which written rule is violated most often, which is the
first candidate for a mechanical check.

The raw evidence stays where the tools write it: llm-review-bench runs under
`.review-benchmark/runs/` or `.agents/reviews/`, refactoring-review runs under
`.agents/reviews/`, PIT reports under `target/`. All of it is git-ignored, and
much of it is deleted with its worktree, so the record copies the counts at
delivery time.

`scripts/process-metrics.py` writes and reads the records. Its tests are
`scripts/tests/test_process_metrics.py`.

## When to record

Record every delivery except the mechanical route of the Task Routes table in
the root `AGENTS.md`. Issue-runner sessions record too; the record is one more
local commit that the runner pushes with the rest.

After the change's last commit and before the rebase and push, run `record`
and commit the file alone as `chore: record delivery <topic>`. A separate
commit keeps the record's commit list complete: the record lists the subjects
of `--base..HEAD`, which a rebase does not change. Rerunning `record` after a
rebase is unnecessary unless the change gained commits.

```bash
python3 scripts/process-metrics.py record --topic <topic> --route <route> \
  --agent <harness> --model <model> \
  --adversarial <bench run dir or id> \
  --refactoring <refactoring-review run dir> \
  --pit target/pit-reports/mutations.xml \
  --gate-failure 'mvn test=2' --asks 1 --corrections 0
```

`--base` defaults to `@{upstream}`, which is the development line for a
worktree branch created from `origin/dev/vX.Y.Z`; pass it explicitly
otherwise. Name each adversarial review run once, in round order, including
failed or timed-out runs; the report counts completed runs as rounds and the
rest separately. Omit a flag whose evidence does not exist for the change.

## Fields

| Field | Source | Meaning |
|---|---|---|
| `routes` | self-reported | Task Routes rows that applied: `mechanical`, `defect`, `contract`, `concurrency`, `docs`, `dependency`, `release`, or `other` |
| `change` | git | Commit subjects of `--base..HEAD` and the diff's file, insertion, and deletion counts |
| `reviews.adversarial` | llm-review-bench run | Run status; per reviewer system: status, attempts, total duration, finding verdict counts, and usage from the newest evaluation; deduplicated cluster verdict counts |
| `reviews.refactoring` | refactoring-review run | Each merged finding's rules, tier, scope, `mechanizable` flag, and disposition from the report's Dispositions table (`applied`, `rejected`, `deferred`, `reverted`, or `unknown` when the report is missing or the cell does not start with one of these) |
| `gates.failures` | self-reported | Per required local check (Commands, Verification And Completion): runs that failed on this change before it passed. Count every failed run, including ones fixed within a minute |
| `pit` | `mutations.xml` | Raw status counts and the inconclusive count: every status other than `KILLED`, `SURVIVED`, `NO_COVERAGE`, and `NON_VIABLE`, per "Interpret every raw status" in [PIT verification](pit.md) |
| `interventions.asks` | self-reported | Times the agent stopped for a maintainer decision under Autonomous Completion |
| `interventions.corrections` | self-reported | Times the user corrected the agent's behavior or output |
| `usage` | harness | Implementer total tokens and cost with its currency, only when the harness reports them |
| `notes` | self-reported | One line of context a later reader needs |

Unknown values stay `null`. The script never estimates tokens or cost and
never adds costs in different currencies, matching llm-review-bench.

## Report

```bash
python3 scripts/process-metrics.py report                      # records only
python3 scripts/process-metrics.py report --scan .agents/reviews --scan .review-benchmark/runs
python3 scripts/process-metrics.py report --ci 100 --json      # adds GitHub Actions failures via gh
```

The report has one row per change, a ranking of refactoring-review rules by
finding count with disposition and `mechanizable` counts, self-reported local
gate failures by gate, and adversarial review yield: reviewer statuses,
cluster verdicts, and reviewer usage. `--scan` adds run directories that no
record names, so evidence from before the ledger, or from work never
delivered, still enters the rule ranking; a run is matched to a record by
bench run id or by the refactoring plan's head commit. `--ci N` reads the last
N GitHub Actions runs and counts failed steps by workflow and job.

## Limitations

- Gate failures and interventions are self-reported by the delivering agent.
  They are lower bounds when an agent forgets a count.
- Reviewer quality across systems belongs to the llm-review-bench
  `leaderboard`; the ledger only carries each run's counts so they survive the
  run directory.
- Rule findings come only from refactoring-review. Adversarial review findings
  carry no rule identifier, so they count toward review yield, not the ranking.
- A high finding count alone does not select the next mechanical check. Weigh
  it against the cost of a miss and whether the rule is decidable from source;
  the `mechanizable` count records the detector's judgment of the latter.
- The ledger measures cost and caught findings. Defects that escaped review
  appear only when a later fix is linked to the reviewed change through
  llm-review-bench `link`.
