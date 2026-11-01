# Lincheck verification

Lincheck explores interleavings for concurrent code. It complements the repeated
stress tests (`CancellationTokenBindRaceTest`) and the mutation-based
[race-claim harness](race-claims/README.md): a stress test samples a race, a
mutation proves a test is sensitive to a specific reordering, and Lincheck
searches schedules and reports a reproducible trace when an invariant breaks.

## Scope

Use Lincheck for cancellation, publication, and handoff windows that a repeated
`Thread` start/join race can miss. Current coverage:

- `CancellationToken`: cancel vs. bind, and parent-cancel vs. child-bind with a
  bound task and submission canceller.
- Each invocation builds fresh state; the assertion is an invariant that must
  hold in every schedule, not a snapshot of one expected trace.

Do not model the whole executor or group lifecycle in Lincheck. The operation
surface is too large and the timeout machinery adds real-time behavior that the
interleaving explorer does not control. Test the narrow window instead.

## Run

```bash
mvn test-compile surefire:test@lincheck-test
```

`mvn test` also runs these tests through the same execution. Do not use
`-Dtest='*LincheckTest'`: the property overrides the default execution's
excludes, so the class runs once with Mockito's agent and once in the isolated
fork.

The tests use 10,000 invocations. That is deliberate: an early probe with 5
invocations missed a lost update that 10,000 reproduced. Do not lower the count
to make a suite faster without first proving the smaller count still finds the
race under a deliberate break.

The build JDK is 25. Lincheck self-attaches a Java agent; the JVM prints a
dynamic-agent warning. Add `-XX:+EnableDynamicAgentLoading` to the test JVM when
the warning is undesirable; it does not affect the result.

## JVM isolation and ByteBuddy

`*LincheckTest` classes are excluded from the default Surefire execution and run
in a separate `lincheck-test` execution with `reuseForks=false`. Lincheck and
Mockito both attach ByteBuddy agents; in a reused JVM the previously attached
Mockito agent appears inside Lincheck's instrumented code, and Lincheck's
snapshot tracker then fails on JDK module access while walking Mockito's
dispatcher. A fresh fork that never loads Mockito avoids the interaction.

The POM also pins `byte-buddy`/`byte-buddy-agent` to the version Mockito 5.24
declares. Lincheck 3.7's transitive 1.14.12 does not recognize the JDK 25
class-file version and otherwise breaks Mockito's inline mock maker even in the
default execution.

## Relationship to PIT and Java 8

- Lincheck is a `test`-scope dependency; Kotlin/ByteBuddy/ASM never enter the
  published artifact or its runtime classpath.
- PIT runs its own instrumentation and should not run Lincheck tests in the same
  mutation pass. Run PIT with the deterministic tests
  (`CancellationTokenTest`, `CancellationTokenBindRaceTest`) and record the
  Lincheck tests as dynamic interleaving evidence, not mutation evidence.
- The tests compile at release 11 and run on JDK 25, the same as the rest of the
  test suite; the library bytecode remains release 8.
