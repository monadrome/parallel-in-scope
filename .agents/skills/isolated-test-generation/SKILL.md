---
name: isolated-test-generation
description: Isolated test generation: generates candidate tests solely from the frozen contract and sanitized APIs. Invoked only by the contract-first-assurance main entry or explicitly by the user.
version: "1.3.0"
disable-model-invocation: true
---
# Isolated Test Generation

This phase is dispatched only by `contract-first-assurance`. Use the
`SKILL_ROOT`, `RUN_DIR`, and frozen input package passed in by the parent flow; do not recover
context from the parent checkout, fixed repository paths, or hidden directories.

In a fresh context and an independent worktree, generate candidate tests solely from the frozen
`contract.md`, the sanitized API/type views, and the necessary build commands.

Do not read the real production source, existing tests, Git history, the main agent's
conversation, the implementation agent's reasoning, or real-implementation verification logs;
do not modify production code. Assign each candidate test a stable ID, write out the
preconditions, operation sequence, observation points, assertions, and uncertainties, and write
the sealed artifacts into `${RUN_DIR}/candidates/`. Only after the candidate tests are sealed
may they be copied into the real verification worktree.
