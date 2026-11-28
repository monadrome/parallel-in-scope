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

Write between 8 and 12 candidates covering C-1 to C-7. Prioritize, in order: quiescence after close,
including an execution that ignores interruption after its future was cancelled (C-3); the snapshot
and in-flight readings at idle, in-flight, and closed points (C-4, C-5); close semantics, including
rejection after close and registered executors staying usable (C-2); the adopted source queued
behind its own waiter (C-7); the absent sampling thread (C-6); the removed surface (C-1).

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
- releases what it creates: it closes every `ParRuntime` it builds, shuts down every executor in
  `finally`, and releases every latch it blocks on.

Also write:

- `candidates/candidate-plan.csv` with the header
  `candidate_id,contract_id,class_name,preconditions,operations,observations,assertions,uncertainty`
  and one row per candidate; quote any field that contains a comma;
- `candidates/generation-notes.md` listing every assumption you made where the contract or API was
  silent, and every contract ambiguity you found.

Write only under `candidates/`. When the files are complete, stop. Do not revise them after this
task ends.
