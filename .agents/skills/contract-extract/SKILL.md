---
name: contract-extract
description: Contract extraction: organizes frozen, executable behavioral contracts from a PR or worktree changes. Invoked only by the contract-first-assurance main entry or explicitly by the user.
version: "1.2.0"
disable-model-invocation: true
---
# Contract Extract

This phase is dispatched only by `contract-first-assurance`. Use the
`SKILL_ROOT` and `RUN_DIR` passed in by the parent flow; do not locate the entry or output
directories through fixed repository paths.

Extract the frozen contract from the PR/worktree scope, applicable rules, and evidence provided
by the main entry.

Read only:

- the specified diff, relevant design contracts, and public APIs;
- the applicable `AGENTS.md`;
- the existing behavioral evidence designated by the main entry.

Output `${RUN_DIR}/contract.md` and `${RUN_DIR}/contract-gaps.md`. Each contract uses a stable
ID and states the precondition state, operations, observable results, exceptions or outcomes,
lifecycle, concurrency/cancellation semantics, evidence source, and uncertainties. Do not
automatically promote implementation details into contracts.
