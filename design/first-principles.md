# First Principles

> This document is the project's axiom layer: the criteria from which every design decision is
> derived from the fewest possible starting points. Consult it first when evaluating any new
> requirement, API, or mechanism; the `design/` contract series concretizes these principles,
> and [philosophy.md](../docs/zh/design/philosophy.md) is their narrative recap.

## 1. Starting points (axioms)

1. **Structured concurrency is the ontology.** The lifecycle of concurrent work is bound to
   explicit scopes: when a scope closes, the results of its work are necessarily terminal, the
   library restores context and attempts bounded cleanup; executions ignoring interruption may
   still not have exited, and results must explicitly report direct tasks whose exit is
   unconfirmed. Terminality does not mean threads or resources have been released.
2. **The user's failure mode is forgetting.** Forgetting to wait, to cancel, to restore a
   ThreadLocal, to handle failure, to close. The library's value is making "forgetting"
   impossible (structural guarantees) or conspicuous (explicit declarations).
3. **Safety beats expressiveness.** Better not to offer a capability than to offer one that is
   easy to misuse; every new concept must justify its existence with "which class of error it
   eliminates".
4. **Stay close to JDK idioms.** The user's mental model comes from
   `ExecutorService`/`ListenableFuture`/`Stream`; the library only supplies the structured
   properties they lack, not a parallel universe.

## 2. Derived core decisions

### Lifecycle

- A single submit boundary: a task set is frozen once and admitted uniformly — there is no
  partial registration or partial submission.
- Scopes are closeable (`Scope` suffix); data is only a carrier (`Context` suffix).
- Resource ownership is unique: `ParRuntime` owns its schedulers and internal services;
  business executors are owned by the user.

### Cancellation and deadlines

- Cancellation is a top-down token propagation tree, not a flag; attribution reads token state
  at convergence rather than guessing from `isCancelled()` after the fact.
- A deadline is an end-to-end budget: `min(own request, parent cap)`; no nesting level ever
  regains the full budget.
- Fail-fast is on by default: one failure cancels siblings — partial success usually costs
  more than starting over.

### Context

- All thread bindings (TTL, registered `ContextPropagator` stages, `TaskExecutionContext`,
  `SubmissionScope`) are stack-wise install/restore, covering normal, exceptional, cancelled, rejected,
  and inline paths. Application propagators are captured at admission and closed in reverse order;
  framework state remains outside the application propagation set.
- No implicit state: no current-group ThreadLocal, no promise to propagate ordinary
  ThreadLocal/MDC unless the application registers a `ContextPropagator` for it.

### Results and observation

- Completion is an immutable snapshot; outcomes are data, and composite results are not encoded
  as exceptions.
- Observation records only real structural relationships: no fabricated dependency edges, no
  fabricated listener events.
- Listeners are invoked only once, exception-isolated, and never run inside locks.

### Mechanism reuse

- A single execution kernel (`TaskSubmissions`/`ScopedCallable`): new capabilities are
  parameterized reuse, never copies of the cancellation/phase/TTL implementations.
- Clear abstraction boundaries: Batch = homogeneous mapping, Group = heterogeneous coordination
  scope. Capabilities that don't fit the shape get their own concept instead of inflating an
  existing one.

## 3. Where convenience comes from

- Type safety is sunk into explicit type carriers (group members bind declaration and result
  reads with `TaskKey<T>`; member snapshots are addressed by key through `GroupValues`),
  avoiding repeated type specification and name-refactoring risk, and avoiding recursive
  generics as member count changes.
- Forced explicit choices make users think (timeout is one-of-two, no default timeout).
- Executors are registered once and referenced by name; users keep their lifecycle ownership
  and shut business pools down after the runtime drains.

## 4. Criteria for evaluating new requirements

Ask in order; any "no" means redesign or reject:

1. Which class of user "forgetting" does it eliminate? (the reason to exist)
2. Can it be expressed by parameterizing existing mechanisms instead of adding a concept?
   (subtraction)
3. Do cancellation, deadline, and context restoration automatically inherit the structured
   semantics? (safety)
4. Does it introduce implicit state or a second execution pipeline? (consistency)
5. Can its failure shapes be expressed in the existing outcome vocabulary? (attribution)
6. Would it burst the abstraction boundaries? (the Batch / Group / future DAG division)

Rejected directions are recorded in [idea-graveyard](../docs/zh/design/idea-graveyard.md);
historical decision snapshots are in [adr/](../adr/) (current behavior is governed by
`design/`).
