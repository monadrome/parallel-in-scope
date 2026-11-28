# Declare enqueue refusal once per registered executor

## Abstract

`rejectEnqueue` is a per-unit option on `BatchOptions` and `TaskOptions`. It only acts when the
registered pool is a `ThreadPoolExecutor` whose work queue is a `SubmissionAwareBlockingQueue`;
on any other queue it is inert and the first such submission through a `Par` logs one warning.
A removal was proposed on the grounds that its job is now done by pool topology and the executor
wait graph. `NestedSamePoolEnqueueRefusalTest` shows otherwise. For a nested batch on the pool
whose only core worker runs the outer element, the inner element without the flag queues until
the inherited deadline expires. With the flag, a pool below its maximum grows a worker and the
inner element succeeds; a pool at its maximum invokes its rejection handler, which either ends the
inner element as `SUBMISSION_REJECTED` before the deadline (abort) or runs it on the blocked outer
worker (`CallerRunsPolicy`). Topology can keep nested work off the outer pool, but it cannot make
a `ThreadPoolExecutor` grow before queueing: the pool adds a worker above its core size only when
`offer` fails. Removal would lose that.

The current shape still has three costs:

1. Queue admission is decided twice: once when the user builds the pool (queue type and capacity)
   and again on every submission (the flag). The `runOnCallerThread` graveyard entry rejected the
   same split for rejection handling, which `RejectedExecutionHandler` declares once.
2. A flag requested on the wrong pool only warns, once per `Par`. The decision log rejected
   warn-only as the final state for the default starvation protection because users believe a
   protection is active when it is not.
3. The executor wait graph classifies a pool by its queue capacity alone, so it reports the same
   self-loop whether or not refusal removes the starvation.

Direction: declare refusal once, at registration of the executor in `ParRuntime.Builder`.
Candidate items:

- Enqueue refusal MUST be declared at most once per registered executor, not per unit.
- Registration that declares refusal MUST fail when the executor, after TTL unwrapping, is not a
  `ThreadPoolExecutor` whose work queue is a `SubmissionAwareBlockingQueue`.
- The wait graph MUST NOT report a wait on a refusing executor as starvation-prone, because library
  units never queue there.
- Refusal MUST stay opt-in; the default registration enqueues.
- The proposal MUST NOT change what a refused `offer` leads to: thread growth, then the pool's
  rejection handler.

Rejected directions:

- **Remove `rejectEnqueue` and `SubmissionAwareBlockingQueue`.** The test above shows that the flag
  turns a deadline-length starvation into thread growth, a fast submission failure, or inline
  execution, and pool topology cannot express "grow before queueing".
- **Keep the per-unit flag and turn the warning into an admission failure.** It removes cost 2 but
  keeps the duplicated decision (cost 1) and the false self-loop (cost 3).
- **Reject every buffering pool at `build()`.** Already rejected under Executor transparency in the
  decision log: it refuses `ForkJoinPool`, virtual-thread executors, and framework-managed pools.

## Caller before and after

Before, every unit on the shared pool must remember the flag, and a pool without the queue only
warns:

```java
ThreadPoolExecutor shared = new ThreadPoolExecutor(
        4, 16, 1L, TimeUnit.MINUTES, new SubmissionAwareBlockingQueue<>(64));
ParRuntime runtime = ParRuntime.builder().register(ParId.of("shared"), shared).build();

runtime.par(ParId.of("shared")).map(ids, this::load,
        BatchOptions.inheritTimeout("load").rejectEnqueue(true));
```

After, the pool declares it once and a wrong queue fails at registration. The registration method
shape is an open point; the line below shows only where the declaration lives:

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("shared"), shared, /* refuse enqueue for library units */)
        .build();

runtime.par(ParId.of("shared")).map(ids, this::load, BatchOptions.inheritTimeout("load"));
```

Failure mode removed: a unit that forgets the flag queues behind blocked workers, and a flag on a
pool without the queue does nothing beyond one log line.

Migration: delete `rejectEnqueue(...)` from `BatchOptions`/`TaskOptions` call sites and declare
refusal on the registration of the pool those units used. Callers that need refusal for some units
but not others on one pool register the pool twice under two `ParId`s with different declarations;
whether the wait graph and the resource-identity projection still treat those as one executor is
an open point.

## Open points

- The registration API shape (an overload, a registration options type, or a builder step).
- Whether any real caller needs per-unit refusal on one pool; current usage is unknown.
- Whether refusal should apply to every library unit on the executor or only to units submitted
  from that executor's own workers, which is the nested case the test exercises.
