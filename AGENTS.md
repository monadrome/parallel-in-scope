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

Two invariants to respect:

- `CancellationToken.bind()` wires deadline, fail-fast, and parent
  propagation only after all futures are submitted; the deadline itself lives
  in the token (min of the requested deadline and the parent's).
- `SlidingWindowSubmitter.submitAll()` returns the exact prepared
  `ExecutionPhaseHintFuture` for tasks in the initial parallelism window;
  tasks beyond the window are returned as `SettableFuture` placeholders
  bridged via `setFuture()` when a slot frees.

## Design Decisions

Optimize for structured concurrency and user safety and convenience. Prefer
parameterizing existing mechanisms over adding concepts. For a new capability,
API, or mechanism, use the evaluation checklist in `design/first-principles.md`
before choosing an implementation. For fixes to existing behavior, consult the
relevant contract through the document routes below.

## Key Conventions

- Java 8 APIs only in `src/main/java`.
- Accessors use the bare `x()` style everywhere (`token.state()`, `event.result()`);
  do not introduce `getX()`/`isX()` forms. Methods implementing JDK or
  third-party contracts keep their mandated names (`ExecutorService.isShutdown()`,
  `Monitor.Guard.isSatisfied()`).
- Every package has `package-info.java` with `@ParametersAreNonnullByDefault`;
  annotate only exceptions with `@Nullable` — `javax.annotation.Nullable` for
  public API/SPI, `org.checkerframework.checker.nullness.qual.Nullable` for
  internal code (both provided scope).
- Logging goes through JUL (`java.util.logging.Logger`).
- The `Scope` suffix marks a lifecycle scope (`SubmissionScope`,
  `TaskGraphObservationScope`); public scopes are closeable, while package-private scopes may be
  stack-installed implementation details. The `Context` suffix marks a data carrier
  (a view or resolved parameters); the `Member` handle marks an identity-typed
  structural slot of a `TaskGroupDefinition`; the `Id` suffix marks an immutable
  value object identifying a logical entry (`ParId`).
- Pre-stable API: public APIs and SPI may change between `0.x` releases without
  compatibility shims. During the `0.x` phase, a breaking change is acceptable
  when it provides a meaningful improvement and has a sufficiently documented
  rationale; do not preserve an awkward API solely for compatibility.
- For public API renames or signature changes, update the implementation,
  tests, user documentation, and migration notes as one change. Keep the
  rationale explicit so future maintainers can distinguish intentional API
  evolution from accidental breakage.

## Verification And Completion

- Add or update tests when changing cancellation, context propagation, executor
  binding, or queue behavior.
- For code changes, use targeted tests while iterating, run `mvn spotless:apply`,
  and finish with a green `mvn test`. A full suite already passing on the final
  code satisfies the targeted-test requirement; do not rerun tests solely to
  satisfy another workflow step.
- For documentation-only changes, check the diff, referenced paths, and any
  commands against their source configuration; Java tests and formatting are
  unnecessary unless executable code or build behavior also changes.
- Carry implementation through applicable verification and the Git workflow
  below. Fix failures caused by the change and rerun affected checks without
  pausing for review of the first implementation. Report unrelated failures or
  blockers explicitly; do not claim completion while required checks are blocked.

## Git Workflow

- After completing the applicable verification above, commit and push the
  current branch automatically; no need to ask. This includes documentation
  maintenance.
- Exception: do not auto-commit design proposals or analysis documents. They
  usually need several rounds of discussion, so leave them in the working tree
  until the direction is settled; committing early both churns history and
  reads as approval that has not been given.
- Stage only the files belonging to the change; leave unrelated working-tree
  modifications uncommitted. Follow the repository's conventional-commit style
  (`feat:`/`fix:`/`refactor:`/`docs:`/`test:`, lowercase summary).

## Permissions

Local tests and Java formatting are authorized with the existing toolchain and
installed dependencies. Ask before:

- Installing Maven dependencies or upgrading plugin versions.
- Deleting files or directories.
- Full release builds, PIT mutation tests, or `mvn deploy`.

Never commit secrets, `.env` files, GPG keys, or repository credentials.

## Subagent Usage

When the coding agent is Kimi Code, implement code changes directly in the
main agent; do not proactively delegate implementation to subagents. The only
exceptions are read-only exploration/analysis subagents and cases where the
user explicitly asks for subagent delegation.

## Document Routes

Load documents when their subject affects the task:

- `design/AGENTS.md` - Entry point for execution-engine, cancellation,
  task-group, queue, or extension behavior changes. Load only contracts whose
  summaries match the change. Current `design/` contracts take precedence over
  historical ADRs.
- `design/first-principles.md` - Evaluate new capabilities, APIs, or mechanisms.
- `docs/en/user-guide.md` - Update when user-facing behavior changes.
- `docs/en/migration-v0.2.md` - Update for public API renames, signature changes,
  or other breaking changes from `0.1.x`.
- `docs/zh/design/philosophy.md` and `docs/zh/design/idea-graveyard.md` - Consult
  for design tradeoffs and previously rejected ideas when proposing capabilities.
- `adr/` - Historical decision rationale; existing records are immutable.
