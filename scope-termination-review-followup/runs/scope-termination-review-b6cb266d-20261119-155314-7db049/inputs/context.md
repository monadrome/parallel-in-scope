Review the commit range origin/dev/v0.5.0..b6cb266d in the immutable snapshot. This is an internal behavior-preserving refactor following an already completed six-lens review; do not propose reopening the deleted ScopeTerminationCause design.

Read every changed file from the commit diff, including verification/race-claims/claims.json and all test helpers/tests. Attack surfaces:
- ScopeTermination.primaryOr must preserve primary-over-fallback precedence in both TaskBatch.freezeResults and TaskGroup.snapshot. Its eager fallback argument is intentional: fromResults is pure over an already frozen list; assess any actual observable or complexity regression rather than assuming laziness is required.
- Batch caller-cancellation override must still replace only a null or CANCELLED primary, never FAILED, and accumulator CAS/provisional fail-fast replacement must remain unchanged.
- Verify.verifyNotNull replaces internal invariant checks only; source remains caller-validated and the unreachable private-constructor kind check is removed.
- TaskGroup.explicitScopeTermination Javadoc must match both branches and the null fallback.
- TerminationAssertions overload must preserve every prior assertion at exactly the three intended call sites without widening TaskTerminationTest's oracle.
- The new race claim must mutate the intended source exactly once and its cited test must detect the intended semantics.
- Check Java 8 compatibility, nullability, generics, contract drift, and test quality.

Remain read-only. Return concrete defects with file and symbol evidence; do not edit files or run write operations.
