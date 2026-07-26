# Deep Concurrency Test Report

Status: temporary coding-agent report for the current branch. Production code is intentionally unchanged by this test effort.

## Verification Baseline

- Before new tests: `mvn -B -ntp test` passed 166/166 on 2026-07-26.
- Maven ran with JDK 25 while production sources compile with `--release 8` and tests with `--release 11`; this is not Java 8 runtime evidence.
- First generated surfaces: 16/16 passed across Future lifecycle, rejection behavior, and queue waiters.
- Enabled generated/contract cases now total 60: lifecycle 6, rejection 4, sliding window 6, cancellation trigger/workload 11, propagation effects 4, condition/queue mutation 11, and purger boundaries 18.
- One additional expected-contract regression is disabled for PCT-001.
- Repeated verification: all seven new test classes ran ten consecutive times with exit code 0, totaling 600 enabled case executions and 10 expected PCT-001 skips.
- Root full gate: `mvn -B -ntp clean verify` passed 227 tests, 0 failures, 0 errors, and 1 PCT-001 skip; JAR, sources JAR, and Javadoc JAR were built successfully.
- Independent demo gate: `mvn -B -ntp test` passed 54/54 tests in 1 minute 7 seconds.

## Product Findings

### PCT-001: Parent cancellation state depends on whether child late binding already happened

Severity: medium contract/diagnostic inconsistency. Cancellation still reaches the child work, but the reported cause is wrong for one timing.

Reproduction: `CancellationPropagationCartesianTest.alreadyCancelledParentClassifiesChildAsPropagatingCancellation`.

Observed matrix:

| Parent timing | Child work | Child Future | Child state |
|---|---|---|---|
| before child `lateBind` | pending | cancelled | `FAIL_FAST_CANCELED` |
| before child `lateBind` | running | cancelled/interrupted | `FAIL_FAST_CANCELED` |
| after child `lateBind` | pending | cancelled | `PROPAGATING_CANCELED` |
| after child `lateBind` | running | cancelled/interrupted | `PROPAGATING_CANCELED` |

Expected: both parent-originated timings classify the child as `PROPAGATING_CANCELED`.

Cause from source/test evidence:

1. The already-cancelled branch in `CancellationToken.lateBind` cancels `futureToken` but does not first transition `state` to `PROPAGATING_CANCELED`.
2. Cancelling `futureToken` cancels the newly attached fail-fast Future.
3. The fail-fast callback then wins `RUNNING -> FAIL_FAST_CANCELED`.
4. The later-parent branch explicitly performs `RUNNING -> PROPAGATING_CANCELED` before cancellation.

Impact: monitoring and diagnostics cannot distinguish parent propagation from sibling failure consistently. Any policy keyed by `CancellationTokenState` sees a different cause depending only on bind timing.

Disposition: production code not modified. The expected-contract regression test is retained with `@Disabled` and a precise reason; the four-case effect matrix remains enabled to verify actual cancellation propagation.

## Test-Implementation Findings

- Initial generated tests imported `ParameterizedTest` from the wrong JUnit package. This was a test compile issue and was corrected before behavioral execution.
- Thread state is used only to prove a queue waiter reached a blocking condition before the test releases it. Result correctness is asserted from queue contents, operation result, interruption evidence, and thread termination.

## Requirement Audit

| Requirement | Evidence | Result |
|---|---|---|
| Remove unrelated planning-with-files artifacts | Historical `.planning/` files and `demo/{task_plan,findings,progress}.md` removed; root planning files contain only this task | complete |
| Deep project analysis | Ten feature layers, six observation planes, compatibility predicates, execution topology, and cleanup semantics in ADR 0001 | complete |
| Cartesian case construction | Seven test classes use `@MethodSource` generators with stable case identities; local products range from 2 x 2 to 3 x 3 x 2 | complete |
| Task dimensions | Pending, IO gate, bounded CPU, mixed CPU/blocking, long cooperative, and interruption-ignoring work execute in the trigger matrix | complete |
| Execution phases and executor behavior | Before-run/running/terminal, initial/later rejection, caller/submitter direct fallback, placeholder and window advancement matrices | complete |
| Timeout categories | Timeout is crossed with all five workload states; Future cancellation, token state, interruption, liveness, and submitter cancellation are separate assertions | complete |
| Cancellation propagation | Parent before/after child bind x pending/running child matrix; PCT-001 captures inconsistent state classification | complete with documented product issue |
| Condition races | Producer/consumer x counterpart/interrupt/timeout plus take/remove/clear/drain/capacity-grow producer wake-ups | complete |
| Thread scheduling edge | Captured runnable models no worker time slice; entered/release latches model body execution; thread state is used only to establish a parked precondition | complete |
| Purger branch behavior | Full pressure below/exact/above x cancellation ratio below/exact/above x enabled matrix, plus existing expiry/reconfiguration/integration tests | complete |
| No production fixes | No task edit under `src/main`; PCT-001 remains unfixed and its expected-contract test is disabled | complete |
| Verification | 10 repeated generated-suite runs, root clean verify, demo suite, and `git diff --check` | complete |

## Residual Limits

- Tests run on Maven's JDK 25 with production compilation targeting Java 8 and test compilation targeting Java 11. They do not constitute Java 8 runtime execution.
- JVM scheduling cannot portably guarantee that a started native thread receives no time slice. The captured-runnable fixture provides the deterministic equivalent at the executor boundary.
- Purger negative cases necessarily observe a bounded quiet interval because absence of an asynchronous purge has no instantaneous terminal event.
