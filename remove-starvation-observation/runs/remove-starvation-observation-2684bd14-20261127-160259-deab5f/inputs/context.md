# Review context, round 2: remove thread-pool starvation observation

Range: 3e04f590 .. 2684bd14 (two follow-up commits on top of `feat!: remove thread-pool starvation
observation`, which removed `ParRuntime.openThreadPoolStarvationObservation(Duration)`,
`ThreadPoolStarvationObservationScope`, `ThreadPoolStarvationReport`, and
`ParRuntimeSnapshot.activeObservations()`).

Settled round-1 findings (confirmed and fixed in this range; do not re-raise unless the fix is wrong):

- `design/task-group-adopted-futures.md` header listed the removed observation.
- `README.md` / `README.zh-CN.md` capability claims listed starvation diagnostics.
- `BACKLOG.md` identity-probe entry cited the removed `NO_ISSUE` status.

Commits in range:

- `docs: drop stale starvation-observation references found in review` (the three fixes above).
- `test: pin timeout-action thread retirement at quiescence`: adds
  `ParRuntimeTest.quiescenceRetiresTheTimeoutActionThreads`, written after focused PIT showed that
  deleting `timeoutActionPool.shutdown()` in `ParRuntime.shutdownServicesWhenAdmissionsComplete`
  survived.

Read every changed file in the range. Attack surfaces: whether each documentation fix is accurate
against the code and the remaining contracts; whether the new test is deterministic, bounded, releases
what it creates, and actually detects the missing pool shutdown (and only that) through documented
behavior (`awaitQuiescence` and `releases()` Javadoc); Java 8/11 test-source compatibility; and any
stale reference to the removed API the first round missed anywhere in the repository's live documents.

Do not edit files.
