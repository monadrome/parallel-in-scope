# PIT verification

This is the execution and acceptance policy for mutation verification. Read it
when changing production behavior, changing tests that supply mutation evidence,
or investigating PIT results. Build configuration remains in [pom.xml](../pom.xml);
review and delivery rules remain in [AGENTS.md](../AGENTS.md#adversarial-review).

## Decide applicability and scope

Record the affected contract or failure mode, production classes to mutate, tests
allowed to kill the mutants, and why that scope covers the change. Use this table
before running PIT:

| Change | Required scope |
|---|---|
| Production behavior or a behavior-preserving refactor | Changed executable classes, including nested implementations, and tests exercising their affected contracts. |
| Shared cancellation, context, scheduling, executor binding, or lifecycle mechanism | Include affected collaborating production classes and caller-facing tests of the shared contract, even when those files did not change. |
| Tests or helpers that strengthen, replace, remove, or otherwise change mutation evidence | Re-run against the production classes whose evidence changed; include the remaining tests for that contract. |
| Dependency, JDK, compiler, test framework, or PIT configuration | Assess affected behavior and previous evidence. Run fresh analysis over affected classes; if the impact cannot be bounded, use the broader core scope described below. |
| Documentation, formatting, or a demonstrably non-executable annotation/comment change | PIT may be skipped with a concrete reason. Assess annotation changes for generated behavior or changed test discovery first. |
| Deleted class or code with no applicable mutation sites | Verify remaining callers and behavior with relevant tests; mutate affected executable collaborators where applicable. Report absent mutation sites explicitly. |

Expand the scope when a relevant path has `NO_COVERAGE`, a narrow test selection
omits a contract-level test, a survivor reveals another participating class, or
an incoming commit changes the same mechanism. Changing a collaborator's behavior
can invalidate evidence even when the mutated class itself is unchanged.

A broader core run selects the production classes directly in the root package
and their relevant root-package tests. Enumerate these from the source directories
without recursively adding the independent queue package. The POM's default
package wildcard can include subpackages: do not use it as an implicit core-only
scope. Queue changes follow their own [instructions](../src/main/java/io/github/monadrome/parallelinscope/queue/AGENTS.md);
unrelated changes do not require a queue sweep.

Use focused runs during iteration. Before accepting a cross-cutting change, run
the complete affected scope, in one run or documented batches. A maintainer may
request a broader audit; this policy does not introduce a scheduled CI job or
authorize a release build.

## Run against current code and tests

Run from the repository root with the build JDK and a green unmutated test suite.
Maven build, test, and PIT operations stay serial within one checkout. The PIT
goal does not compile sources; `test-compile` must precede it after any source
revert, restore, or edit. Use `clean test-compile` when deletions or renames could
leave stale class files.

Replace the example classes with the scope selected above. The trailing `*`
includes nested classes; comma-separated patterns allow multiple classes and
tests. Include every relevant contract test, not just the new regression test.

```bash
mvn -o -Ppitest test-compile org.pitest:pitest-maven:mutationCoverage \
  '-DtargetClasses=io.github.monadrome.parallelinscope.TouchedClass*' \
  '-DtargetTests=io.github.monadrome.parallelinscope.TouchedTest*,io.github.monadrome.parallelinscope.OtherContractTest*' \
  '-Dmutators=CONDITIONALS_BOUNDARY,NEGATE_CONDITIONALS,INCREMENTS,INVERT_NEGS,NULL_RETURNS,FALSE_RETURNS,TRUE_RETURNS,PRIMITIVE_RETURNS,EMPTY_RETURNS,VOID_METHOD_CALLS'
```

The explicit goal is required: `mvn -Ppitest` alone runs no mutation analysis.
The command is offline-safe when dependencies are already installed.
`targetClasses`, `targetTests`, and `mutators` are POM properties so command-line
overrides work; inline plugin values would shadow those overrides.

Core runs MUST include `VOID_METHOD_CALLS`: deleting an interrupt, body-exit
notification, or resource release can expose a contract failure. The POM default
omits it to reduce equivalent queue mutants; that default is insufficient for a
core run. Document any additional exclusions and the behavior they leave unchecked.

Accept results only for the final relevant production code, tests, helpers, and
configuration. Re-run affected scope after any of these inputs changes, including
after rebase; an unchanged local patch alone does not establish freshness.
History-based reuse is optional iteration feedback, not final evidence: a final
verification run MUST use no history input. This avoids relying on incomplete
dependency invalidation.

PIT writes `target/pit-reports/mutations.xml` and HTML reports. Preserve the XML
and logs for each batch before another run overwrites them. Record the source
revision and any uncommitted input changes, command, JDK, scope, mutators, and
exclusions beside the results; report timestamps alone do not prove freshness.

## Interpret every raw status

Keep PIT's raw statuses and counts. MUST NOT relabel timeouts or memory failures
as `KILLED`, or infer a pass from Maven's exit status or a headline mutation score.

| Raw status | Required interpretation and action |
|---|---|
| `KILLED` | A test detected the mutation. Inspect the assertion or failure when using it as evidence for a particular contract; an incidental failure may not protect that contract. |
| `SURVIVED` | A test ran but did not detect the mutation. Classify it using the dispositions below. |
| `NO_COVERAGE` | No selected test exercised the site. Check test selection first, then add a missing scenario or justify unreachable behavior. It is a gap until resolved. |
| `TIMED_OUT` | Inconclusive until logs and a focused reproduction attribute the timeout. A reproducible mutation-induced hang can support a liveness finding; retain the raw status and explain the evidence. |
| `MEMORY_ERROR` | Inconclusive until investigated. Distinguish mutation-induced resource growth from a worker or environment limit; retain the raw status. |
| `NON_VIABLE` | The mutant could not load or execute as valid bytecode. Report separately; it supplies no test-strength evidence. Investigate if it affects the claimed scope. |
| `RUN_ERROR`, unknown, or other incomplete status | Investigate the worker/tool failure; affected evidence remains inconclusive. |

Missing XML, an interrupted or partial run, zero evaluated mutants for a claimed
executable scope, or an unaccounted expected class cannot establish a pass. Correct
the scope or tool failure and rerun; record genuinely absent mutation sites as a
reasoned skip. Retry failures only after addressing their cause, or once for a
known transient, as AGENTS.md requires.

## Dispositions and completion

Every `SURVIVED` and `NO_COVERAGE` entry MUST have a disposition with evidence.
Identify it by class, method, mutator, mutation description, and the matching XML
entry. Group entries only when the same argument applies to every member. Use
symbols and report artifacts instead of source-path line numbers in durable prose.

- **Missing scenario or weak assertion:** add a contract-level test and rerun.
  Verify the intended mutant is now detected. For a regression test, also perform
  the fix-only reverse verification required by AGENTS.md.
- **Equivalent:** show why original and mutated code have identical observable
  behavior for all contract-permitted inputs and relevant interleavings. One green
  example, several agreeing reviewers, or a high total score is not that proof.
- **Unreachable:** name the enforcing invariant and show why the site cannot be
  reached through supported paths. Treat reachable error, cancellation, and
  cleanup paths as testable behavior rather than excluding them for convenience.
- **Unresolved gap or tool limitation:** retain the affected contract, evidence,
  impact, and next action. A confirmed defect or ineffective test blocks
  completion. A residual nonblocking limitation needs an explicit maintainer
  disposition; it is not a silent pass or an equivalent mutant.

Do not set a universal percentage target or suppress a mutant merely to raise the
score. A passing verification requires justified scope, current complete evidence,
no unresolved blocking gap, and evidence-backed dispositions for all exceptions.
An unresolved execution failure is `blocked` or `inconclusive`, with the affected
scope named. A reasoned non-applicable check is `skipped`.

Include this compact record in the PR, owning design document, or commit message:

- Applicability and contract/failure mode.
- Production and test scope, expansion rationale, mutators, exclusions, command,
  source/test/configuration revision, build JDK, and confirmation of no history input.
- XML/log locations and all raw status counts, separately for each batch.
- Dispositions, supporting test/proof, and the actual effect of each fix.
- Overall `pass`, `fail`, `blocked`, `inconclusive`, or `skipped`, with limitations.

The [issue runner](../design/issue-automation.md#6-执行与门禁) checks report
presence, timestamp freshness, and raw result states; it does not prove scope
coverage, equivalence, or disposition quality. Its gate may count `TIMED_OUT` and
`MEMORY_ERROR` as detected according to PIT conventions. That machine gate is not
an all-`KILLED` result or a substitute for this policy's review. Runner rebase
exemptions describe its current automation, not permission to claim stale
evidence as final verification.
