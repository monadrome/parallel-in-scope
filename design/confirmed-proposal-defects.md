# Confirmed proposal defects

The implementation starts from `e5cb966` and retains only reachable failures or direct contradictions
of active contracts. Public call signatures stay unchanged. The best existing calls remain
`par.map(inputs, mapper, options)` and `runtime.group(...).par(...).runAll()`; they now return their
actual failures with an interrupted caller and report checkpoint-driven inherited timeouts correctly.
Invalid null mappers must be replaced with a real Function. Applications relying on the old accidental
ISE or MEMBER_CANCELLED attribution should inspect the actual result and caller interrupt status.

## Findings And Dispositions

| Source | Finding and evidence | Disposition |
|---|---|---|
| P0014 | Completed SettableFuture failure inspection and every batch/group/terminal freezing path on direct and pooled executors throws unrelated ISE for an interrupted caller | Retained: Futures.getDone preserves the business cause, flag, and existing settle pin |
| P0015 | Public runAll with a token-domain clock advanced inside a body and a pending timer returns member/terminal TIMEOUT but group MEMBER_CANCELLED | Retained: register the existing TIMEOUT listener before deciding whether to skip redundant bind; no new clock fallback or convergence policy |
| P0016 | Direct report listener Error leaves an ISSUE report published but diagnostic count zero | Retained: diagnose in publication finally, preserve Error propagation and reentrant publication order |
| P0016 | Existing graph-data test seam throws detection Error, then a report listener throws another Error; close loses the detection Error | Retained as a defensive internal test: primary detection Error and report cause remain, publication Error is suppressed |
| P0010 | Nonempty null-mapper map returns task USER_FAILURE; empty/null inputs accept the invalid mapper | Retained: reject the caller argument before admission, with ordered options/function validation |
| P0011 | Surefire selection by TaskBatchResultBodyCompletionTest reports no matching tests | Retained: align the class name with its existing file |
| P0011 | Actual bilingual client and fallback snippets fail Java 8 compilation (checked IOException method reference and missing lambda return) | Retained: translate checked IO failure in the Function and return success; interruption is restored and translated with its original cause |
| P0011 | Actual MDC recipe on a direct executor clears enclosing TTL and MDC fields | Retained: save/restore enclosing values, including the absent-context case |
| P0011 | IO prose promises interrupt stops blocking IO and socket read timeouts bound a whole call, contradicting interruption and client contracts | Retained as a documentation contradiction: explain interrupt requests and individual client waits |
| P0011 | Design prose promises no thread leaks or user executor ownership, and says an enqueued task cannot start before execute returns | Retained as documentation contradictions: match bounded cleanup, borrowed ownership, and the actual submitting-thread guard, including the documented direct-executor exception |
| P0012 | Reference-release test points at absent decision sections; remaining query prose assigns refusal ambiguously | Retained as documentation maintenance: point to the active execution-future contract and name the application's responsibility; clarify propagateCancellation does not restore arbitrary interruption |
| P0018 | Gate=yes marks failed, skipped, or cancelled deploy as Published in the actual summary script | Retained: distinguish the gate decision from the deploy outcome; only successful deployment claims publication |
| Independent spec review | P0011's graveyard still denies supported member deadlines and heterogeneous groups; report test-design describes mutable RUNNING results; interactive demo labels queued fail-fast siblings GROUP_CANCELLED | Retained: align bilingual policy boundaries, frozen reports, and demo labels with existing implementation and settlement tests |

Documentation probes compile the actual fenced examples with release 8 against this checkout and
installed TTL/SLF4J APIs. They execute the actual MDC recipe and exercise success, IO fallback,
interruption cause/flag preservation, and cancellation escape. Workflow probes execute the actual
summary script across gate/deploy outcomes; they do not deploy or verify a public download.

## Complete Proposal Inventory

| Proposal | Outcome and excluded direction |
|---|---|
| P0001 | Deferred: new request-cancellation input and ownership policy |
| P0002 | Deferred: breaking API reductions have downstream counterevidence and unresolved migration choices |
| P0003 | Already fixed in the baseline; no additional evidenced runtime defect |
| P0004 | Deferred: new shared-budget API and clock-domain choice |
| P0005 | Deferred: subtree confirmation is a new capability; current direct-body boundary is explicitly contracted |
| P0006 | Deferred: protocol/live-surface refactors lack a measured failure; its interruption concern is covered by P0014 |
| P0007 | Deferred: additional allocation is visible but no current performance regression is measured |
| P0008 | Deferred: new diagnostic identity/lifetime capability |
| P0009 | Deferred: new named terminal input API without a demonstrated failing use case |
| P0010 | Null mapper fixed; shutdown quick failure, wrapper identity, and resource measurements continue in P0021 |
| P0011 | Concrete recipes, IO prose, and test name fixed; broader history/navigation/ADR/changelog/BACKLOG maintenance continues in P0022 |
| P0012 | Concrete obsolete pointer and caller-responsibility prose fixed; general accessor/Javadoc/comment maintenance continues in P0024 without a demonstrated runtime failure |
| P0013 | Deferred: SDK/agent compatibility requires fixed-version external verification; manual Context recipe is already corrected |
| P0014 | Fully implemented, distilled into interruption-contract |
| P0015 | Confirmed propagation fixed; uncommitted RUNNING-clock attribution choice continues in P0020 |
| P0016 | Fully implemented, distilled into task-graph; nonreturning listeners remain outside the liveness promise |
| P0017 | Deferred: selection/support/public artifact claims need current external evidence or a policy choice |
| P0018 | Publication summary fixed; artifact identity/cache/source/consumer verification continues in P0023; cache replacement has no current reproduced mismatch |
| P0019 | Deferred: new exhaustive matching or unary convenience API; existing interruption defect fixed through P0014 |

## Review And Verification

Runtime regression tests were run red against baseline production before fixes, then green against
the fixes. The old unconditional-InterruptedException Future stub was replaced by a real Guava
future because it would hang Guava's uninterruptible reader instead of testing the actual defect.
No production concurrency primitive, public API, dependency, executor ownership, or timer topology
was added. Independent review, reverse verification, mutation classification, and final checks are
recorded here as they complete.

Independent review used separate read-only Standards and Spec seats on a different model. Standards
reported no actionable findings. Spec retained the omitted P0011 documentation contradictions above;
they were corrected and the follow-up review accepted them. It also retained the demo's two-second
timeout competing with its two-second depicted business failure; the budget is now five seconds.
Neither review found another production defect. A suggested direct-member-cancellation attribution
change was rejected using the existing contract and regression tests, as detailed below.

The first scoped PIT run completed with mutations.xml: 21 killed, two timed out, four survived.
All four survivors were retained as test gaps: removing binding restoration in each publication
branch was hidden by current()'s repair, and removing the detection-failure diagnostic was not
asserted. Raw TTL binding observations in publication listeners and an original-cause log assertion
close those gaps; independent follow-up review found no issue with them. Separate-thread test timeouts
bound the uninterruptible regression harnesses. Final PIT completed with 27/27 killed, zero surviving,
timed-out, or uncovered mutants. The explicit core mutator list includes VOID_METHOD_CALLS; scope
is exceptionNow, submitBatch, start and its listeners, publishReport, and logIssueQuietly. This is
method-scoped coverage, not a whole-class or whole-library score.

Final checks passed separately: targeted tests; Spotless apply and check; full library test (920
tests, no failures/errors/skips); design routes; local documentation links; English-documentation
language; 154 repository script tests; actual bilingual recipe probes with release-8 compilation
and context/interruption assertions; and all 15 gate/deploy summary combinations. The graveyard's
typed group example also compiles at release 8. These checks do not prove every repository example
or external HTTP client's behavior.

The existing external consumer was clean-compiled and ran nine tests on Corretto 1.8.0_412 using
the final locally installed 0.3.0-SNAPSHOT. Its actual Surefire classpath artifact SHA-256 matches
the built JAR. This is local-artifact verification, not Central download verification. Normal JAR,
sources, and Javadoc packaging succeeded; Javadoc emitted 100 existing accessor/queue documentation
warnings, which remain separate maintenance in P0024. No deploy or remote consumer verification was
run. The pre-commit read-only review and follow-up findings above are independent of these checks.

Reverse verification in an independent checkout removed only the four production fixes: all 17
defect assertions failed (20 tests total, seven failures and ten errors). Restoring the fixes made
the same 20 tests pass. The class-name selector separately failed before the rename and passed
after it. The verification checkout and document/workflow probe harness remain open for audit.

The inventory's additional claim that direct member cancellation must change GROUP_CANCELLED to
MEMBER_CANCELLED was rejected: memberCompleted commits group cancellation before cascading, and
the existing direct-cancellation and convergence tests require group GROUP_CANCELLED and initiating
member MEMBER_CANCELLED. The separate no-recorded-failure FAIL_FAST fallback is not that path.

## Publication Summary Contract

The summary MUST base a Published claim on the deploy step's successful outcome, MUST NOT infer
success from the gate decision, and MUST distinguish deployment failure/skipping from a skipped gate.
This does not claim that Central already serves a byte-identical artifact. Historical source:
`proposal:P0018`; remaining verification directions are in P0023.

## Documentation Recipe Contract

Examples MUST compile against the documented Function/Callable shape and follow interruption P1-P4.
TTL/MDC bridges MUST restore original context on the same thread, including nested and direct execution;
the established restore rule is in [extension-and-wrapping](extension-and-wrapping.md). IO teaching
MUST distinguish interrupt delivery, client wait timeouts, and the independent body-exit boundary.
Targeted test selection by the documented file's class name MUST address the intended test class.
Historical source: `proposal:P0011`; remaining maintenance directions are in P0022.

Concrete Javadoc responsibility and obsolete reference corrections came from `proposal:P0012`.
They change teaching only, preserving Checkpoints.remaining as a read-only query and
propagateCancellation's existing cancellation-only control behavior. General maintenance remains
in P0024.
