# Generation notes

## Assumptions where the contract or API was silent

- The inert-refusal warning message is assumed to contain the registered entry id verbatim, so
  candidates filter JUL records with `record.getMessage().contains("<entry-id>")`. The contract
  guarantees only the `Par '<id>' requested rejectEnqueue` prefix for the refusal warning; entry ids
  were chosen to be unique per candidate to avoid cross-test contamination.
- A `ThreadPoolExecutor` built with `new SubmissionAwareBlockingQueue<Runnable>(capacity)` is treated
  as the refusal-capable executor shape; `Executors.newFixedThreadPool` is treated as plain.
- `ThreadPoolExecutor.AbortPolicy` (the default) is assumed to be the rejection handler used by the
  library's executor when a refusing element is rejected at maximum pool size; the contract names
  this policy for the `SUBMISSION_REJECTED` observation.
- The terminal combine of `Par.mapAndCombine` is assumed to be submitted from a converging thread
  shortly after the last element succeeds; queued-not-rejected is observed by watching the
  application-owned combine pool's queue reach size one before the test releases its blocker.
- A `ForkJoinPool` is assumed to be "an executor the library cannot see through" for the
  registration-warning contract, exactly as the contract's example states.
- For the cycle-warning candidate, a task execution that calls `Par.map` on its own registered entry
  is assumed to record a self-wait (a pool waiting on itself) that forms a cycle, matching
  `ExecutorWaitReport`'s "single executor that waits on itself".
- The cycle-warning message is filtered by the candidate entry id and by warning level, because the
  contract states the wording is non-contractual; the candidate additionally relies on
  `cyclicComponents()` being non-empty.
- Absence assertions use bounded windows between 800 ms and 1500 ms; these windows are arbitrary and
  are stated in each Javadoc.

## Contract ambiguities found

- The exact wording and content of registration and cycle warnings is non-contractual; it is
  unclear whether they reliably name the entry id, so the cycle candidate cannot filter purely by a
  message prefix and uses level plus `cyclicComponents()`.
- The precise moment the warning is published relative to admission is unspecified; candidates
  await the record with a bound rather than assuming it is delivered before the synchronous call
  returns.
- It is not stated whether the inert-refusal record counts per entry, per entry/options pair, or per
  runtime; candidates assume per entry, as the contract's "once-only rule is per entry" states.
- The contract does not define whether the terminal combine's refusal inherits from the batch
  options; candidates assume it does not and always requests no refusal.
- For C-5, "no execution behavior changes" as a whole is not decidable; candidates assert only the
  specific observations the contract names.
- Whether a nested unit started inside a refusing unit's task inherits the refusal is explicitly out
  of scope and is not tested.
