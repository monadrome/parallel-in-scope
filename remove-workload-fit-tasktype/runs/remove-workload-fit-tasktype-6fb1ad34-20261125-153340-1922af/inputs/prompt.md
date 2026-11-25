You are one independent reviewer in the llm-review-bench benchmark.

Review the change from 3ce96f08284e3e718311c58669b52c0cf96655e5 to 6fb1ad345fb161bf3921aab71430b57575e7baa9. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-workload-fit-tasktype/.review-benchmark/runs/remove-workload-fit-tasktype-6fb1ad34-20261125-153340-1922af/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-workload-fit-tasktype/.review-benchmark/runs/remove-workload-fit-tasktype-6fb1ad34-20261125-153340-1922af/inputs/context.md. Do not modify the snapshot. Do not read
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

# Remove TaskType and workload-fit screening: review round 7 (withdrawal of C-0005)

Review `3ce96f08..6fb1ad34` read-only. The implementer is Claude (Opus). Do not edit files,
run Maven, or commit. You may run read-only commands such as `git`, `grep`, `sed -n`, `diff`, and
`shasum -a 256`.

## The commit

`6fb1ad34 test: withdraw the unstable C-0005 regression` deletes
`verification/contract-tests/src/test/java/io/github/monadrome/parallelinscope/C0005RegistrationAndCycleWarningsContractTest.java`,
which `ac95c948` had promoted from CONTRACT-TDD-015. Round 6 (llm-review-bench run
`remove-workload-fit-tasktype-3ce96f08-20261125-150850-070176`, findings `kimi:0` and `kimi:1`)
found that the file's registration and cycle-warning oracles accept asynchronous log records
published by an earlier phase. The promoter checked both findings against the source before withdrawing; verify them independently. Phase seven of
`.agents/skills/contract-first-assurance/SKILL.md` forbids changing a promoted candidate's
assertions or observation window and forbids promoting an unstable candidate. The run therefore
reclassified CONTRACT-TDD-015 as `unstable` and withdrew the promotion instead of fixing the oracle.

You are the independent reviewer of this promotion change. Follow the independent-review route in
`verification/contract-tests/AGENTS.md`.

The assurance run lives in an untracked directory, called RUN below:
`/Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-workload-fit-tasktype/.contract-first-assurance/2026-11-24-remove-tasktype-workload-fit/`.
Round 6's review output is in RUN at
`evidence/reviews/remove-workload-fit-tasktype-3ce96f08-20261125-150850-070176-kimi-attempt-1.json`.

## What to check

1. Resolution. Does the withdrawal resolve `kimi:0` and `kimi:1`? Under phase seven and the
   classification list in SKILL.md, is withdrawal (rather than an oracle fix during promotion) the
   permitted action, and is `unstable` the right final classification?
2. Completeness of the commit. Does it change only the C0005 file? Are the C0002, C0003, and C0004
   files byte-identical to `ac95c948`, to their RUN archives under `promotions/`, and to their
   snapshots under `promotion-targets/`? Does any file in the repository still reference C-0005
   or the removed class?
3. Run records. Does RUN's `promotion-status.csv` keep the three promoted rows and mark
   CONTRACT-TDD-015 `blocked` with an accurate reason? Does `candidate-status.csv` classify 015
   `unstable` and keep its initial `new coverage` state, as "Failure classification must keep the
   initial state and the corrected final state" requires? Is the C0005 snapshot gone from
   `promotion-targets/` while its archive copy stays under `promotions/` as evidence? Do
   `report.md`, `verification-summary.md`, and `contract-gaps.md` describe three promotions and the
   withdrawal consistently? `ROUND7_*` placeholders in those files stand for this round's result
   and are filled in after it.
4. Regression gates. Steps 054 to 056 in RUN's `logs/commands.jsonl`: is each argv equal to the
   adapter's `regressionSuite` command for its phase, does each exit 0, and are the test counts in
   step 055's log consistent with the three remaining suite files?
5. Skip eligibility and message. The run is frozen at `f020503d`. Under "Skip eligible" in
   `.agents/skills/project-delivery/references/assurance.md`, is this test-only withdrawal
   skip-eligible, given that it removes a regression added earlier in the same delivery, changes
   no library behavior, and changes no evidence present at base? Are the commit message's claims
   accurate, including `assurance: SKIPPED` and `PIT: SKIPPED`?

## Output

Do not quote source text from `verification/contract-tests/` or from RUN's `candidates/`,
`promotions/`, `promotion-targets/`, or `adaptation/` anywhere in your output. That includes
string literals, entry identifiers, and method bodies. The implementer must not see that text.
Refer to files, classes, and methods by name, and describe defects in prose.

In `summary`, give one line per item: `RESOLVED` or `UNRESOLVED` for item 1, and `PASS` or
`FAIL` for items 2 to 4. For item 5, give `CONFIRM` or `REJECT` for skip eligibility and
`ACCEPT` or `REJECT` for the message. Report every defect as a finding tied to a file and a line,
with a concrete failure scenario.

## Verification already run

- In RUN's `logs/commands.jsonl`: `054-regression-metadata-post-withdrawal`,
  `055-regression-tests-post-withdrawal` (3396 + 2 core tests and 4 contract-suite tests), and
  `056-regression-execution-post-withdrawal`, all exit 0.
- The C0002, C0003, and C0004 files, their archives, their snapshots, and their `ac95c948` blobs
  have equal SHA-256.
- `git grep` finds no tracked reference to C-0005 or the removed class.
- `check-verification-integrity.py` reports no new violations. The one protected change it
  reports is this branch's earlier edit to `scripts/check-verification-integrity.py`.
  `check-doc-refs.py` and `check-local-links.py` pass.
