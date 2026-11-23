# Contract-First Assurance Routing

Use this policy for the root public API/documented-contract and concurrency routes. It decides
when to invoke the separately versioned `contract-first-assurance` bundle; the other gates for
the selected routes still apply.

## Threshold

Classify the semantic effect of the complete diff, not its number of files, lines, or methods.

### Run required

Invoke `contract-first-assurance` when any condition holds:

- observable behavior or requirements change, including preconditions, outcomes, exceptions,
  ordering, lifecycle, resource ownership, cancellation, deadline, context, executor, queue,
  visibility, or interleaving semantics;
- implementation changes affect those behaviors or a shared mechanism behind them, including
  behavior-preserving refactors of concurrency-sensitive code;
- test or oracle changes weaken, remove, or replace evidence for those behaviors;
- the implementing or reviewing model identifies an unresolved contract gap, inadequate
  behavioral evidence, possible silent false success, or another reason independent
  contract-derived verification would add signal.

### Skip eligible

A run may be skipped only when none of the required conditions holds. Examples include a
mechanical public method signature change with unchanged behavioral and concurrency semantics,
documentation edits that leave library requirements unchanged, or agent-workflow edits that
change neither library runtime contracts nor their behavioral evidence. A signature change that
alters overload resolution, nullability, callback semantics, or any other observable contract is
not mechanical.

Record `assurance: SKIPPED`, the changed scope, and the semantic reason no required condition
holds in the initial commit message, or in the PR description when an existing PR carries the
work; copy the same disposition into the delivery ledger note. The independent reviewer records
confirmation against the complete diff. If either model cannot establish that no required
condition holds, or a reviewer questions or rejects the skip, upgrade the change to **Run
required**; an unreviewed or unrecorded skip does not satisfy the gate.

## Delivery Sequence

Classify the change before the initial commit. Execute a required run after that commit so the
frozen scope is committed and the review gates required by the host adapter are available.
Independent review and assurance evidence gathering may overlap, but the report cannot finalize
until its required review evidence exists. Confirmed fixes follow both the review re-review
rules and the rerun rules below.

## Passing A Required Run

Run the bundle against the frozen base, head, and change scope, then run its `verify_run.py`.
A required run passes delivery only when verification succeeds and `${RUN_DIR}/report.md`
contains exactly one final-gate line with one of these values:

```text
Final gate: PASS
Final gate: PASS WITH ACCEPTED EXCEPTIONS
```

Use `PASS WITH ACCEPTED EXCEPTIONS` only when the manifest records every accepted exception and
its required evidence and approval. Preserve raw check statuses separately. `FAIL`, `BLOCKED`,
`INCONCLUSIVE`, a missing report, a failed verifier, or `SKIPPED` for a required run leaves
delivery incomplete.

An earlier report remains valid after later commits only when its frozen scope still covers the
semantic diff and every later change is independently confirmed skip-eligible. Otherwise rerun
the bundle; never rewrite the frozen inputs or hashes to make an old report cover a new head.

## Rejected Alternatives

Running assurance for every mechanical API or documentation edit was rejected because its cost
buys no new behavioral evidence. Letting diff size or the implementer alone waive the run was
rejected because small semantic changes can be high-risk and correlated implementation and test
assumptions are the blind spot this gate addresses.
