# Lens T: Test Structure

Structure of test code the change added or modified. Tests are the oracle for every other lens
(R8), so moves here may restructure a test but must keep each test's inputs, assertions, and timing
identical. Default tier `T1`.

### T01 Implementation-coupled test
- Smell: a test that asserts private state, call counts on the project's own collaborators, or an
  internal ordering the contract does not promise, so a behavior-preserving refactoring breaks it.
- Move: assert through the seam the contract names.
- Suppress: kernel probe tests that a contract or a declaration comment names as probes (for
  example body-reference release probes).
- Tier: `T2` (changing what the test asserts changes the oracle).
- mechanizable: no

### T02 Tautological assertion
- Smell: the expected value is computed the way the code computes it, so the test passes by
  construction.
- Move: replace with a literal or worked example traceable to the contract.
- Tier: `T2` (it changes the oracle).
- mechanizable: no

### T03 Timing as synchronization
- Smell: `Thread.sleep`, polling with a fixed timeout, or wall-clock comparisons used to order
  events the test could order with a latch, a `SettableFuture`, or the test `ManualClock`.
- Move: replace the wait with an explicit signal.
- Tier: `T1` when the assertions stay identical.
- mechanizable: partly

### T04 Repeated setup
- Smell: the same setup appears in three or more tests the change added.
- Move: Extract Function into a test helper. Two copies stay: tests favor reading on their own over
  sharing.
- Tier: `T0`.
- mechanizable: no

### T05 Name that hides the behavior
- Smell: a test name describing the mechanism (`callsX`, `test1`) instead of the observable
  behavior it pins.
- Move: Rename to state the behavior.
- Tier: `T0`.
- mechanizable: no
