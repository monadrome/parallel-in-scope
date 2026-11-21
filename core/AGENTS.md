# Core Engineering Instructions

Applies to the library and its tests under `core/`. Read the root code/prose conventions route
before editing Java. For behavior changes, use `design/AGENTS.md` to select the owning contract;
do not read the entire design directory.

## Architecture

Base package: `io.github.monadrome.parallelinscope`.

| Package | Responsibility |
|---|---|
| root package | Public API and callbacks plus the package-private execution kernel |

The root package deliberately co-locates the public API with package-private cancellation,
context, graph, and scheduling implementation. This is the Java 8 encapsulation boundary: do not
reintroduce public bridge types or conceptual subpackages merely to categorize files.

The general-purpose queue implementations publish as the standalone
`io.github.monadrome:blocking-queues` artifact, developed in the separate
[blocking-queues](https://github.com/monadrome/blocking-queues) repository; core consumes it as an
external dependency pinned by `blocking-queues.version` in the parent POM. The artifact boundary is
defined by `adr/0013-standalone-blocking-queues-artifact.md` and the repository move by
`adr/0015-blocking-queues-standalone-repository.md`. The `SubmissionAwareBlockingQueue` adapter
remains in the core root package because its admission semantics read `SubmissionScope` and
`MultiTaskContext`. Queue behavior, its contract, and its tests live in that repository and are
outside this repository's reading and verification scope.

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
otherwise inspect with `isInterrupted()`. Read-only query methods must not
change their result or throw merely because the calling thread is interrupted.
Exception: synchronous `Par.map` / group `runAll` deliberately wait uninterruptibly
and restore the flag, including their bounded cleanup waits. Caller interruption
does not cancel these executions. `ParRuntime.awaitQuiescence` remains interruptible.
