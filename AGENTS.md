# AGENTS.md

## Project

**parallel-in-scope** is a structured-concurrency toolkit for Java 8+ built on
Guava `ListenableFuture` and Alibaba `TransmittableThreadLocal`.

- Java release 8 for the library, release 11 for tests; JUnit 5 via Surefire.
- Dependency and plugin versions live in `pom.xml`.

## Commands

Run from the repository root.

```bash
mvn test -Dtest='ClassName#methodName' # targeted test; replace class and method
mvn test                              # all tests
mvn spotless:apply                    # format Java sources
mvn spotless:check                    # verify formatting without writing; CI runs this
mvn clean verify                      # tests + package checks; not a release build
```

## Architecture

Base package: `io.github.monadrome.parallelinscope`.

| Package | Responsibility |
|---|---|
| root package | Public API and callbacks plus the package-private execution kernel |
| `queue` | Independent general-purpose queue implementations |

The root package deliberately co-locates the public API with package-private cancellation,
context, graph, and scheduling implementation. This is the Java 8 encapsulation boundary: do not
reintroduce public bridge types or conceptual subpackages merely to categorize files.

The `queue` package ships in this same artifact. Its artifact boundary is a settled decision
(`adr/0006-queues-ship-with-core.md`): do not propose splitting it into a sibling artifact,
privatizing it, or re-raising the boundary as an open question in reviews, defect triage, or
refactor proposals. Treat it as part of this library's public product — the `queue` classes have
their own contract (`design/draining-queue-contract.md`) and tests, and their defects are this
repository's to fix.

It is outside the core mechanism and outside default reading scope: skip it in repository-wide
sweeps unless the task names queue behavior. Its tests still run in `mvn test`. Rules, including
when the exclusion does not apply, are in
`src/main/java/io/github/monadrome/parallelinscope/queue/AGENTS.md`, which governs both queue
directories.

Two invariants to respect:

- Parent propagation is wired in the `CancellationToken` constructor;
  `CancellationToken.bind()` wires the deadline timer and fail-fast. Every
  entry point binds **before** submitting — `Par.submit`, `Par.map`, and
  `TaskGroup.start` alike — because submission can run a body on the
  submitting thread, and the deadline is that path's only source of liveness.
  The deadline itself lives in the token (min of the requested deadline and
  the parent's).
- Every element's caller-visible `Task` view wraps its prepared
  `ExecutionPhaseHintFuture` from creation on, whether or not the element has
  reached a free parallelism slot. There are no placeholders and no later
  bind step: `SlidingWindowSubmitter.viewsFor()` builds the views, the caller
  binds them, and `submitAll()` only decides when each prepared future enters
  the pool. Cancelling a view therefore reaches the thread running its body.

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
explicit human confirmation before implementing it.

## Key Conventions

- Java 8 APIs only in `src/main/java`.
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
- Complete applicable verification and adversarial review before committing.
  Fix failures caused by the change and rerun affected checks without pausing
  for review of the first implementation. Report unrelated failures or blockers;
  do not claim completion while required checks are blocked.
- Report each check separately and honestly: behavior tests, external consumer,
  independent review, and mutation coverage are distinct facts. Record a blocked
  check as blocked with its reason. A PIT run that started but timed out, or
  produced no `mutations.xml`, is not a pass; never describe a started or partial
  check as passing. Run Maven build/test/PIT serially within one checkout;
  independent verification checkouts may run in parallel.
- Mockito's inline mock maker self-attaches its agent. On the JDK 25 build this
  currently succeeds with only a deprecation warning and the Mockito test classes
  pass without any javaagent, but self-attach is deprecated and will stop working
  on a future JDK, so treat an attach error as an environment-specific condition
  rather than a code defect. If the Mockito tests do fail with an attach error,
  rerun with the installed jar passed explicitly — this machine uses
  `/Users/qinghualin/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar`:

  ```bash
  mvn -o test -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar
  ```

  The `$HOME` form resolves on other checkouts without hardcoding a user path.
- After verification, automatically commit and push the current branch,
  including documentation maintenance. Exception: leave design proposals and
  analysis documents uncommitted until the direction settles; commit settled
  proposals with the implementing change.
- Commit only this change's files; leave unrelated modifications and staged
  changes uncommitted. Use Conventional Commits with a lowercase summary
  (`feat:`/`fix:`/`refactor:`/`docs:`/`test:`).

## Adversarial Review

Before committing public API, documented-contract, or concurrency-sensitive
changes, run an independent review with its own budget:

- Use a different model or harness (`cmux codexyolo` runs Codex in this repo).
  The reviewer must not edit files. Name attack surfaces: interleavings,
  contract versus implementation, test quality, Java 8, and generics.
- Diff each contract MUST/MUST NOT named by the change against the
  implementation line by line; catching drift is a pre-commit review duty,
  not a periodic audit.
- Later rounds target the previous round's fixes. First read settled findings
  as the baseline, then look beyond them. Use a fresh seat when context is nearly
  full or the reviewer starts agreeing with itself.
- Reproduce every finding against the working tree; retain, downgrade, or reject
  it explicitly. Record findings, dispositions (including rejections), and each
  fix's actual effect in the change's `design/` document. Leave the review
  workspace open as an audit trail.
- Reverse-verify every regression test: temporarily revert only the fix, observe
  the new test fail, then restore the fix.
- Run mutation coverage with an explicit goal: the `pitest` profile binds no
  execution, so `mvn -Ppitest` alone fails with "No goals have been specified".
  The offline-safe form, scoped to the touched class and its test, is:

  ```bash
  mvn -o -Ppitest test-compile org.pitest:pitest-maven:mutationCoverage \
    '-DtargetClasses=io.github.monadrome.parallelinscope.TouchedClass' \
    '-DtargetTests=io.github.monadrome.parallelinscope.TouchedTest'
  ```

  The defaults for `targetClasses`, `targetTests`, and `mutators` are project
  properties (inline plugin config would shadow their user properties, making
  the `-D` flags no-ops). The default `mutators` list omits `VOID_METHOD_CALLS`
  to cut queue noise — but that also drops core interrupt / body-exited-skipped
  / `tracker.release` call-removal mutations. Add this flag for a core-targeted
  run (it replaces the default, so the nine mutators are repeated):

  ```bash
  # add to the command above for a core-targeted run:
  '-Dmutators=CONDITIONALS_BOUNDARY,NEGATE_CONDITIONALS,INCREMENTS,INVERT_NEGS,NULL_RETURNS,FALSE_RETURNS,TRUE_RETURNS,PRIMITIVE_RETURNS,EMPTY_RETURNS,VOID_METHOD_CALLS'
  ```

  Classify every survivor before reporting: equivalent mutants are not coverage
  gaps. PIT needs no permission and touches only `target/`.

Historical examples and results already live in
`design/group-one-shot-api-refactor-codex.md` section 10; consult them when
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

## Permissions

Local tests and Java formatting are authorized with the existing toolchain and
installed dependencies. Ask before:

- Installing Maven dependencies or upgrading plugin versions.
- Deleting files or directories.
- Full release builds or `mvn deploy`.

PIT authorization and scoping are defined under Adversarial Review.

Never commit secrets, `.env` files, GPG keys, or repository credentials.

## Document Routes

Load documents when their subject affects the task:

- `design/AGENTS.md` - Entry point for execution-engine, cancellation,
  task-group, queue, or extension behavior changes. Load only contracts whose
  summaries match the change. It indexes committed documents only; in-flight
  proposals stay untracked by policy, so check `git status --short design/` too.
  Current `design/` contracts take precedence over historical ADRs, except for
  boundary questions an ADR closed outright — `adr/0006` holds the queue artifact
  boundary (its retired `design/` companion is recoverable from git history via
  `design/decision-log.md`).
- `design/first-principles.md` - Evaluate new capabilities, APIs, or mechanisms.
- `docs/en/user-guide.md` - Update when user-facing behavior changes.
- `docs/en/migration-v0.2.md` - Update for public API renames, signature changes,
  or other breaking changes from `0.1.x`.
- `docs/zh/design/philosophy.md` and `docs/zh/design/idea-graveyard.md` - Consult
  for design tradeoffs and previously rejected ideas when proposing capabilities.
- `adr/` - Historical decision rationale; existing records are immutable.
- `BACKLOG.md` - Known defects and deferred work, each with an evidence grade.
  It is pinned to the commit named at its top, so re-verify any line number it
  cites before acting on an entry.
- `mkdocs/mkdocs.yml` - Site navigation, i18n locales, and the redirect map for
  previously published URLs. Update when adding, renaming, or moving a page
  under `docs/`.
- `.github/ISSUE_TEMPLATE/` - The forms a capability, defect, or documentation
  issue must use; the design proposal form mirrors the `design/first-principles.md`
  evaluation.
