# FuturePath Staged Outcome-Handling API

## Abstract

- Core motivation: replace callers directly reading `Future.State`, `resultNow()`,
  `exceptionNow()`, and similar state-query methods with a staged API, expressing one round of
  result handling as a compile-time-checkable complete path.
- Shape: `FuturePath` can be created from a `ListenableFuture<T>` or an `ImmediateResult<T>`;
  both entries share the result-dispatch stages, but end in an asynchronous Future or a
  synchronous Result respectively, and neither can masquerade as the other.
- Proposed MUSTs:
  - A path MUST handle `SUCCESS`, `USER_FAILURE`, `SUBMISSION_FAILURE`, and the cancellation
    terminal states in order; each stage can only advance to the next — no skipping,
    repeating, or reordering.
  - The `SUCCESS` stage receives a nullable value; the two failure stages receive the original
    `Throwable`; the cancellation stage receives a `TaskTermination` and an optional detailed
    Throwable.
  - The cancellation stage MUST cover `MEMBER_CANCELLED`, `GROUP_CANCELLED`, `FAIL_FAST`, and
    `TIMEOUT`, but must not require the user to register a handler per internal cancellation
    reason.
  - A path created from a Future MUST keep asynchronous semantics and produce a
    `ListenableFuture<R>`; a path created from a Result MUST keep synchronous semantics and
    produce an `ImmediateResult<R>`.
  - The stage chain MUST be impossible to end before the last handler is registered, making a
    missed branch a compile-time error rather than a runtime default branch.
- Rejected directions: continuing to expose the state-query combination (still requiring the
  user to memorize the state-read order); a separate stage per cancellation enum value
  (turning the internal enum into an unstable public API); auto-wrapping a synchronous Result
  into a Future (losing synchronous semantics); a single `OutcomePath` terminal returning both
  Future and Result (unnatural to express in Java's types, leaving callers unable to tell the
  execution model).

The task-termination semantics this proposal consumes are defined by the
[task termination and scope reason contract](../task-termination-and-scope.md).
`FuturePath`'s cancellation stage handles `CancellationReason` and `ExecutionDisposition`; it
must not re-expose `GROUP_CANCELLED`, `MEMBER_CANCELLED`, or `FAIL_FAST` as new stable reasons.

## 1. The failure mode and the best code before

Current callers must read the state first, then call the query method matching the state. In
asynchronous scenarios they must also remember which methods are allowed, which throw, and
whether a cancellation reason carries usable detail:

```java
if (future.state() == Future.State.SUCCESS) {
    Price price = future.resultNow();
    return price;
}
if (future.state() == Future.State.FAILED) {
    Throwable failure = future.exceptionNow();
    return fallback(failure);
}
return Price.UNKNOWN;
```

This code has three problems: state reads and result reads are two API sets; a missed state
falls back only at runtime; and the user must decide for themselves how `USER_FAILURE`,
`SUBMISSION_FAILURE`, and the cancellation terminal states are classified.

## 2. User code after

Future entry:

```java
ListenableFuture<Price> price = FuturePath.fromFuture(future)
        .onSuccess(value -> value)
        .onUserFailure(error -> fallback(error))
        .onSubmissionFailure(error -> Price.UNKNOWN)
        .onCancelled((outcome, detail) -> Price.UNKNOWN)
        .toFuture();
```

Result entry:

```java
ImmediateResult<Price> price = FuturePath.fromResult(result)
        .onSuccess(value -> value)
        .onUserFailure(error -> fallback(error))
        .onSubmissionFailure(error -> Price.UNKNOWN)
        .onCancelled((outcome, detail) -> Price.UNKNOWN)
        .toResult();
```

Handler registration order is constrained by the return interfaces. A caller cannot end right
after registering `onSuccess`, nor skip the submission-failure stage; only after the last
`onCancelled` is registered does the path have a terminal.

## 3. API shape

The public entries keep one concept name, with internal stages using different return
interfaces:

```java
public final class FuturePath {
    public static <T> FutureSuccessStage<T> fromFuture(
            ListenableFuture<T> source);

    public static <T> ResultSuccessStage<T> fromResult(
            ImmediateResult<T> source);
}
```

The stage interfaces' constraining shape follows; concrete generic bounds and JSpecify
annotations are subject to Java 8 expressibility at implementation time:

```java
interface FutureSuccessStage<T> {
    <R> FutureUserFailureStage<R> onSuccess(
            Function<? super @Nullable T, ? extends R> handler);
}

interface FutureUserFailureStage<R> {
    FutureSubmissionFailureStage<R> onUserFailure(
            Function<? super Throwable, ? extends R> handler);
}

interface FutureSubmissionFailureStage<R> {
    FutureCancelledStage<R> onSubmissionFailure(
            Function<? super Throwable, ? extends R> handler);
}

interface FutureCancelledStage<R> {
    FutureTerminalStage<R> onCancelled(
            BiFunction<? super CancellationReason, ? super ExecutionDisposition, ? extends R> handler);
}

interface FutureTerminalStage<R> {
    ListenableFuture<R> toFuture();
}
```

The Result entry uses the same four handling stages, with the last stage returning a terminal
interface offering only `toResult()`, ultimately yielding an `ImmediateResult<R>`. Internally
it may reuse the existing `ImmediateResult`'s outcome dispatch, but it must not reuse the
Future's waiting, cancellation, or exception-extraction paths.

## 4. Terminal classification and the exhaustiveness boundary

This proposal's exhaustiveness targets the stable semantics users can handle, not every
constant of the current enum:

| Public stage | Covered `TaskTermination` |
| --- | --- |
| `onSuccess` | `SUCCESS` |
| `onUserFailure` | `USER_FAILURE` |
| `onSubmissionFailure` | `SUBMISSION_FAILURE` |
| `onCancelled` | `CancellationReason` (with source identity) and `ExecutionDisposition` |

`RUNNING` is not a terminal state deliverable to a path. If an unfinished state appears
internally, the path implementation MUST report a library-internal error rather than invoke a
user handler.

The cancellation handler receives the stable `CancellationReason` and claim disposition;
business failures stay in `FailureCause` and scope member results and are not disguised as a
second scope reason through the cancellation stage.

## 5. Synchronous and asynchronous semantics

- `fromFuture` MUST NOT block the calling thread; it registers one completion-driven dispatch
  and returns a derived `ListenableFuture<R>`.
- `fromResult` MUST only read the immutable snapshot — no re-waiting, no starting executions,
  no changing the original Result's lifecycle.
- The two entries' handler invocation order and outcome classification MUST agree; differences
  are limited to execution timing and terminal type.
- Exceptions thrown by handlers follow each terminal's existing semantics: the Future entry
  converts them into the derived Future's failure; the Result entry cannot fabricate an
  asynchronous failure on an already-constructed Result — whether it throws directly or returns
  a failed Result must be specified explicitly.

## 6. Migration path

The existing `ImmediateResult.fold(onValue, onFailure, onCancelled)` can migrate to the staged
API:

```java
FuturePath.fromResult(result)
        .onSuccess(onValue)
        .onUserFailure(onFailure)
        .onSubmissionFailure(onFailure)
        .onCancelled(onCancelled)
        .toResult();
```

During migration, the old `fold` may remain as a deprecated adaptation layer, but new
documentation and examples show only `FuturePath`. The adaptation layer must not re-read Future
state; it only converts the old three handlers into the four-stage path, with the two failure
stages sharing the original `onFailure`.

## 7. Rejected alternatives

- **Keep using `Future.State` plus state queries**: cannot eliminate the caller's error of
  memorizing state-to-query-method pairing, and cannot form a compile-time exhaustiveness
  check.
- **One stage per cancellation reason**: exposes the internal attribution enum directly to
  users; adding a cancellation reason would cause unnecessary source breakage.
- **One `onFailure` covering both failures**: keeps the current API's semantic loss between
  submission failure and user failure; callers still re-read the outcome.
- **Uniformly returning `ListenableFuture<R>`**: forces the Result entry to go asynchronous,
  breaking the immutable synchronous snapshot's semantics and callers' performance
  expectations.
- **Arbitrary-order chained registration**: only a syntactic change with no compile-time
  completeness guarantee, plus new runtime rules for duplicate and overriding handlers.

## 8. First-principles evaluation

1. Forgetting eliminated: forgetting to handle failure categories, misusing state-read methods,
   calling result queries in illegal states.
2. Mechanism reuse: reuses `TaskTermination`, `ImmediateResult`, and the existing Future
   completion propagation; no new execution kernel.
3. Structured safety: the Future entry follows existing cancellation and completion
   propagation; the Result entry only handles terminal snapshots, introducing no second
   lifecycle.
4. No implicit state: a path holds its source and handlers explicitly, using no ambient state.
5. Consistent attribution: the public stages build on `TaskTermination`'s three branches, with
   cancellation reason and claim disposition expressing the trigger source and the execution
   fact separately.
6. Abstraction boundary: this capability only projects and dispatches results; it changes no
   Batch, Group, cancellation, or deadline contract.

## 9. Questions to finalize before landing

- Whether the Future entry's derived Future follows the existing listener executor or provides
  an explicit executor overload.
- When a Result entry's handler throws, whether to return an `ImmediateResult` failure snapshot
  or keep the synchronous API's direct-throw semantics.
- The deprecation period of the old `fold`, and whether to remove it directly in `0.4.0`.
- Whether the staged interfaces are public top-level types or nested interfaces of
  `FuturePath` to reduce public package surface.

These questions do not affect this proposal's core constraint: result handling must complete
through a full staged path, and the Future and Result terminal semantics must stay separate.
