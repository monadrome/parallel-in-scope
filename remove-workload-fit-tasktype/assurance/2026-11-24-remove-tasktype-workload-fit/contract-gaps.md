# Contract gaps

Initial gaps found while freezing `contract.md`. Phase five appends the candidate-derived results
under "Candidate results".

## Missing input boundaries and unit kinds

- G-1 API and options §3.2.1 lists the single task among the units checked at admission, but no
  public operation admits a single task: `Par.submit` is package-private and has no production
  caller. The clause is observable only through package-level tests, not through the public API.
- G-2 Whether a nested unit started inside a refusing unit's task inherits `rejectEnqueue` is not
  stated. The superseded ADR 0008 clause "nested units do not inherit the parent unit's type"
  concerned `TaskType` only.
- G-3 Whether a recognized `TransmittableThreadLocal` executor wrapper around a `ThreadPoolExecutor`
  with a `SubmissionAwareBlockingQueue` counts as refusal-capable for the inert-refusal warning is not
  stated. `ParRuntime.Builder.build()` documents seeing through recognized TTL wrappers only for the
  discarding-policy check.

## Missing distinction between final results and live observation

- G-4 No documented point guarantees that every diagnostic record of a runtime has been delivered.
  `close()` does not wait for executions or reports, and the documented conditions of
  `awaitQuiescence` do not mention diagnostic delivery. A test that asserts a record is absent can
  only observe a bounded window.
- G-5 Only the inert-refusal record's level, count, publishing thread, and entry attribution are
  documented. Its logger name, wording, and parameters are not, so a test can match only the entry id
  and the option name.

## Missing concurrency interleavings

- G-6 "The first unit through a `Par` ... logs one warning" implies exactly one record when several
  refusing units are admitted through one entry at the same time, but no interleaving is stated.

## Missing failure-handling requirements

- G-7 A7 says adopted members undergo no admission diagnostics, but an adopted member carries no
  options and cannot request refusal, so no public observation distinguishes compliance from
  violation for the inert-refusal record.
- G-8 ADR 0016 says "no execution behavior changes". As a whole this is not decidable; only the
  absence of the removed records and unchanged outcomes for specific runs are observable.

## Candidate results

`candidate-status.csv` holds every candidate's classification and reason. These results bear on
the contract:

- `CONTRACT-TDD-003` (wrong assumption). The candidate expected the blocked first element of a
  refusing batch to succeed while its siblings were rejected. A rejected element is a failure, so
  the batch's fail-fast cascade cancels a sibling that is still running; the element succeeds only
  when its release wins that race. The `lincheck-test` execution failed at that assertion while the
  default execution passed. The library documents fail-fast cancellation for every batch failure;
  the gap is in this run's C-2, which states only the rejected element's outcome.
- `CONTRACT-TDD-011` (insufficient oracle) confirms G-7.
- `CONTRACT-TDD-016` (insufficient oracle) confirms G-4 and G-8. A bounded absence window cannot
  show the removal: at base, `register(ParId, ExecutorService)` declared `TaskType.UNKNOWN` and the
  screen judged only `COMPUTE` entries over ten-second epochs of execution time, so base publishes
  no record in the same run either.
- `CONTRACT-TDD-012` (new coverage) exercises the interleaving that G-6 notes is unstated: eight
  refusing batches admitted through one entry at once still publish exactly one warning.
- `CONTRACT-TDD-005` and `CONTRACT-TDD-013` (new coverage) need no contract change.
- `CONTRACT-TDD-015` (initially new coverage, finally unstable). Its phases share one logger and
  install each capture handler without waiting for the previous phase's asynchronous record, so a
  late registration record can satisfy or break a later phase's oracle. Independent review round 6
  found this after promotion, and the promotion was withdrawn. This is a defect of the test, not a
  contract gap: a stable test can await each record before the next phase starts.

Four candidates needed a compile-only adaptation (`adaptation/compile-adaptation.diff`): three
wrapped a checked `InterruptedException` inside a `java.util.function.Function` body, and one
lacked an import. The sealed candidates under `candidates/` are unchanged.
