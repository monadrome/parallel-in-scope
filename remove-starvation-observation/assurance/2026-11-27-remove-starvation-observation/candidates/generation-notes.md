# Generation notes

Assumptions made where the contract or sanitized API was silent, and contract ambiguities found.

## Assumptions

- "single-task submission" in C-2 has no dedicated public API in `sanitized-api.md`; rejection is exercised through a one-element `Par.map` batch and a single-member group `runAll()`.
- C-7's registered executor is the raw `ThreadPoolExecutor` (so the library can recognize a starvation-prone pool); a Guava `ListeningExecutorService` view is used only by the test to submit the source as a `ListenableFuture`.
- C-7's "must not wrap/submit/cancel (other than the source)/reorder on the source's behalf" obligations are internal and not directly observable through the public API; only the three observable outcomes are asserted.
- `inFlight()` returns exactly 1 for one admitted batch whose future has not settled; this follows the Javadoc definition of "admissions setting up an execution plus undrained runs".
- The `close()` non-blocking property is proven with a still-held latch (release count still 1) rather than wall-clock timing.
- The "no starvation sampling thread" property is verified by discrete sampling at three points (during nested and top-level work, after completion, after close), not continuous observation.
- The ignoring-interruption execution loops used by C-3/C-4/C-5 candidates are capped at ten seconds so a test failure can never leave a permanently hung worker thread.
- Timing lower bounds (`>=800ms` of a 1s timeout, `>=400ms` of a 500ms timeout) prove that a wait actually elapsed and use generous margins to avoid flakiness.

## Ambiguities

- C-3 says an interrupted caller observes `InterruptedException` "even when the runtime is already quiescent"; quiescence is interpreted as a closed runtime with no admitted work, matching the example.
- C-5's "public readings are exactly ..." is interpreted as exercising the four named accessors plus `toString()` rather than reflectively enumerating methods; post-close counters are intentionally not cross-checked because the contract forbids requiring consistency beyond the stated cases.
- C-1's nested `Status`/`Evidence` types are not checked by `Class.forName` because nested names are only loadable as members of the removed outer type; the outer lookups imply their absence.
- The word "submit" in C-7 is interpreted as enqueueing the source task on the registered pool; using a listening view to obtain a `ListenableFuture` does not add an extra queued task.
