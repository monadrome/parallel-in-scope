# Reverse verification

All runs used the verification worktree `.worktrees/rwf-assurance-verify` at the frozen head
`f020503d`, with the sixteen candidates copied into
`core/src/test/java/io/github/monadrome/parallelinscope/assurance/`. The four new-coverage
candidates below are byte-identical to the sealed files under `candidates/`. The four adapted
candidates (003, 004, 009, 016) reproduce `adaptation/compile-adaptation.diff` exactly.

Each step ran through the gate wrapper, which recorded its argv, exit status, and logs in
`logs/commands.jsonl`. A focused run is
`mvn -pl core test -Dtest=io.github.monadrome.parallelinscope.assurance.<Class> -Dsurefire.failIfNoSpecifiedTests=false`.
Each mutated run failed in the `default-test` execution, so Maven stopped before the
`lincheck-test` execution. Each patch was applied with `git apply`, reverted with `git apply -R`,
and followed by `git diff --exit-code`, which exited 0 every time.

## CONTRACT-TDD-005 / terminal combine of a refusing `mapAndCombine`

`patches/005-combine-inherits-batch-refusal.patch` makes the terminal combine in `Par` inherit the
batch's `rejectEnqueue` instead of requesting none. The candidate failed
(`logs/015-reverse-candidate-tdd-005.stdout.log`, exit 1) in
`mapAndCombineTerminalCombineIsQueuedNotRejected`: "queue did not reach size 1 within PT10S", so
the combine was not queued onto the busy refusal-capable executor. Apply, restore, and clean:
logs 014, 016, 017, all exit 0.

## CONTRACT-TDD-012 / concurrent first admissions through one entry

`patches/012-racy-once-only-guard.patch` replaces the `compareAndSet` once-only guard of the
inert-refusal warning with a check-then-set that sleeps 500 ms between the check and the set. The
candidate failed (`logs/019-reverse-candidate-tdd-012.stdout.log`, exit 1) in
`concurrentFirstAdmissionsStillWarnOnce`: expected one warning, got 8. Apply, restore, and clean:
logs 018, 020, 021, all exit 0.

## CONTRACT-TDD-013 / blocking handler for the inert-refusal warning

`patches/013-caller-waits-for-delivery.patch` makes `Announcements.deliver` wait until the handler
has run. The candidate failed (`logs/023-reverse-candidate-tdd-013.stdout.log`, exit 1) in
`diagnosticIsDeliveredOffThreadAndNeverBlocksCaller` at the `admitThread.isAlive()` assertion
("Expecting value to be false but was true"): the admitting thread was still blocked after its
10-second join. Apply, restore, and clean: logs 022, 024, 025, all exit 0.

The patch changes the delivery helper that every admission diagnostic shares, so
`ExecutorWaitAdmissionTest.theCycleWarningRunsOffTheAdmittingThreadAndABlockedHandlerDelaysNoAdmission`
would detect it through the cycle warning. This run shows that the candidate detects a blocking
delivery. The candidate's new evidence is the inert-refusal site with a blocking handler, which no
existing test exercises.

## CONTRACT-TDD-015 / registration warning publishing thread

`patches/015-registration-warning-on-build-thread.patch` makes `ParRuntime` deliver the
registration warning for an executor that is not a `ThreadPoolExecutor` with `Runnable::run`, on
the building thread. The candidate failed (`logs/027-reverse-candidate-tdd-015.stdout.log`,
exit 1) in `registrationAndCycleWarningsAreOffThread` at the publishing-thread assertion: the
record's thread ID equaled the building thread's ID. Apply, restore, and clean: logs 026, 028,
029, all exit 0.

## Restored run

After the last restore, the four candidates ran together on the unmodified head
(`logs/030-reverse-restored-run.stdout.log`, exit 0): four tests passed in each of the
`default-test` and `lincheck-test` executions.

No temporary mutation remains in either worktree. The candidate copies were then deleted from the
verification worktree, which `git status --short` reports clean.
