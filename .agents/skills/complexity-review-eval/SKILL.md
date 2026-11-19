---
name: complexity-review-eval
description: In-project complexity gray evaluation and refactoring attention routing. Use proactively whenever a code change involves longer methods, growing branches/nesting, structural refactoring, when the developer could only do a shallow review, when relying on agent review alone feels insufficient, or when P0036, complexity evaluation, gray evaluation, or refactoring candidates are mentioned. It produces auditable complexity metrics and routes candidates into refactoring-review's S lens; it does not treat complexity as a quality score, and it does not replace tests, contract checks, or independent code review.
---

# Complexity review evaluation

Treat the complexity mechanism as a gray signal in the evaluation system: it routes limited
reviewer attention to the methods worth reading in this change; deterministic builds and tests
hold the quality floor; semantic review decides whether refactoring is actually needed.

## When to invoke proactively

Typical situations for proactive invocation:

- a code change adds or significantly modifies long methods, deep nesting, or branch-dense
  methods;
- the developer says they only did a simple review and wants agent review to fill the gap;
- `/refactoring-review` is about to run and the change includes core production Java;
- the discussion is about P0036, complexity candidates, refactoring hotspots, evaluation gray
  periods, or review attention allocation;
- the change touches concurrency, cancellation, deadlines, executors, queues, or public
  contracts, and review intensity may need escalating.

A change that is pure documentation, pure configuration, or has no core production Java changes
may invoke this skill for the record, but should expect zero candidates.

## Execution steps

1. Confirm the behavior change is committed first. A formal evaluation compares the commit's
   base/head; uncommitted production code does not enter metrics.
2. Run in the repository root:

   ```bash
   python3 scripts/refactoring-review.py plan \
     --base origin/dev/v0.5.0 \
     --head HEAD \
     --output-dir .agents/reviews/<topic>-complexity \
     --force
   ```

   `--base` may be omitted because the project configuration defaults to `origin/dev/v0.5.0`.
   When only debugging an uncommitted state, `--allow-dirty` may be used, but you must state
   that the result does not include uncommitted code and cannot serve as formal evaluation
   evidence.

3. Read these artifacts:

   - `.agents/reviews/<topic>-complexity/metrics.json`
   - `.agents/reviews/<topic>-complexity/plan.json`
   - the structure lens's `agents/S/brief.md` (or the corresponding S shard's brief)

4. Confirm `metrics.json` contains:

   ```text
   stage = gray-evaluation
   purpose = attention-routing evidence; not a quality score or CI gate
   ```

   If the stage is missing, collection failed, or the output is unparseable, report
   `blocked/inconclusive` — never treat it as "no complexity candidates".

5. If agent detectors are needed, continue with the existing refactoring-review flow:

   ```bash
   python3 scripts/refactoring-review.py detect \
     .agents/reviews/<topic>-complexity \
     --reviewer codex

   python3 scripts/refactoring-review.py merge \
     .agents/reviews/<topic>-complexity
   ```

   When a detector's output is unparseable, times out, or it did not read the changed files,
   its status is blocked, not clean.

## How to interpret candidates

A complexity candidate is only a review hint. For each candidate, answer first:

- which responsibilities, branches, or protocol steps compose the complexity;
- whether there is an independently changeable responsibility boundary;
- what the concrete behavior-preserving refactoring move is;
- which tests, ordering, cancellation, deadline, interrupt, executor binding, and resource
  lifecycles must be preserved.

A candidate may be rejected when: the algorithm itself needs complex control flow, exhaustive
dispatch is a completeness requirement, the code is a concurrency protocol, or splitting would
only add parameter passing and hidden state. Never change behavior to lower CC, line counts, or
nesting depth.

## Quality floor

This skill does not replace these checks; they still run per task routes:

- compilation, formatting, and the full test suite;
- public API surface checks;
- race claims, concurrency-specific tests, and applicable PIT;
- independent `/multi-review`;
- documentation and migration notes.

Zero complexity candidates, empty detector findings, or low metrics do not mean the change
passes. Delivery may be reported only when the applicable deterministic gates and independent
review evidence are all valid.

## Gray-stage boundaries

- The current stage is fixed as `gray-evaluation`;
- The current scope is core production Java, excluding `queue`;
- The current implementation is a dependency-free conservative scanner — an evaluation input,
  not a quality score;
- No new CI failure conditions, no automatic fixes, no complexity-reduction requirements;
- Whether to upgrade to an AST collector or add risk signals later depends on the
  effective-finding rate and false-positive cost on real changes.

## Result reporting

When reporting to the user, state at least:

1. whether the evaluation ran and its stage;
2. the candidate count and whether they entered the S lens;
3. the status of deterministic gates and independent review;
4. that the complexity mechanism is still in gray evaluation, not a CI quality gate;
5. the reason for any blocked/inconclusive.
