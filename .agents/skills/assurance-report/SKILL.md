---
name: assurance-report
description: Independent verification report: aggregates contracts, tests, reverse verification, and implementation improvement suggestions. Invoked only by the contract-first-assurance main entry or explicitly by the user.
version: "1.2.0"
disable-model-invocation: true
---
# Assurance Report

This phase is dispatched only by `contract-first-assurance`. Use the
`SKILL_ROOT` and `RUN_DIR` passed in by the parent flow; do not create a second run directory
or read other runs' intermediate artifacts.

Read this round's frozen contract, candidate statuses, real-implementation verification,
reverse verification, and contract gaps, and produce `${RUN_DIR}/report.md`. If a phase did not
run, the report must keep the `SKIPPED`, `BLOCKED`, or `INCONCLUSIVE` status and the reason.

The report must distinguish:

- implementation defects;
- wrong test assumptions;
- missing contracts or insufficient oracles;
- environment, timing, and verification blockers.

Report the statuses of behavior tests, full tests, reverse verification, PIT, and independent
review separately. PIT must report raw status, disposition, and the final gate together; an
unaccepted or incompletely evidenced timeout remains `INCONCLUSIVE`; a well-evidenced,
approved mutation exception may make the PIT gate `PASS`, but the raw status must not be
rewritten.
