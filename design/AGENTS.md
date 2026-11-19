# Design Document Routes

Usage rule: **read this table first, match it against the current task by summary, load only the
documents it hits, and do not pre-read everything.**
Contract-class documents are written with implementation-binding force (MUST/MUST NOT/SHOULD)
and are the authoritative basis for this repository's current behavior.
When you need decision background, rejected alternatives, or consequences, read the ADRs linked
under the subject. The unreplaced parts of an `Accepted` ADR remain valid; a `Partially
superseded` ADR must be read together with its successors; a `Superseded` ADR serves only to
explain why the direction changed and must not be used as a basis for current implementation.
Boundary questions that need to be permanently closed to reopening may be held by an ADR; every
other current behavior, lifecycle, and concurrency invariant is held by the live contracts and
is not duplicated in ADRs. ADR headers carry `Stability` and `Category` lines: `Stability: beta`
means the subject it constrains has not shipped in a stable release; `Category: core` records
are background for core behavioral decisions whose current authority sits in the live
contracts; `non-core` records (artifact/repository boundaries, testing methods, documentation
practices, build tools, dependency policy) remain the authority for their question until
superseded. Retired ADRs keep no full text; their conclusions and rejection reasons are in
[decision-log.md](decision-log.md).

This directory keeps only the current view, in four classes: **live contracts** (tracked,
constrain current implementation), **in-flight proposals** (tracked, in `proposal/`, written but
not yet landed), **analysis drafts** (temporary inputs, deleted after harvest; committed to
`analysis/` only at the user's request, see maintenance principle 9), and **archived proposals**
(tracked, in `archive/proposal/`, dormant directions kept as full text for human reflection and
history analysis; agents MUST NOT load them by default, live documents MUST NOT link to or depend
on them, and their outbound links and claims are frozen as of the archive date). Landed proposals
are harvested into live contracts; conclusions and rejection reasons of rejected or superseded
directions go to [decision-log.md](decision-log.md). The repository keeps no full-text archive
beyond `archive/proposal/`: superseded full text, review transcripts, and verification records are
deleted once their conclusions and rejection reasons are harvested, and current documents must not
depend on them. **Do not cite commit hashes or line numbers to locate content.**

New capabilities and contract changes need no issue first: a proposal is written directly into
`proposal/`, added to the in-flight routes, and committed, with no distinction between draft,
accepted, or in-progress. At landing, merge the summary into the live contract, write the
historical back-reference, and delete the proposal and its route row in the same commit; details
are in [proposal-lifecycle.md](proposal-lifecycle.md). Whether to also open an issue for public
discussion is the maintainer's judgment (see Issue Tracking in the root
[AGENTS.md](../AGENTS.md)). Every proposal and PR touching public API or contracts must carry
concrete statements: the best user code writable before the change, the same code after, and the
failure mode removed; breaking changes additionally carry a migration path.

**This table has two routed sections, live contracts and in-flight proposals; analysis drafts and
archived proposals are not routed.** Related ADRs sit under their subject's contracts; no separate
ADR index or second navigation is built. CI's `scripts/check-design-routes.py` validates both
sections bidirectionally: table links must exist, and every tracked top-level live document and
every proposal in `proposal/` must have a row; `analysis/` and `archive/` are outside the check.
`scripts/check-proposal.py` additionally gates proposal identity, abstracts, and the
content bans; archived proposals are checked for identity only, `check-doc-refs.py` and
`check-local-links.py` skip them as sources, and `check-local-links.py` rejects links from live
documents into the archive. When picking up work, read the matching route first, then check
`git status --short design/` for uncommitted analysis inputs.

Adversarial-review findings and dispositions are staged at the end of the proposal document they
belong to during review; when the implementation is finalized and harvested, retained findings
merge into contract rules, and rejected or downgraded findings with their reasons condense into a
"rejected alternatives and reasons" or "claims rejected in review" section merged into the body or
[decision-log.md](decision-log.md); reverse verifications, PIT classifications, and round
transcripts are not archived — the commit message records the round's outcome.
Baseline reading for later review rounds: read the decision-log and the document body first.
The process is Adversarial Review in the root [AGENTS.md](../AGENTS.md).

## Document maintenance principles

When writing, changing, or deleting documents in this directory:

1. **Do not copy facts code can answer** — line numbers, class inventories, and method signatures
   drift inevitably. Reference mechanism names, symbol names, or section headings; never write
   "file:line", and never cite commit hashes: a hash goes stale on rebase or rewrite, and every
   document citing it must follow, forming chained edits. When a source is needed, name the
   date, the proposal number, or the mechanism.
2. **Single source of truth**: a fact is written fully in exactly one place; every other place
   may only point, never restate (positive example: P1–P4's full text lives in
   [interruption-contract.md](interruption-contract.md); the root AGENTS.md keeps only the
   summary). A pointer must read on its own: when the target holds a single sentence, write that
   sentence at the pointer instead of building a one-line jump chain of "see somewhere, some
   line".
3. **Supersession rewrites in place** — no "superseded" annotations in live documents; the old
   shape keeps no full text, and the reasons for abandonment are written per principle 4.
4. **Rejection records outvalue adoption records** — the reasons for an adoption can be inferred
   from code; the reasons for a rejection cannot and must be written down.
   Every rejection or dismissal carries at least one reason, so that a future un-rejection knows
   what must be overturned; "review-contrast value only" and "superseded" do not count as
   reasons. When a tracked proposal is rejected or superseded, the conclusion and rejection
   reasons go into [decision-log.md](decision-log.md) (when reopening must be closed, write an
   ADR per principle 10, with the log keeping a pointer) — that is a precondition for deleting
   the proposal file and its route row; it does not count as implementation landing and writes
   no landing back-reference into behavioral contracts.
5. **Lifecycle exits are one-way**: an in-flight proposal ends as a live contract (landed), as
   decision-log residue (rejected or superseded), or as an archived dormant file under
   `archive/proposal/`; the archive standard and re-entry are defined in
   [proposal-lifecycle.md](proposal-lifecycle.md). Material that no longer constrains
   implementation keeps no full text outside the archive and is deleted after harvest.
   The summary is written with the proposal and updated as the argument evolves; at landing, the
   edit merges it into the contract, writes the historical back-reference, and lands in the same
   commit as the implementation, the proposal's deletion, and the route row's removal. Harvesting
   keeps the core motivation, the design principles and invariants, and the rejected directions
   with reasons; core code may be kept (a state-machine skeleton or protocol order — code that
   expresses more precisely than prose); line numbers, class/method inventories, signature
   enumerations, test counts, command outputs, and multi-round review transcripts are deleted.
   **No "file:line" and no commit hashes** — reference mechanism names, symbol names, or section
   headings. Existing live documents are not forced to be rewritten immediately; condense them in
   passing the next time a related change touches them.
6. **Contract and implementation in the same commit** — the two check each other in review. A
   behavior-changing commit message or PR description lists the MUST/MUST NOT items it changes
   or adds, so no contract section drifts silently behind.
7. **The keep/drop criterion**: "if it were deleted, would decision quality drop?" Questions
   code, tests, or git can answer are negative assets when written down.
8. **Long-lived documents write invariants and mechanisms, not enumerations** (write the rules of
   the "token propagation tree", not a "public API inventory").
9. **Analysis drafts are inputs, not documents**: the conclusions of audits, probe records, and
   decision briefs are harvested into fixes, the decision log, BACKLOG, or in-flight proposals,
   then deleted. Confirm each item's landing place one by one; an untracked file has no in-repo
   copy — when unsure, keep it and say why. Analysis drafts are not committed on one's own
   initiative by default; they enter the repository only at the user's request: placed in
   `analysis/` (not routed, not constraining implementation), opened with an archival note —
   committed at the user's request, the date and purpose, and that it is not a current contract;
   it must pass `scripts/check-local-links.py`. Deleted after harvesting, with no full text kept
   elsewhere.
10. **Decision rationale lands in exactly one place by nature**, with pointers elsewhere:
    - **ADR** ([adr/](../adr/)): cross-module, hard-to-reverse, or reopening-closing decisions
      (artifact boundaries, core protocol orders, conclusions of the "do not reopen in reviews,
      defect triage, or refactoring proposals" kind). An ADR's decision is never rewritten: when
      the decision changes, write a successor ADR and mark the old one `Superseded`; outdated
      non-decision content is condensed and deleted per [adr/AGENTS.md](../adr/AGENTS.md). When
      an ADR's status or successor relationship changes, update the owning subject's route links
      in the same commit.
    - **A live contract body's "rejected alternatives" section**: trade-offs affecting a single
      contract, rewritten in place with it.
    - **[decision-log.md](decision-log.md)**: the residue of rejected, superseded, or harvested
      tracked proposals and analysis drafts (conclusions, rejection reasons, landing places); it
      prevents reopening only and does not define current behavior. When a log conclusion needs
      reopening closed, write an ADR at the same time; the log row keeps a one-sentence
      conclusion and the ADR pointer, with the full reasons only in the ADR.
    - Existing entries are not force-migrated: a log row already saying "do not reopen / do not
      re-raise" is promoted to an ADR the next time a related change touches it.
11. **Structural text may be gate-coupled**: check scripts can hardcode document structure —
    route-table section names, heading text, or table shapes (example:
    `scripts/check-design-routes.py` reads this file's `Live contracts` / `In-flight proposals`
    section headings). Before renaming a heading, restructuring a route table, or reshaping a
    gated table, grep `scripts/` and `scripts/tests/` for the literal and update every coupling
    in the same commit.

## Live contracts

### TaskGroup (independent parallel task groups)

| Document | Summary |
|---|---|
| [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md) | **Group declaration contract + review baseline**: the one-shot chained draft `group(name, timeout).par(key, par, taskCode)….runAll()`, the three-stage builder, compile-time type safety via reference-identity `TaskKey<T>`, value views without runtime type tokens, uniform admission and a single lifecycle; §9 holds the review and verification lessons |
| [synchronous-scope-exit.md](synchronous-scope-exit.md) | **Synchronous exit contract**: `runAll()`/`Par.map` execute synchronously on the calling thread to completion or failure, external root cancellation does not depend on a run handle, bounded cleanup waits, and late failures after cancellation get log levels by source |
| [task-group-api-and-options.md](task-group-api-and-options.md) | TaskGroup goals and non-goals, the Group/Batch semantic boundary, option types (`BatchOptions`/`TaskOptions` + `closeGrace`), result types (`TaskGroupResult`/`TaskTermination`) |
| [task-group-lifecycle.md](task-group-lifecycle.md) | TaskGroup object and context lifecycles (MemberState, TaskExecutionContext, SubmissionScope, TTL boundaries), structural parent/cancellation parent/deadline decoupling, the state machine and completion reasons, ParRuntime shutdown and resource ownership |
| [task-group-submission.md](task-group-submission.md) | The freeze and uniform submission contract, declaration-phase validation, executor rejection, and the reuse boundary of the two-phase submission kernel `TaskSubmissions` |
| [task-group-cancellation.md](task-group-cancellation.md) | TaskGroup cancellation token topology, direct member-cancel cascade, fail-fast, deadline computation and timers, member bind-skip policy, and the `originState()` attribution rules |
| [task-group-observability-and-verification.md](task-group-observability-and-verification.md) | TaskGroup member observation snapshots (terminal `TaskExitObservation`), group-level completion observation (synchronous return), Group-side executor-wait rules (graph model in task-graph.md), concurrency invariants, the required test matrix, and acceptance criteria |
| [task-group-terminal-combine.md](task-group-terminal-combine.md) | The optional single terminal combine task: full join, results, cancellation, observation, drawbacks, and non-goals |
| [batch-terminal-combine.md](batch-terminal-combine.md) | The `Par.mapAndCombine` batch terminal combine contract: prepared and bound in one admission, exactly-once success-gated submission, shared deadline/cancellation lifecycle, graph-edge honesty, and empty-input rejection |
| [batch-settle-attribution.md](batch-settle-attribution.md) | Batch elements and the terminal combine are attributed at **settle time**: the pin listener registered before bind, freezing waits for pins rather than the cleanup budget, an execution self-throwing CE/IE is `USER_FAILURE`, and the difference scope versus freeze-time classification with the accepted residual |
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
| [extension-and-wrapping.md](extension-and-wrapping.md) | The extension boundary contract: the only user extension point is task code itself (self-wrapping — no decorator SPI, with the deferral rationale and re-entry conditions), the three premises and three invariants (I1 structure / I2 synchronous dynamic scope / I3 detection only), the three objects users can wrap (thread pool/Callable/FutureTask), the 14 problem classes to avoid, self-wrapping rules (MDC/tracing/metrics/retry), and the contract and verification matrix |
| [interruption-contract.md](interruption-contract.md) | The cross-layer interruption-handling rules: the general rule and the three owner classes, P1–P4, the core design (cancellation is the semantics, caller interruption is not a cancellation signal plus user duties, borrowed-thread isolation as the only lossy exception, delivery stays in bounds and the delivery marker), per-role rules (including the checkpoint cancellation confirmation's source marker), and the verification matrix; the P1–P4 summary lives in the root AGENTS.md |
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
