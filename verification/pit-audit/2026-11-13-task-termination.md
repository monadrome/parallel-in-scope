# Task termination consumption PIT audit

The final focused mutation scope covered `ImmediateResult` and its canonical termination contract tests after the final test additions. The command used the build JDK, no PIT history input, and the explicit mutators required by the core policy, including `VOID_METHOD_CALLS`.

Command:

```text
mvn -o -Ppitest -pl core clean test-compile org.pitest:pitest-maven:1.30.1:mutationCoverage -DtargetClasses=io.github.monadrome.parallelinscope.ImmediateResult* -DtargetTests=io.github.monadrome.parallelinscope.ImmediateResult*,io.github.monadrome.parallelinscope.PublicApiSurfaceTest -Dmutators=CONDITIONALS_BOUNDARY,NEGATE_CONDITIONALS,INCREMENTS,INVERT_NEGS,NULL_RETURNS,FALSE_RETURNS,TRUE_RETURNS,PRIMITIVE_RETURNS,EMPTY_RETURNS,VOID_METHOD_CALLS
```

The raw report is `tmp/pit-audit-task-termination-final5/mutations.xml`; the complete console log is `tmp/pit-audit-task-termination-final5/pit.log`. The final result is 49 mutations: 49 `KILLED`, 0 `SURVIVED`, 0 `NO_COVERAGE`, and 0 timeouts. Earlier focused runs left two survivors; the added tests `freezingSuccessfulAndFailedTasksUsesCanonicalBranches` and `submissionFailureFreezingPreservesNotClaimedDisposition` closed those gaps.
