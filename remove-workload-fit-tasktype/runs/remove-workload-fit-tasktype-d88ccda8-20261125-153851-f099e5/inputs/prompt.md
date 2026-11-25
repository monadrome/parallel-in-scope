You are one independent reviewer in the llm-review-bench benchmark.

Review the change from 6fb1ad345fb161bf3921aab71430b57575e7baa9 to d88ccda8c64d56519101dda826a78c277bfd043f. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-workload-fit-tasktype/.review-benchmark/runs/remove-workload-fit-tasktype-d88ccda8-20261125-153851-f099e5/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-workload-fit-tasktype/.review-benchmark/runs/remove-workload-fit-tasktype-d88ccda8-20261125-153851-f099e5/inputs/context.md. Do not modify the snapshot. Do not read
benchmark gold labels, adjudication records, or other reviewers' output.

Return exactly one JSON object and nothing else (no Markdown fences, no prose):

{
  "summary": "one short paragraph describing the review",
  "findings": [
    {
      "file": "repository-relative/path.ext",
      "lineStart": 123,
      "lineEnd": 130,
      "title": "short defect title",
      "trigger": "concrete input, interleaving, or condition that reaches the defect",
      "consequence": "observable failure or contract violation",
      "evidence": "code path with file:line references",
      "severity": "P0|P1|P2|P3",
      "confidence": 0.0,
      "suggestedFix": "actionable fix"
    }
  ]
}

Rules:
- Report only defects introduced or materially exposed by this change.
- Every finding must have a concrete trigger and a user-observable consequence.
- Cite repository-relative files and the line range in the head snapshot.
- confidence is a number from 0 to 1.
- At most 20 findings. Return {"summary":"...","findings":[]} when you find
  nothing; do not return an empty response or prose.
- Do not include shell commands, patches, or hidden reasoning outside the JSON object.

## Shared context (verbatim, immutable)

# Remove TaskType and workload-fit screening: delivery ledger review

Review `6fb1ad34..d88ccda8` read-only. The implementer is Claude (Opus). Do not edit files, run
Maven, or commit. You may run read-only commands such as `git`, `grep`, `sed -n`, `ls`, `python3
-c` for JSON reading, and `python3 scripts/process-metrics.py report`.

`d88ccda8 chore: record delivery remove-workload-fit-tasktype` adds one file,
`ledger/2026-11-25-remove-workload-fit-tasktype.json`, written by
`python3 scripts/process-metrics.py record`. The rules are in `verification/delivery-ledger.md`.

## What to check

1. The commit adds only the record, alone, with the subject `chore: record delivery <topic>`, and
   it is the last commit after the change's other commits (`origin/dev/v0.5.0..d88ccda8`).
2. `change.commits` lists every subject of `f093bb12..6fb1ad34`, in order.
3. `reviews.adversarial` names each llm-review-bench run of this topic once, in round order,
   including the partial round-1 run whose kiro seat failed. The runs are under
   `.review-benchmark/runs/remove-workload-fit-tasktype-*`. Check the counts the record copies
   against each run's files.
4. `reviews.refactoring` names `.agents/reviews/refactoring-remove-workload-fit-tasktype-2026-11-24`.
   `pit` reads the head reports `verification/pit-audit/2026-11-24-remove-tasktype/reports/head-A.xml`
   to `head-E.xml`, and its raw counts and inconclusive counts match those files.
5. `routes`, `implementer`, `interventions`, `gates.failures`, and `notes` follow the field table.
   The self-reported values are lower bounds, and you cannot verify them from files. Report only a
   value that contradicts evidence in the repository or in the assurance run at
   `.contract-first-assurance/2026-11-24-remove-tasktype-workload-fit/`, whose `logs/commands.jsonl`
   records the assurance steps. Do not read `verification/contract-tests/` or the run's
   `candidates/`, `promotions/`, `promotion-targets/`, or `adaptation/`. This review does not need
   them.

## Output

In `summary`, give one line per item: `PASS` or `FAIL`. Report every defect as a finding tied to
a file and a line, with a concrete consequence for a later reader of the ledger.
