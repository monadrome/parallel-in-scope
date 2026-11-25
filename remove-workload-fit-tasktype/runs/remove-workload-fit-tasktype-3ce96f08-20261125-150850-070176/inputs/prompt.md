You are one independent reviewer in the llm-review-bench benchmark.

Review the change from 758b7df6429cfd3be554bb716eb9d9537727addf to 3ce96f08284e3e718311c58669b52c0cf96655e5. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-workload-fit-tasktype/.review-benchmark/runs/remove-workload-fit-tasktype-3ce96f08-20261125-150850-070176/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-workload-fit-tasktype/.review-benchmark/runs/remove-workload-fit-tasktype-3ce96f08-20261125-150850-070176/inputs/context.md. Do not modify the snapshot. Do not read
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

# Remove TaskType and workload-fit screening: review round 6 (promotion, PIT disposition, skip eligibility)

Review `758b7df6..3ce96f08` read-only. The implementer is Claude (Opus). Do not edit files, run
Maven, or commit. You may run read-only commands such as `git`, `grep`, `sed -n`, `diff`, and
`shasum -a 256`.

## The commits

1. `2f73397f docs: withdraw installGlobal PIT equivalence claim` changes one file,
   `verification/pit-audit/2026-11-24-remove-tasktype.md`. Round 5 reviewed its content and found
   it RESOLVED. Do not re-review that text; answer only item 3 for this commit.
2. `ac95c948 test: promote TaskType removal regressions` adds four test files under
   `verification/contract-tests/src/test/java/io/github/monadrome/parallelinscope/`:
   `C0002MapAndCombineCombineNoRefusalQueuedContractTest`,
   `C0003ConcurrentFirstAdmissionsOnceContractTest`, `C0004OffThreadBlockingHandlerContractTest`,
   and `C0005RegistrationAndCycleWarningsContractTest`. You are the independent reviewer of this
   promotion. Follow the independent-review route in `verification/contract-tests/AGENTS.md`.
3. `3ce96f08 docs: record maintainer disposition of ParRuntime PIT gaps` changes the PIT record
   `verification/pit-audit/2026-11-24-remove-tasktype.md`, its archive README, and the README's
   line in `SHA256SUMS`. On 2026-11-25 the maintainer, in this session, accepted the four
   pre-existing `ParRuntime` gap entries as residual nonblocking limitations. The commit records
   that disposition and changes the overall status from Inconclusive to Pass.

The assurance run lives in an untracked directory, called RUN below:
`/Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-workload-fit-tasktype/.contract-first-assurance/2026-11-24-remove-tasktype-workload-fit/`.
Its relevant files are:

- `contract.md`: the frozen contracts C-1 to C-5.
- `candidate-status.csv` and `promotion-status.csv`: classifications and promotion rows.
- `candidates/`: the sealed candidates, never edited after generation.
- `adaptation/promotion-adaptation.diff`: the only permitted differences between a sealed
  candidate and its promoted target.
- `promotions/` and `promotion-targets/`: the archive copies and the frozen snapshots.
- `reverse-verification/README.md` and `reverse-verification/patches/`: the contrary patches and
  the failing runs.
- `logs/commands.jsonl`: every recorded command with its argv and exit status; per-step logs sit
  beside it.

Policy: `.agents/skills/contract-first-assurance/SKILL.md` (phase seven, promotion),
`.agents/skills/project-delivery/references/assurance.md` ("Skip eligible"), and
`verification/pit.md`.

## What to check

1. Promotion integrity, for each of CONTRACT-TDD-005, -012, -013 and -015:
   - the promoted target equals its sealed candidate plus exactly its hunks in
     `adaptation/promotion-adaptation.diff`;
   - those hunks change only the package, class name, contract metadata, provenance, and
     formatting, never inputs, actions, or assertions;
   - the archive and the snapshot are byte-identical to the committed file;
   - the contract ID is unique in the suite and matches `promotion-status.csv`;
   - the provenance names the run and the source contract;
   - the target does not duplicate an existing suite test.
2. Stability. Name every assertion that depends on timing, scheduling, or machine speed in a way
   that could fail on a slow or loaded CI runner. For each, say whether its bound is generous
   relative to the operation it waits for. CI runs the suite in the `hidden-contract-regression`
   job.
3. Skip eligibility. The assurance run is frozen at `f020503d`. Under "Skip eligible", does each
   of the three commits leave that run valid? `2f73397f` must be documentation-only with respect to library
   requirements, and so must `3ce96f08`. The promotion must be test-only: it adds the run's reverse-verified regressions
   without changing library behavior or weakening evidence.
4. The promotion's commit message. Are its `MUST:`, `MUST NOT:`, `Assurance:`, `assurance:
   SKIPPED`, and `PIT: SKIPPED` lines accurate? Check the PIT claim against `verification/pit.md`
   and the PIT test set: does PIT's scope include `verification/contract-tests`?
5. The disposition commit. Does the record now satisfy "Dispositions and completion" in
   `verification/pit.md`: every `SURVIVED`, `NO_COVERAGE`, and `TIMED_OUT` entry in the change
   scope disposed, each accepted gap keeping its contract, evidence, impact, and next action, and
   `Pass` justified? Do the changed-classes table, the gap section, and "Overall status" agree?
   Is the citation of round 4's approval of the four `ParRuntime` timeouts accurate? Does
   `shasum -a 256 -c SHA256SUMS` still pass in the archive directory?

## Output

Do not quote source text from `verification/contract-tests/` or from RUN's `candidates/`,
`promotions/`, `promotion-targets/`, or `adaptation/` anywhere in your output. The implementer
must not see that text. Refer to files, classes, and methods by name, and describe defects in
prose.

In `summary`, give one line per candidate for item 1 (`PASS` or `FAIL`), one line for item 2
(`STABLE`, or the risks), one line per commit for item 3 (`CONFIRM` or `REJECT`), and one line for
item 4 (`ACCEPT` or `REJECT`), and one line for item 5 (`ACCEPT` or `REJECT`). Report every defect as a finding tied to a file and a line, with a
concrete failure scenario.

## Verification already run

- Promotion, in RUN's `logs/commands.jsonl`: `048-regression-format` (contract-tests
  `spotless:check`), then `051-regression-metadata-final`, `052-regression-tests-final`, and
  `053-regression-execution-final`, all exit 0. Step 052 ran 3396 + 2 core tests and 5
  contract-suite tests. The committed files, the archives, and the snapshots have equal SHA-256.
- Disposition: `check-doc-refs.py`, `check-local-links.py`, and `check-english-docs.py` pass;
  `shasum -a 256 -c SHA256SUMS` reports 88 OK.
