# Deadline Attribution Policy for Uncommitted Cancellation

## Abstract

- Core motivation: when the budget has passed but the token has not yet committed a
  cancellation, whether to read a cancellation-shaped result as TIMEOUT remains a policy
  choice.
- Invariants and proposed MUSTs: if the rule changes, it MUST cover batch, group, and terminal
  alike, read the token's own clock domain, and preserve first-wins and business failures.
  MUST NOT guess the source from Throwable types or System.nanoTime alone.
- Rejected directions with reasons: do not expand P0015's real TIMEOUT-escalation fix into an
  implicit clock backstop, and do not use time passage to rewrite the existing USER_FAILURE
  settle rules for self-thrown CE/IE.

## Current behavior and the direction to choose

P0015's committed member/terminal TIMEOUT-escalation omissions have been merged into the
[group cancellation contract](../task-group-cancellation.md). This proposal only carries over
its unresolved RUNNING-clock backstop.

```java
TaskGroupResult<Void> result = runtime.group("load", timeout)
        .par(TaskKey.<Integer>named("member"), par, () -> { throw ownCancellation; })
        .runAll();
TaskTermination outcome = result.resultOf("member").termination();
```

Today the same code keeps USER_FAILURE when the token has no framework cancellation committed,
even if the execution throws CE itself just after the budget. A candidate change might read the
same code as TIMEOUT, but that would first require proving this is an error pattern and
choosing the boundary among external direct cancellation, self-thrown CE/IE, and business
TimeoutException. If monitoring attribution changes, a migration path must be written; no rule
is approved at present.

Acceptance must use controlled clocks with late or never-firing timers to verify real winning
events versus mere time passage separately, covering failure precedence, independent tight
budgets, inherited budgets, nesting, and unexpired inputs. Historical stress counts or review
consensus must not substitute for a new policy choice.
The historical input is PR #56's four revision rounds; its original argumentation was not
recorded in the repository. The [batch settle contract](../batch-settle-attribution.md) remains the current
constraint.
