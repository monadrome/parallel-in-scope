# Design Document Routes

Read the matching table rows only, not the whole directory. Live contracts govern current
implementation; in-flight proposals and analysis are inputs, not current contracts.
Read subject-linked ADRs for rationale and boundaries: current behavior contracts take
precedence over historical core ADRs, while non-core boundary/process ADRs remain authoritative
until superseded. Accepted ADRs' unreplaced parts remain valid; partially superseded records
must be read with successors; superseded records explain history, not current behavior.

Before editing design documentation, read
[documentation maintenance](../.agents/skills/project-delivery/references/documentation.md),
which owns the full document-maintenance principles, lifecycle, and gate-coupled structures.
In particular, principle 9 owns analysis-draft handling and principle 10 places decision
rationale in an ADR, the owning contract's rejected alternatives, or the decision log.
For new capabilities/contracts, also read `first-principles.md` and `proposal-lifecycle.md`;
follow the root task route's maintainer-direction gate before implementation.

Keep the `Live contracts` and `In-flight proposals` headings and table shapes aligned with
`scripts/check-design-routes.py`. No analysis or archive rows; do not load archived proposals
by default. Check `git status --short design/` for uncommitted analysis inputs.

## Live contracts

### TaskGroup (independent parallel task groups)

| Document | Summary |
|---|---|
| [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md) | **Group declaration contract + review baseline**: the one-shot chained draft `group(name, timeout).par(key, par, taskCode)….runAll()`, the three-stage builder, compile-time type safety via reference-identity `TaskKey<T>`, value views without runtime type tokens, uniform admission and a single lifecycle; §9 holds the review and verification lessons |
| [synchronous-scope-exit.md](synchronous-scope-exit.md) | **Synchronous exit contract**: `runAll()`/`Par.map` execute synchronously on the calling thread to completion or failure, external root cancellation does not depend on a run handle, bounded cleanup waits, and late failures after cancellation get log levels by source |
| [task-group-api-and-options.md](task-group-api-and-options.md) | TaskGroup goals and non-goals, the Group/Batch semantic boundary, option types (`BatchOptions`/`TaskOptions` + `closeGrace`), result types (`TaskGroupResult`/`TaskTermination`) |
| [task-group-lifecycle.md](task-group-lifecycle.md) | TaskGroup object and context lifecycles (MemberState, TaskExecutionContext, SubmissionScope, application-context boundaries), structural parent/cancellation parent/deadline decoupling, the state machine and completion reasons, ParRuntime shutdown and resource ownership |
| [task-group-submission.md](task-group-submission.md) | The freeze and uniform submission contract, declaration-phase validation, executor rejection, and the reuse boundary of the two-phase submission kernel `TaskSubmissions` |
| [task-group-cancellation.md](task-group-cancellation.md) | TaskGroup cancellation token topology, direct member-cancel cascade, fail-fast, deadline computation and timers, member bind-skip policy, and the `originState()` attribution rules |
| [task-group-observability-and-verification.md](task-group-observability-and-verification.md) | TaskGroup member observation snapshots (terminal `TaskExitObservation`), group-level completion observation (synchronous return), Group-side executor-wait rules (graph model in task-graph.md), concurrency invariants, the required test matrix, and acceptance criteria |
| [task-group-terminal-combine.md](task-group-terminal-combine.md) | The optional single terminal combine task: full join, results, cancellation, observation, drawbacks, and non-goals |
| [batch-terminal-combine.md](batch-terminal-combine.md) | The `Par.mapAndCombine` batch terminal combine contract: prepared and bound in one admission, exactly-once success-gated submission, shared deadline/cancellation lifecycle, graph-edge honesty, and empty-input rejection |
| [batch-settle-attribution.md](batch-settle-attribution.md) | Batch elements and the terminal combine are attributed at **settle time**: the pin listener registered before bind, freezing waits for pins rather than the cleanup budget, an execution self-throwing CE/IE is FAILED(USER_CODE_FAILURE), and the difference scope versus freeze-time classification with the accepted residual |
| [task-group-adopted-futures.md](task-group-adopted-futures.md) | **Group members adopting external futures contract**: `adopt` declares an in-flight `ListenableFuture` as a member; the group token cancels the source with the same cancellation intent, bind before connect, admission failure abandons the source; values enter ordinary combines and frozen results, with resources managed by the external pipeline or the recipient; a producer `Par` puts nested waits into the executor wait graph, complementing thread-pool starvation observation |
| [task-termination-and-scope.md](task-termination-and-scope.md) | `[beta]` **Task termination and scope reason contract**: the unified model of logical termination, execution claim, live state, post-exit observation, and the single primary cause of Batch/Group |

Related decisions (load only when the task touches the corresponding decision background or boundary):

- [ADR 0002](../adr/0002-separate-task-execution-and-future-lifecycle.md): separation of
  execution-side and result-side enhancement, completion-driven admission; placeholder
  admission, late bind, purge, and refill have been partially superseded by later decisions or
  contracts.
- [ADR 0005](../adr/0005-globalpar-executor-binding.md): executor topology, batch context, and
  cross-resource observation; current naming, options, and lifecycle follow this subject's live
  contracts (partially superseded).
- [ADR 0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md): bind before
  submit, prepared views, and borrowed-thread isolation.

### Observability and diagnostics

| Document | Summary |
|---|---|
| [task-graph.md](task-graph.md) | **Executor wait graph contract**: a runtime-owned, always-on, append-only executor waits-for graph; only synchronous waits form edges, both ends must be executors registered with this runtime, one admission's edges install atomically, the hot path is read-only, installation is CAS; reports carry cyclic strongly connected components and one concrete cycle, admission threads never log, and the semantics are "structural risk once observed"; rejected alternatives (request-level scopes, static queries and policy switches, enumerating all simple cycles, aging edges by time, rejecting on cycles) |
| [task-identity.md](task-identity.md) | `[beta]` **Task identity contract**: the disjoint unit/group-run identity spaces and their minting, the closed `TaskId` hierarchy as the single kind source, kind+label equality as reconciliation, ids as process-local diagnostics only; rejected alternatives (phantom tags + Kind enum, value-only single class, two independent top-level classes) |
| [thread-pool-starvation-observation.md](thread-pool-starvation-observation.md) | **Unstructured thread-pool observation contract**: explicit scopes, continuous saturation/backlog/no-progress sampling, risk signals and visibility states, executor ownership and shutdown order; never wraps or changes user tasks |
| [workload-fit-screening.md](workload-fit-screening.md) | **Workload-fit screening contract**: runtime-level always-on, timing only library-admitted task code whose entry or unit is declared `COMPUTE`; judged per entry in epochs of ten seconds of execution time, with a sticky issue and one `WARNING`; units are only attributed (at most 256 per entry) and judged for drift; cumulative counts read through `workloadFitReport()`; never wraps or changes user tasks, shares no state with starvation observation (ADR 0010) |

Related decisions:

- [ADR 0008](../adr/0008-tasktype-as-workload-fit-diagnostic.md): `TaskType` as a workload-fit
  diagnostic declaration; the runtime screening window is partially superseded by ADR 0010.
- [ADR 0010](../adr/0010-always-on-workload-fit-screening.md): runtime-level always-on,
  per-entry workload-fit screening.
- [ADR 0014](../adr/0014-logging-backend-boundary.md): core logging stays on JUL, and the
  application bridges to other logging backends through configuration or a bridge; logging
  remains best effort, with reliable diagnosis carried by the report APIs.

### Cancellation and queues

| Document | Summary |
|---|---|
| [cancellation-model.md](cancellation-model.md) | **Cancellation model overview (navigation, holds no rules)**: the six layers — decision/propagation/execution/cooperation/attribution/exit — with their authoritative contracts, the four cancellation sources and non-sources, the order one cancellation passes through the layers, token topologies per entry point, and easily confused facts; read this before changing cancellation-related mechanisms, then follow its index into the specific contracts |
| [cancellation-propagation.md](cancellation-propagation.md) | The Guava `ListenableFuture` cancellation-propagation mechanics (the direction differences of transform/catching/addCallback/aggregate futures), the semantics `CancellationToken.bind` depends on, and the source index |
| [sliding-window-refill.md](sliding-window-refill.md) | The sliding-window event-driven refill contract: the caller completes the initial window, completing threads claim the next element, the window cap and cancellation/failure handling, and the framework thread O(1) resource bound |

Related decisions:

- [ADR 0013](../adr/0013-standalone-blocking-queues-artifact.md): the standalone artifact and
  package boundary for general-purpose blocking queues; `SubmissionAwareBlockingQueue` remains
  in core.
- [ADR 0015](../adr/0015-blocking-queues-standalone-repository.md): blocking-queues sources
  moved out of this repository; queue sources, tests, and the [draining queue contract](https://github.com/monadrome/blocking-queues/blob/main/design/draining-queue-contract.md)
  belong to the standalone repository, with core consuming releases via
  `blocking-queues.version`.
- Retired ADRs (0003 automatic purge, 0004 abrupt-close, 0006 same-artifact release): their
  conclusions and rejection reasons are in the Queues section of
  [decision-log.md](decision-log.md); current rules follow execution-future-simplification,
  the standalone repository's draining queue contract, and ADR 0013/0015 respectively.

### Extension and wrapping

| Document | Summary |
|---|---|
| [extension-and-wrapping.md](extension-and-wrapping.md) | The extension boundary contract: task code itself is the only task-behavior extension point (self-wrapping — no decorator SPI, with the deferral rationale and re-entry conditions), while runtime-registered `ContextPropagator`s carry application context; the three premises and three invariants (I1 structure / I2 synchronous dynamic scope / I3 detection only), the three objects users can wrap (thread pool/Callable/FutureTask), the 14 problem classes to avoid, self-wrapping rules (MDC/tracing/metrics/retry), and the contract and verification matrix |
| [interruption-contract.md](interruption-contract.md) | The cross-layer interruption-handling rules: the general rule and the three owner classes, P1–P4, the core design (cancellation is the semantics, caller interruption is not a cancellation signal plus user duties, borrowed-thread isolation as the only lossy exception, delivery stays in bounds and the delivery marker), per-role rules (including the checkpoint cancellation confirmation's source marker), and the verification matrix; the P1–P4 summary lives in core/AGENTS.md |
| [runner-interrupt-delivery.md](runner-interrupt-delivery.md) | The runner exit and cancellation interrupt-delivery handshake, the volatile-ordering proof, and the regression-verification and independent-review records |
| [execution-future-simplification.md](execution-future-simplification.md) | The execution-phase non-blocking query contract, the cancellation/terminality race, the trade-offs and migration of moving automatic purge to an experimental branch, and the verification and review records |
| [confirmed-proposal-defects.md](confirmed-proposal-defects.md) | Current reproductions of proposal defects, their item-by-item retain/defer/reject dispositions, narrow fixes and independent verification records; the landing constraints for document recipes and release summaries |

### Repository process

| Document | Summary |
|---|---|
| [issue-automation.md](issue-automation.md) | The optional issue→triage→agent mechanism: the trust boundary (execution reads only trusted-identity text; no execution-capable agent on issue events), the label state machine and vetoes, triage managing only its own labels, selection and backpressure, the runner's enforced gates (clean export builds, reverse verification, PIT freshness, independent re-review, waiting for CI by SHA), merging into the dev line only, and the paths requiring human merging |
| [llm-review-benchmark.md](llm-review-benchmark.md) | **LLM review benchmark live contract**: daily `/multi-review` goes through the TypeScript CLI; immutable raw evidence, versioned clusters/verdicts/gold and recomputable metrics; the four-state verdict, open-world recall boundaries, the 51m layered budget, gold isolation and security boundaries; the rejected pure Skill/Python core/text dedup/single total score/first-phase service alternatives |
| [proposal-lifecycle.md](proposal-lifecycle.md) | Proposal tracking, abstracts, landing and rejection deletion preconditions, historical back-references, routes, and residual gates; read when maintaining proposals or harvesting analysis drafts |

Related decisions and engineering entries:

- [ADR 0001](../adr/0001-layered-cartesian-concurrency-testing.md): layered Cartesian
  concurrency testing; its `TaskType`-related parts are superseded by ADR 0008.
- [ADR 0009](../adr/0009-comment-minimization-and-placement.md): comment retention and
  placement principles; the implementation-note and local-proof placement parts are superseded
  by ADR 0012.
- [ADR 0011](../adr/0011-demo-and-verification-stay-on-junit-5.md): the JUnit major versions
  and test-runtime boundaries of the root tests versus the demo/verification consumers.
- [ADR 0012](../adr/0012-contract-and-implementation-comment-layers.md): the layering of call
  contracts, implementation notes, and local correctness proofs.
- Engineering rules and verification load by task: [root AGENTS.md](../AGENTS.md),
  [demo/AGENTS.md](../demo/AGENTS.md), [PIT](../verification/pit.md),
  [Lincheck](../verification/lincheck.md), [SpotBugs](../verification/spotbugs.md).

### Design philosophy and decision records

| Document | Summary |
|---|---|
| [first-principles.md](first-principles.md) | The project's axiom layer: the structured-concurrency starting points, the derived core decisions, and the evaluation criteria for new requirements; consult this first when evaluating any new feature/concept |
| [decision-log.md](decision-log.md) | The conclusions, rejection reasons, and current landing places of rejected, superseded, or harvested directions; check here before reopening an old direction |
| [docs/zh/design/philosophy.md](../docs/zh/design/philosophy.md) ([en](../docs/en/design/philosophy.md)) | The concurrency library's subtraction philosophy: core trade-offs and boundaries, for evaluating whether a new feature fits the project's positioning (a published site page, kept in place) |
| [docs/zh/design/idea-graveyard.md](../docs/zh/design/idea-graveyard.md) ([en](../docs/en/design/idea-graveyard.md)) | Capabilities explicitly not provided, with alternatives; check the rejection records before introducing a new feature (a published site page, kept in place) |

## In-flight proposals
| Proposal | Summary |
|---|---|
| [proposal/P0037-future-path-staged-outcome-api.md](proposal/P0037-future-path-staged-outcome-api.md) | Unify Future/Result result handling with `FuturePath`'s staged API, hiding state queries and guaranteeing exhaustive handling of the four terminal stages (success, user failure, submission rejection, cancellation) through compile-time chained stages |
| [proposal/P0036-complexity-review-signals.md](proposal/P0036-complexity-review-signals.md) | Complexity attention-routing evidence in gray evaluation; no quality score and no CI gate |
| [proposal/P0002-api-surface-reduction.md](proposal/P0002-api-surface-reduction.md) | Public API reduction candidates and later counter-evidence; re-verify the historical inventory by downstream capability and migration cost |
| [proposal/P0020-deadline-attribution-policy.md](proposal/P0020-deadline-attribution-policy.md) | The RUNNING-clock fallback policy choice remaining from P0015; does not override real events, first-wins, or the current settle attribution |
