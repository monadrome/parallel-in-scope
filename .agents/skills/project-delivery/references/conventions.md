# Code And Prose Conventions

Read when implementing or reviewing changes to project-authored code or prose;
Java-specific bullets apply only to Java.

## Key Conventions

- Java 8 APIs only in `core/src/main/java`.
- Declaration Javadoc states the contract its reader can rely on: purpose,
  inputs and results, nullability, exceptions, concurrency, cancellation, and
  lifecycle. This includes internal declarations. Public Javadoc must make sense
  without reading package-private types; retain observable ordering and
  resource-lifetime guarantees in the contract.
- Do not add `@since` Javadoc tags before the 1.0.0 release: the pre-1.0.0 API
  changes too frequently for version tracking to help. Do not add `@author`
  tags; authorship is unambiguous and tracked by Git.
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
  to library, tests, and demo.
- Use American English in code and prose, except the doubled-`l` family:
  `cancelled`, `cancelling`, `canceller`, `cancellable`, `signalling`, `labelled`.
  Respelled public members need changelog entries that name the replacement.
  Preserve third-party names and original spellings in dated records (released
  changelogs, published migration guides, accepted ADRs).
- For documentation language, including code/proper-name/historical exceptions and approval
  before changing existing Chinese maintainer content, read Documentation Audiences in
  [documentation maintenance](documentation.md) before writing or editing that content.
- Accessors use the bare `x()` style everywhere (`token.state()`, `event.result()`);
  do not introduce `getX()`/`isX()` forms. Methods implementing JDK or
  third-party contracts keep their mandated names (`ExecutorService.isShutdown()`,
  `Monitor.Guard.isSatisfied()`).
- Every library package under `core/src/main/java` has `package-info.java` with JSpecify
  `@NullMarked`; consumer modules keep their own nullability policy.
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
- `State` names a stage in a process: one situation inside an encompassing
  lifecycle that implies what may come next. Machine-position enums
  (`CancellationToken.State`), aggregate carriers in the GoF State pattern sense
  (`TaskExecutionState`), and live stage projections (`TaskState`) all use it.
  `Status` names the outcome of an operation: a final, resulting condition
  (`ThreadPoolStarvationReport.Status`). Terminal task outcomes keep their own
  type (`TaskTermination`); live stage views are `State`, never `Status`.
- Public APIs and SPI may break between `0.x` releases without compatibility
  shims for meaningful improvements with documented rationale.
- For public API renames or signature changes, update the implementation,
  tests, user documentation, and the changelog entry as one change. Keep the
  rationale explicit so future maintainers can distinguish intentional API
  evolution from accidental breakage.
- The project does not maintain migration guides: each breaking change carries
  its migration action in its changelog entry, which must be self-contained.
  Migration guides published for released versions are frozen dated records.
