# Review context, round 2: fixes after the first review of the join-policy change

Range: ce7b5cc2 .. HEAD, two commits on branch feat/join-policy-await-all:

1. `test: close the PIT gaps in the scope-termination and await-all paths` (f6c8797f): removes the
   unobservable token read from `TaskBatch#deriveScopeTermination`, makes the five-argument
   `TaskBatchResult` constructor require the derived termination, removes the synchronous
   `groupToken.failFastCancel()` on a group combine failure (the scope termination is now derived
   with the first failed task first), and adds tests for the direct-cancel priority, a running
   pool combine after all members succeeded, and the `joinPolicy` thread check.
2. `fix: address the first adversarial review of the join-policy change` (6bd5d76e): the
   `mapAndCombine` AWAIT_ALL combine gate becomes one `successfulAsList(views)` listener (no
   SEVERE aggregate logging), deadline tests move to `ManualClock`, and contract/user-doc text is
   corrected (direct-cancel attribution in groups, no `close()` in public docs, task-group
   cancellation and lifecycle contracts).

Settled in round 1 (do not re-raise unless the fix is wrong): FAIL_FAST group direct-cancel
attribution text, allAsList SEVERE logging under AWAIT_ALL, close() in public docs,
task-group-cancellation.md derivation text, lifecycle/adopt policy qualification, wall-clock
deadline tests.

Do not read `verification/contract-tests/`.

Attack surfaces: is removing the combine-failure `failFastCancel()` really unobservable (any
reader of the group token before or after convergence: values future, report, completion task,
member classification, parent propagation to nested scopes still running inside a member, the
FAIL_FAST bind callback order); does the new `successfulAsList(views)` gate preserve the
exactly-once combine submission and the deadline/cancelOn paths under AWAIT_ALL; is the batch
derivation without the token read equal for every reachable token state (TIMEOUT, CANCELLED,
PROPAGATED_CANCELLED, FAIL_FAST, caller cancel); test determinism; contract versus implementation
for every changed MUST.
