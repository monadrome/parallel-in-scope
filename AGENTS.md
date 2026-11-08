# AGENTS.md

## Project

**parallel-in-scope** is a structured-concurrency toolkit for Java 8+ built on
Guava `ListenableFuture` and Alibaba `TransmittableThreadLocal`.

- Java release 8 for the library, release 11 for tests; JUnit 6 via Surefire on the
  build JDK 25 (the library bytecode stays release 8; the demo and the verification
  consumer stay on JUnit 5 for their Java 8 runtime).
- Dependency and plugin versions live in `pom.xml`.

## Current Development Line

- All new work targets `dev/v0.5.0`, including agent pull requests. Do not
  commit to `main` or target it with development pull requests; release
  integration is a separate maintainer action.
- The library, demo, and default verification consumer track `0.5.0-SNAPSHOT`.
  Keep stable installation examples on the latest published release, `0.4.1`.
- Preserve the snapshot publishing policy: pushes to `dev/v0.5.0`, the daily
  scheduled Snapshot run, and manual Snapshot runs consult Central metadata and
  publish only when no snapshot exists or the branch tip's UTC date is later
  than the last publication's UTC date. Verify the library and demo before
  deploying. The schedule lives in `.github/workflows/snapshot.yml`; GitHub
  fires `schedule` only from the default branch's copy of that file, so opening
  a development line or changing the workflow includes syncing that copy on
  `main` -- a maintainer action outside the development line. A matching
  `v0.5.0-SNAPSHOT` tag remains the manual publication path that bypasses this
  date gate; snapshot tags never create a GitHub Release.
- Dependabot reads `.github/dependabot.yml` from the default branch. Sync that
  file to `main` as a maintainer action; its update entries target `dev/v0.5.0`.

## Commands

Run from the repository root.

```bash
mvn test -Dtest='ClassName#methodName' # targeted test; replace class and method
mvn test                              # all tests
mvn spotless:apply                    # format Java sources
mvn spotless:check                    # verify formatting without writing; CI runs this
mvn clean verify                      # tests + package checks; not a release build
mvn -Pspotbugs -Dmaven.test.skip=true verify  # opt-in SpotBugs High gate; not in default CI
python3 scripts/check-doc-refs.py     # documentation must not depend on hashes or line numbers
python3 scripts/check-java-conventions.py  # new library-source convention violations; see verification/java-conventions.md
python3 scripts/refactoring-review.py plan  # refactoring-review gate and detector plan for the committed change
python3 scripts/process-metrics.py record --topic <topic> --route <route>  # delivery record; see verification/delivery-ledger.md
python3 scripts/process-metrics.py report  # aggregate delivery records: review rounds, rule findings, gate failures
```

## Task Routes

Classify the request before following the rest of this file. The table selects
the process; the named sections and documents own the rules. Routes are
cumulative: when two rows apply, follow both and let the more restrictive gate
win. Classify by the smallest change that satisfies the request, not by the
largest scope it could grow into.

| Request shape | Route | Do not |
|---|---|---|
| Mechanical, non-behavioral edit in one file: typo, comment, local rename, link | Make the edit, run the narrowest relevant check, commit | Write a proposal or run adversarial review |
| Reproducible behavior defect | Reproduce the failure first; fix the root cause; add a regression test and reverse-verify it | Fix by inspection, or claim completion without a failing case |
| Public API, documented contract, or new mechanism | Read `design/first-principles.md` and the owning contract; write or update the `design/proposal/` entry; stop for the maintainer's direction | Implement before the direction is settled |
| Cancellation, interruption, deadline, visibility, context propagation, executor binding, or queue behavior | State the invariants and interleavings first; add targeted tests; run `python3 scripts/check-race-claims.py` for registered ordering claims; run the full suite and adversarial review | Treat compilation, one green test, or a timeout-free run as proof |
| Documentation or site change only | Check the diff, referenced paths, and cited commands; run `python3 scripts/check-doc-refs.py` | Run Java tests or `spotless` |
| Dependency or build change | Patch/minor upgrade: run the `dependency-audit` skill, `mvn test`, and the `runtime-consumer` matrix locally; ask before adding a dependency or taking a major upgrade | Skip the audit or the runtime compatibility check |
| Release, snapshot, merge into `main`, or issue-runner delivery | Maintainer action per Current Development Line, or `design/issue-automation.md` for the runner | Initiate, push, or merge outside that route |

If the request crosses into a gated route mid-task, stop and reclassify instead
of finishing under the old route. A request for analysis authorizes the analysis,
not implementation.

## Architecture

Base package: `io.github.monadrome.parallelinscope`.

| Package | Responsibility |
|---|---|
| root package | Public API and callbacks plus the package-private execution kernel |
| external `blocking-queues` artifact | Independent general-purpose queue implementations |

The root package deliberately co-locates the public API with package-private cancellation,
context, graph, and scheduling implementation. This is the Java 8 encapsulation boundary: do not
reintroduce public bridge types or conceptual subpackages merely to categorize files.

The standalone `blocking-queues` repository publishes the general-purpose queue implementations as
`io.github.monadrome:blocking-queues`. Its artifact boundary is defined by
`adr/0013-standalone-blocking-queues-artifact.md`; it must not depend on the core module. The
`SubmissionAwareBlockingQueue` adapter remains in the core root package because its admission
semantics read `SubmissionScope` and `MultiTaskContext`.

The external queue artifact is outside the core mechanism and outside default reading scope: skip
it in repository-wide sweeps unless the task names queue behavior. Its tests run in the standalone
repository.

Two invariants to respect:

- Parent propagation is wired in the `CancellationToken` constructor;
  `CancellationToken.bind()` wires the deadline timer and fail-fast. Every
  entry point binds **before** submitting — `Par.submit`, `Par.map`, and
  `TaskGroup.start` alike — because submission can run an execution on the
  submitting thread, and the deadline is that path's only source of liveness.
  The deadline itself lives in the token (min of the requested deadline and
  the parent's).
- Every element's caller-visible `Task` view wraps its prepared
  `ExecutionPhaseHintFuture` from creation on, whether or not the element has
  reached a free parallelism slot. There are no placeholders and no later
  bind step: `SlidingWindowSubmitter.viewsFor()` builds the views, the caller
  binds them, and `submitAll()` only decides when each prepared future enters
  the pool. Cancelling a view therefore reaches the thread running its execution.

Interruption rules (P1-P4): never swallow `InterruptedException`; rethrow it,
restore the flag, or translate with the original cause and restore the flag.
The thread owner chooses post-interruption policy, so blocking APIs propagate
`InterruptedException`. Clear a flag only immediately before throwing that
exception or when the method is the documented sole consumer of the signal;
otherwise inspect with `isInterrupted()`. Read-only status methods must not
change their result or throw merely because the calling thread is interrupted.
Exception: synchronous `Par.map` / group `runAll` deliberately wait uninterruptibly
and restore the flag, including their bounded cleanup waits. Caller interruption
does not cancel these executions. `ParRuntime.awaitQuiescence` remains interruptible.

## Design Decisions

Optimize for project-wide quality: correctness, API coherence, maintainability,
and the user experience across implementation, tests, and documentation. Before
choosing a local solution, trace its effects through affected callers and shared
mechanisms. Count complexity moved to other components or users as a cost;
a smaller diff, simpler method, or passing targeted test alone does not establish
an improvement. Prefer solutions that improve the whole affected path and explain
material tradeoffs. Keep this assessment within the existing reading boundaries
and the implementation within the authorized scope; report unrelated improvements
separately.

Optimize for structured concurrency and user safety and convenience. Prefer
parameterizing existing mechanisms over adding concepts. For a new capability,
API, or mechanism, use the evaluation checklist in `design/first-principles.md`
before choosing an implementation. For fixes to existing behavior, consult the
relevant contract through the document routes below.

Prefer established higher-level concurrency abstractions over direct use of
low-level primitives such as `Thread.interrupt()` and `synchronized`, whose
protocols are easy to get wrong. Before introducing or changing a mechanism
that directly uses such primitives, explain why the higher-level alternatives
are insufficient, present the proposed mechanism and its risks, and obtain
explicit human confirmation before implementing it. The ordered ladder of
mechanisms, from no sharing down to hand-built protocols, lives in
`refactoring-review/lenses/concurrency.md`.

## Key Conventions

- Java 8 APIs only in `core/src/main/java`; the standalone queue artifact has its own repository
  and build rules.
- Declaration Javadoc states the contract its reader can rely on: purpose,
  inputs and results, nullability, exceptions, concurrency, cancellation, and
  lifecycle. This includes internal declarations. Public Javadoc must make sense
  without reading package-private types; retain observable ordering and
  resource-lifetime guarantees in the contract.
- Put maintainer-only algorithms and local protocol explanations in ordinary
  `/* ... */` comments inside the owning class or private mechanism. Use
  `@implNote` only for implementation details useful in generated API docs,
  after registering the tag in the Javadoc build. `@implSpec` states requirements
  on implementers or overrides, not a private algorithm.
- Keep statement-level ordering, race, lifetime, and dependency proofs beside
  the statements they justify. Durable decisions and cross-method invariants
  belong in the existing design/ADR source of truth; local notes explain their
  application rather than copy the rationale. See
  `adr/0012-contract-and-implementation-comment-layers.md` when choosing a layer.
- Express routine behavior through names, structure, and tests. Before moving
  or deleting a comment, preserve each distinct valid claim and correct claims
  that no longer match the code. Do not use comment count, density, or length
  as a deletion criterion or add mechanical gates for them. These rules apply
  to library, tests, and demo; the standalone queue module's JDK-port parity comments
  remain governed by the standalone `blocking-queues` repository's `AGENTS.md`.
- Use American English in code and prose, except the doubled-`l` family:
  `cancelled`, `cancelling`, `canceller`, `cancellable`, `signalling`, `labelled`.
  Respelled public members need changelog and migration-guide entries. Preserve
  third-party names and original spellings in dated records (released changelogs,
  `migration-v0.2` tables, accepted ADRs).
- Accessors use the bare `x()` style everywhere (`token.state()`, `event.result()`);
  do not introduce `getX()`/`isX()` forms. Methods implementing JDK or
  third-party contracts keep their mandated names (`ExecutorService.isShutdown()`,
  `Monitor.Guard.isSatisfied()`).
- Every package has `package-info.java` with JSpecify `@NullMarked`;
  annotate only exceptions with `org.jspecify.annotations.Nullable`
  (TYPE_USE position, compile scope). NullAway enforces the annotations at
  compile time via Error Prone; the build requires JDK 25 (LTS) while the
  bytecode target stays at release 8.
- Do not annotate local variables with `@Nullable`; NullAway tracks local
  nullness through control flow. `@Nullable` on nested type uses, such as a
  collection's element type, remains valid.
- Logging goes through JUL (`java.util.logging.Logger`).
- Runtime checks follow Guava's conditional-failure taxonomy: caller violations
  use `Preconditions.checkArgument` for arguments, `checkState` for state, and
  `checkNotNull` for nulls (prefer over `Objects.requireNonNull`); dependency and
  internal invariants use `Verify.verify`/`verifyNotNull`; platform
  impossibilities throw `AssertionError`.
  Keep hand-built checks for ordered validation, causes, custom exceptions,
  intended JDK exception types, or expensive message arguments. Migrate existing
  checks only when touched.
- Exception messages are lowercase sentence fragments without a trailing
  period; they interpolate the offending value or id and name the actionable
  alternative when one exists. A leading code identifier keeps its exact casing
  (`"ParRuntime is closed"`). Message templates use
  `%s` only (Guava `lenientFormat` supports nothing else).
- Suffixes: `Scope` is a lifecycle scope (public scopes are closeable;
  package-private scopes may be stack-installed); `Context` is a data carrier;
  `Step` is a one-shot builder stage (`GroupStart` opens the chain); `Id` is an
  immutable logical-entry identifier.
- Public APIs and SPI may break between `0.x` releases without compatibility
  shims for meaningful improvements with documented rationale.
- For public API renames or signature changes, update the implementation,
  tests, user documentation, and migration notes as one change. Keep the
  rationale explicit so future maintainers can distinguish intentional API
  evolution from accidental breakage.

## Documentation Audiences

Documentation has two top-level audiences; keep their entry points and responsibilities distinct:

- **User-facing documentation** explains supported usage and observable behavior: `README*.md`,
  `docs/`, `demo/`, `integrations/`, migration guides, and public contributor instructions.
  `docs/{en,zh}/quickstart.md` is the canonical development-line quickstart. The README provides
  a minimal stable-release example; demo articles provide detailed examples rather than a second
  quickstart.
- **Agent-facing documentation** guides implementation and maintenance: `AGENTS.md`, `design/`,
  `adr/`, and verification instructions. `design/` owns current contracts, proposals, and review
  history; `adr/` owns durable decisions and their rationale. Users must not need these internal
  documents to use the public API.

Group related contracts, ADRs, and history by subject in their existing routes where useful. Link
related documents by subject without duplicating implementation rationale. Subject grouping is a
navigation aid, not a third documentation category or a replacement for the
lifecycle rules in `design/AGENTS.md` and `adr/AGENTS.md`.

## Documentation References

Documentation must stay readable and correct without the Git object database:

- Keep only the current view. The repository keeps no full-text archive of
  retired proposals, harvested analyses, review transcripts, or superseded ADR
  wording. Before deleting such material, distill its conclusions into the owning
  contract, ADR, or `design/decision-log.md`, with at least one reason for every
  rejected or superseded direction so the rejection can later be revisited.
- Do not cite commit hashes in documentation, not even as provenance: a hash
  changes when history is rewritten and forces chained edits in every document
  that cites it. Name the date, the proposal number (`proposal:P<NNNN>`), the
  release, or the mechanism instead. Never point at git history to retrieve
  content.
- Never identify code or prose by a line number appended to a path. Cite the
  symbol, the section heading, or the file path instead. Line numbers drift and
  make the document wrong by default.
- Write a pointer so that it reads on its own: when the target holds a single
  sentence, write that sentence at the pointer instead of a jump to it.
- `scripts/check-doc-refs.py` enforces these rules on live documents. Until the
  maintainer rewrites them, `BACKLOG.md`, `todo.md` (scratch), analysis inputs
  under `design/analysis/`, the changelog, and the dated records under
  `verification/` and `tools/` are exempt and may still carry hashes; do not
  add new ones. Most hashes they cite are already unreachable from any branch.

## Autonomous Completion

- A request to change something authorizes the whole route this file prescribes:
  implementation, tests, formatting, documentation and migration notes, the
  review-and-fix loop, commit, and delivery. Do not wait for confirmation between
  these steps. Stop only for:
  - a direction the request leaves undecided: public API shape, contract
    semantics, a concurrency protocol, or a large refactor it did not name;
  - an action that Permissions or Design Decisions says to confirm first, or an
    outward action this file does not prescribe (a release, a merge into
    `main`, a message to people outside the session);
  - a blocker you cannot remove.
- Finish all work that does not depend on the open point first, then ask once:
  list each open decision with your recommendation and what it changes.
- Settle implementation choices yourself instead of presenting options. Design
  for the best end state; in `0.x`, compatibility and migration cost are not
  selection criteria, and migration notes record the break.
- A request for analysis authorizes the analysis, not applying its proposals.
- Start new work from the freshly fetched development line named under Current
  Development Line, not from a stale local branch or `main`.
- Another agent may share this checkout. Before the first edit and before each
  commit, look for its signs: modified or untracked files you did not create,
  local commits ahead of the fetched development line that are not yours, or a
  branch or HEAD that moved under you. When you find any, move your work to a
  worktree of your own instead of working around it:
  `git worktree add -b <topic> .worktrees/<topic> origin/dev/vX.Y.Z`. Carry over
  only your own changes, as a patch of your paths, then return those paths in
  the shared checkout to their committed state. Verify, commit, and deliver to
  the development line from the worktree. Do not commit, stash, reset, rebase,
  or switch branches in the shared checkout, and never push the other agent's
  commits. After delivery, remove the worktree and delete its branch.
- Retry a failure only after addressing its cause, or once when it is a known
  transient; report a failure that repeats unchanged. When an environment
  denies an action this file prescribes, leave the exact command in the report
  instead of working around the denial.
- When the user corrects the same behavior twice, propose the rule that would
  have prevented it. Changes to permissions or gates take effect only after a
  maintainer approves them; until then they do not widen the task that proposed
  them.

## Verification And Completion

- Behavior-changing commits list the contract MUST/MUST NOT items they add or
  change in the commit message or PR description, so no contract section is
  silently left behind.
- Add or update tests when changing cancellation, context propagation, executor
  binding, or queue behavior.
- For code changes, use targeted tests while iterating, run `mvn spotless:apply`,
  and finish with a green `mvn test`. A full suite already passing on the final
  code satisfies the targeted-test requirement; do not rerun tests solely to
  satisfy another workflow step.
- For documentation-only changes, check the diff, referenced paths, and any
  commands against their source configuration; Java tests and formatting are
  unnecessary unless executable code or build behavior also changes.
- Complete applicable local verification before the initial commit. Fix failures
  caused by the change and rerun affected checks without pausing for review of
  the first implementation. Report unrelated failures or blockers; do not claim
  completion while required checks are blocked.
- For every code change, run `/refactoring-review`
  (`.claude/skills/refactoring-review/SKILL.md`) after the behavior commit and
  before Adversarial Review or delivery. `scripts/refactoring-review.py plan`
  decides whether a diff counts (documentation-only and comment-only diffs do
  not) and how many read-only detectors cover which lenses; rules are routed
  by `refactoring-review/AGENTS.md`. Accepted refactors land as separate
  `refactor:` commits that leave test assertions unchanged; it runs once per
  change and never on its own refactor commits.
- Report each check separately and honestly: behavior tests, external consumer,
  independent review, and mutation coverage are distinct facts. Give each check
  one status (pass, fail, blocked, inconclusive, or skipped) and a reason for
  every status except pass. A PIT run that started but timed out, or
  produced no `mutations.xml`, is not a pass; never describe a started or partial
  check as passing. Run Maven build/test/PIT serially within one checkout;
  independent verification checkouts may run in parallel.
- Mockito's inline mock maker self-attaches its agent. On the JDK 25 build this
  currently succeeds with only a deprecation warning and the Mockito test classes
  pass without any javaagent, but self-attach is deprecated and will stop working
  on a future JDK, so treat an attach error as an environment-specific condition
  rather than a code defect. If the Mockito tests do fail with an attach error,
  rerun with the jar matching the `mockito.version` in `pom.xml` passed explicitly.
  The current POM value is `5.24.0`:

  ```bash
  mvn -o test -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.24.0/mockito-core-5.24.0.jar
  ```

  The `$HOME` form resolves on other checkouts without hardcoding a user path.
- Record every delivery except a mechanical edit in the delivery ledger
  (`verification/delivery-ledger.md`): after the change's last commit, run
  `scripts/process-metrics.py record` with the change's review runs, PIT
  reports, local gate failures, maintainer asks, and user corrections, and
  commit the record alone as `chore: record delivery <topic>`. Keep a count of
  failed required checks and of stops for a decision while you work; record
  unknown usage as unknown instead of estimating it.
- After verification, commit and deliver to the development line: rebase onto
  its freshly fetched tip, rerun the checks whose inputs the incoming commits
  changed, and fast-forward push it; when the push is rejected, repeat from the
  rebase. Push a separate branch only when the user names one or an open pull
  request already carries the work, and then update that pull request's
  description and verification to match its final commits. Contributors
  without write access open a pull request as `CONTRIBUTING.md` describes.
- Delivery includes documentation maintenance and in-flight proposals. Proposals
  are tracked under `design/proposal/`; their lifecycle is defined in
  `design/proposal-lifecycle.md`. Analysis drafts are temporary inputs: do not
  commit them on your own initiative; commit one only when the user asks, under
  `design/analysis/` per `design/AGENTS.md` principle 9. Harvest their
  conclusions before deleting them either way.
- Unattended issue-runner sessions (`scripts/issue-agent.py`) override the push
  rule above: the agent commits locally only, and the runner pushes an
  `auto/issue-*` branch, opens a pull request into the current `dev/vX.Y.Z`
  line, and merges it only after its own gates. Agent pull requests never
  target `main`; see `design/issue-automation.md`.
- Commit only this change's files; leave unrelated modifications and staged
  changes uncommitted. Use Conventional Commits with a lowercase summary
  (`feat:`/`fix:`/`refactor:`/`docs:`/`test:`).

## Adversarial Review

After the initial commit of public API, documented-contract, or
concurrency-sensitive changes, run an independent review with its own budget.
Use the commit diff as the review baseline so review and fixes can continue in
later sessions:

- Use a different model or harness than the implementer. Run it through
  `/multi-review` (`.claude/skills/multi-review/SKILL.md`) on the commit range,
  which keeps reviewers read-only on a snapshot; when no reviewer CLI can run,
  use a fresh-context agent on another model in a clone without a remote. The
  reviewer must not edit files. Name attack surfaces: interleavings, contract
  versus implementation, test quality, Java 8, and generics.
- When refactoring-review ran, review the range from its base, including its
  `refactor:` commits. Read its report as settled findings, and add behavior
  preservation of each refactor commit and each applied `T1` item as attack
  surfaces.
- A review counts only when its output or transcript shows the reviewer read
  the changed files. A timeout, a failed run, or a reviewer that cannot read
  the files is no review: rerun it once, then record the review as blocked,
  never as clean.
- Diff each contract MUST/MUST NOT named by the change against the
  implementation line by line; catching drift is a post-commit review duty,
  not a periodic audit.
- Address confirmed findings in verified follow-up commits. Later rounds review
  those commit diffs, first reading settled findings as the baseline, then
  looking beyond them. Use a fresh seat when context is nearly full or the
  reviewer starts agreeing with itself. Stop when a round confirms no new
  blocking finding (a defect, a contract violation, or an ineffective test) or
  after three rounds, and record what remains open with its disposition.
- Reproduce every finding against the working tree; retain, downgrade, or reject
  it explicitly. Record findings, dispositions (including rejections), and each
  fix's actual effect in the change's `design/` document, or in the fix commit
  message when no `design/` document covers the change. Leave the review
  workspace or run directory in place as an audit trail.
- Reverse-verify every regression test: temporarily revert only the fix, observe
  the new test fail, then restore the fix.
- For executable production changes (including refactors), mutation-relevant
  test/helper changes, toolchain or build/PIT configuration changes, or
  investigation of a PIT result, read [PIT verification](verification/pit.md)
  before selecting scope or reporting completion. It owns applicability,
  commands, scope expansion, freshness, dispositions, and acceptance criteria.
  PIT needs no permission and writes build outputs under `target/`.

Historical examples and results already live in
`design/group-one-shot-api-refactor-codex.md` section 9; consult them when
investigating review or test blind spots.

## Issue Tracking

- Maintainer work needs no issue. Decisions live in the implementing PR and,
  when extended reasoning is needed, a `design/` document.
- Every public API or documented-contract proposal and PR must show the best
  user code today, the same code after the change, and the failure mode removed.
  Breaking changes must name the migration path; PRs missing this rationale
  are not mergeable.
- Open issues for public input, downstream discoverability, or deferred work.
  Use `.github/ISSUE_TEMPLATE/`: `design_proposal.yml` for capabilities/contracts,
  `bug_report.yml` for defects, `documentation.yml` for guides/Javadoc.
- External contributors file an issue first except for the trivial changes
  listed in `CONTRIBUTING.md`.
- PRs implementing issues link them with `Closes #NN` / `Refs #NN`; otherwise
  the PR description must be self-contained. Before opening or updating an
  issue, read `CONTRIBUTING.md` section "Issue maintenance" for ownership,
  direction updates, and milestone rules.
- Issues may opt into the agent runner. Triage suggests `priority/*`, a type,
  and `agent/eligible` or `needs-decision`; a maintainer's `agent/ready`
  authorizes a run and `needs-decision` vetoes one. The runner is optional:
  nothing in the normal flow depends on it. Its trust boundary, labels, and
  gates are in `design/issue-automation.md`.

## Permissions

Authorized without asking:

- Local tests, Java formatting, and the repository checks, with the existing
  toolchain and installed dependencies.
- Deleting tracked files the change retires or replaces: a landed or rejected
  proposal, a harvested draft under `design/analysis/`, superseded code, a
  rename's source. Git keeps them; the commit message says what was deleted and
  where its behavior or conclusions now live.
- Patch and minor upgrades of dependencies and plugins in the three POMs the
  dependency audit reads, once the `dependency-audit` skill has checked their
  release notes and minimum Java version, and `mvn test` plus the `runtime-consumer`
  job of `.github/workflows/ci.yml`, run locally, pass.

Ask before:

- Adding a dependency or plugin, a major upgrade, a change to the build JDK or
  the Java 8 baseline, and any edit under `.github/workflows/` or to
  `mkdocs/requirements.txt`.
- Deleting untracked files you did not create, or files outside the change.
- Full release builds or `mvn deploy`.

PIT authorization is above; execution and acceptance follow
[PIT verification](verification/pit.md).

Never commit secrets, `.env` files, GPG keys, or repository credentials.

## Document Routes

Load documents when their subject affects the task:

- `integrations/AGENTS.md` - The standalone Resilience4j, Sentinel, and Spring
  sample project. Read before changing anything under `integrations/`; it is a
  separate consumer project, outside `mvn test` and CI.

- `design/issue-automation.md` - The optional issue runner: trust boundary,
  labels, gates, and what may merge unattended. Read before changing
  `scripts/issue_agent/`, the agent labels, or issue-triggered workflows.
- `design/AGENTS.md` - Entry point for execution-engine, cancellation,
  task-group, queue, extension behavior, or proposal maintenance changes. Load
  only documents whose summaries match the change. It routes live contracts and
  tracked in-flight proposals separately; proposals do not constrain current
  implementation. Check `git status --short design/` for uncommitted analysis
  too; analysis committed at the user's request under `design/analysis/` is
  input, not contract.
  Current `design/` contracts take precedence over historical ADRs, except for
  boundary questions held by ADRs — `adr/0013` holds the current standalone queue
  artifact boundary and supersedes `adr/0006`. Its principle 10 decides where decision
  rationale lands: ADR, a contract's rejected-alternatives section, or the decision log.
- `design/decision-log.md` - Conclusions and rejection reasons of retired
  directions; read before reopening an old direction.
- `design/first-principles.md` - Evaluate new capabilities, APIs, or mechanisms.
- `refactoring-review/AGENTS.md` - Rule router for the refactoring-review step:
  principles, tiers, lenses (including the concurrency-mechanism ladder), and the
  config the planner reads. Read before running the step or changing its rules.
- `docs/en/user-guide.md` - Update when user-facing behavior changes.
- `docs/en/migration-v0.2.md` - Update for public API renames, signature changes,
  or other breaking changes from `0.1.x`.
- `docs/zh/design/philosophy.md` and `docs/zh/design/idea-graveyard.md` - Consult
  for design tradeoffs and previously rejected ideas when proposing capabilities.
- `adr/` - Historical decision rationale; decisions are never rewritten, and
  records are condensed only per `adr/AGENTS.md`. New records only for
  decisions `design/AGENTS.md` principle 10 routes there.
- `BACKLOG.md` - Known defects and deferred work, each with an evidence grade.
  It is pinned to the commit named at its top, so re-verify any line number it
  cites before acting on an entry.
- `mkdocs/mkdocs.yml` - Site navigation, i18n locales, and the redirect map for
  previously published URLs. Update when adding, renaming, or moving a page
  under `docs/`.
- `verification/lincheck.md` - Interleaving research for narrow cancellation
  windows; read before adding or changing a `*LincheckTest`.
- `verification/delivery-ledger.md` - Per-delivery process record: when to
  write it, field meanings, the report, and its limitations; read before
  delivering a change or changing `scripts/process-metrics.py`.
- `verification/java-conventions.md` - Diff-scoped gate for written Java
  conventions: rules, exemptions, baseline subtraction, and limitations; read
  before changing `scripts/check-java-conventions.py` or when it reports.
- `verification/spotbugs.md` - Opt-in static audit scope, baseline, and why it
  is not a default gate; read before running the `spotbugs` profile or editing
  `verification/spotbugs-exclude.xml`.
- `.github/ISSUE_TEMPLATE/` - The forms a capability, defect, or documentation
  issue must use; the design proposal form mirrors the `design/first-principles.md`
  evaluation.
