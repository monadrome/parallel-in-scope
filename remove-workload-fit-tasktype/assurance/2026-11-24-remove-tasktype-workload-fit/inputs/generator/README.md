# Isolated contract-test generation

You are a fresh, isolated test generator. Write JUnit candidate tests that check the frozen contract
in `contract.md` against the public API in `sanitized-api.md`. A separate verifier runs them against
the real implementation later.

## Allowed inputs

Read only these files in the current directory: `README.md`, `contract.md`, `sanitized-api.md`, and
`commands.md`. Do not read parent directories, any repository checkout, library sources or jars, the
Maven cache, Git history, existing tests, or any other file. Do not use the network. Do not run Maven,
`javac`, or the candidates: nothing to compile against exists here, by design.

## What to write

Write between 10 and 16 candidates covering C-1 to C-5. Prioritize, in order: per-entry once-only
reporting and attribution for every public unit kind (C-3); the `mapAndCombine` combine requesting no
refusal (C-2, C-3); refusal turning queueing into thread growth or `SUBMISSION_REJECTED` (C-2);
delivery off the admitting thread with throwing and blocking handlers (C-4); concurrent first
admissions (C-3); registration and cycle warnings (C-4); the removed surface and removed records (C-1,
C-5).

Each candidate:

- has a stable ID `CONTRACT-TDD-NNN`, numbered from 001, and one file
  `candidates/ContractTddNNN<ShortName>Test.java` declaring one public class of the same name with
  exactly one `@Test` method;
- declares `package io.github.monadrome.parallelinscope.assurance;` and uses only the public API in
  `sanitized-api.md` plus the libraries listed in `commands.md`;
- starts with a class Javadoc stating the candidate ID, the contract ID, the precondition state, the
  operation sequence, the observation points, the assertions, and any uncertainty;
- asserts public behavior from the contract only, not invocation counts, private state, or wording
  the contract marks as non-contractual;
- is deterministic: it coordinates threads with latches or futures, bounds every wait (at most 10
  seconds for an expected event), and uses a time window only to assert an absence, naming that window
  in the Javadoc;
- releases what it creates: it closes every `ParRuntime` with try-with-resources, shuts down every
  executor in `finally`, releases every latch it blocks on, and removes every logging handler it adds.

Also write:

- `candidates/candidate-plan.csv` with the header
  `candidate_id,contract_id,class_name,preconditions,operations,observations,assertions,uncertainty`
  and one row per candidate; quote any field that contains a comma;
- `candidates/generation-notes.md` listing every assumption you made where the contract or API was
  silent, and every contract ambiguity you found.

Write only under `candidates/`. When the files are complete, stop. Do not revise them after this
task ends.
