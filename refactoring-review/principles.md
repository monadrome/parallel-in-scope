# Refactoring-Review Principles

These principles bind every detector, the triaging agent, and the agent applying refactors.
Numbers referenced here live in [config.json](config.json).

## R1 Behavior preservation is the definition

A refactoring changes structure and keeps observable behavior. In this repository observable
behavior includes return values, exception types and messages, documented Javadoc contracts,
ordering and visibility, the interrupt flag, whether cancellation reaches a running body, and
resource lifetime. A change that might alter any of them is not a refactoring: it goes to code
review or to a proposal.

## R2 Scope is the change itself

Detectors and appliers act on code the change added or modified and on the method or type that
encloses it. Pre-existing smells outside that region are reported as `out-of-diff` and never fixed
here. One exception: when the change makes an existing smell worse (for example it adds the third
copy of a duplicated shape), the fix may touch the existing copies within the files the change
already modifies.

## R3 Repository rules override generic smells

The sources listed under Precedence in [AGENTS.md](AGENTS.md) override every lens rule. Examples:
the root package deliberately co-locates the public API with the package-private kernel, so
"split into subpackages" is never a finding; `adr/0006-queues-ship-with-core.md` closes the queue
artifact boundary; queue code keeps parity with JDK `LinkedBlockingQueue` per the queue
`AGENTS.md`.

## R4 Findings are judgement calls with a cited rule and a concrete move

Every finding names a lens rule ID, the symbol it concerns, a named refactoring move (Fowler's
catalog names where one exists: Extract Function, Inline Function, Move Function, Introduce
Parameter Object, Replace Loop with Pipeline, Remove Dead Code, ...), and the benefit in terms of
what becomes easier to change, test, or reason about. A finding without a move is dropped.
Smells are labelled heuristics ("possible Feature Envy"), never hard violations.

## R5 Mechanical problems become checks

When a finding is an instance of a fixed pattern, the durable outcome is a deterministic check
(a test such as `PackageBoundaryTest`, a `scripts/check-*.py` script, or an Error Prone check), not a
manual fix repeated every run. Mark it `mechanizable: true`; the report lists it under proposed
checks.

## R6 Detection and application are separated

Detectors run in fresh contexts, read only, and never edit files, invoke skills, or spawn agents.
The main agent, which owns the tests and verification context, triages and applies. A detector's
output is a hypothesis; only the main agent's verification against the working tree makes it a
decision.

## R7 One pass, no convergence loop

Each change gets one refactoring-review pass. The `refactor:` commits it produces are not
refactoring-reviewed again; code review covers them. Judgement-call findings vary from run to run,
so looping until a run comes back clean never terminates.

## R8 Tests are the oracle and stay unchanged

After a refactoring the existing tests pass without changing their assertions, inputs, or timing.
If a refactoring needs a test's assertion or timing to change, it changed behavior or the test was
coupled to the implementation: revert it and record it as `T2`. Mechanical renames that follow a
renamed symbol are the only permitted test edits; `scripts/refactoring-review.py verify` flags the
rest.

## R9 Delete and inline before abstracting

Prefer removing, inlining, and merging (Speculative Generality, Middle Man, dead code) over adding
types, parameters, or hooks. A refactoring that adds a concept must satisfy the root `AGENTS.md`
rule "prefer parameterizing existing mechanisms over adding concepts"; otherwise it is `T2`.

## R10 Concurrency ladder moves are suggestions

[lenses/concurrency.md](lenses/concurrency.md) ranks concurrency mechanisms. Moving existing code
up that ladder replaces a concurrency protocol, which the root `AGENTS.md` reserves for a
maintainer decision, so such findings are always `T2`. New code in the change that lands on the
ladder's lowest rung must carry its interleaving argument or be rewritten higher before review;
that requirement is `T1`.

## Tiers

The detector proposes a tier; triage may only make it stricter (T0 to T1 or T2, T1 to T2).

| Tier | Scope | Handling |
|---|---|---|
| `T0` local mechanical | rename of a private member or local; extract or inline a private method; delete dead code the change introduced; merge duplication inside the change; migrate a touched precondition to the Guava taxonomy; move a comment to its correct layer | apply; full `mvn test` |
| `T1` cross-symbol or sensitive | a package-private signature; an edit spanning files; code under the cancellation, interruption, deadline, visibility, context-propagation, executor-binding, or queue routes without changing the protocol; test helpers | apply; targeted tests plus full suite; `python3 scripts/check-race-claims.py` when the code is concurrency-sensitive; name each one as an attack surface for code review |
| `T2` suggestion only | public API; documented contracts; concurrency protocols; new types or concepts; out-of-diff code; queue JDK parity | never applied; record its destination: `BACKLOG.md`, a `design/proposal/` entry, or the report's open items |

## Scaling

Detection splits by lens and, for large changes, by shard; it never assigns the same lens to two
agents on the same files, because agreement between judgement calls is not evidence.

- Below `limits.singleAgentMaxLines` changed code lines, one detector carries every triggered lens.
- Up to `limits.perLensMaxLines`, each triggered lens gets one detector over its relevant files.
- Above that, lenses marked `shardable: false` keep one detector over the whole diff (cross-file
  smells need the whole view); the other lenses split into shards of about `limits.shardLines`
  lines grouped by directory. A file larger than that is its own shard; a shard below
  `limits.minShardLines` joins the smallest other shard of its lens. The total stops at
  `limits.maxAgents`; the remainder is reported as uncovered together with a recommendation to
  split the change.
- The finding budget `limits.findingBudget` is shared across detectors, so triage cost stays flat
  as detectors increase.

## Stop Rules

- Stop applying when the refactor commits exceed `limits.refactorGrowthRatio` of the behavior
  change's code lines or more than `limits.maxAcceptedFindings` findings are accepted; move the
  rest to a separate task.
- A detector that times out, fails, or returns unparsable output is not an empty review: rerun it
  once, then record that lens and shard as blocked.

## Rejected Alternatives

- **One detector with every rule.** Rejected: a single context reports the most visible smells
  (names, duplication) and skims the rest; per-lens detectors look deeper and make lens yield
  measurable.
- **Several detectors on the same lens and files, then voting.** Rejected: smells are judgement
  calls, so three agreeing agents are not more correct than one, and the duplicates triple triage
  cost.
- **Detectors that apply their own fixes.** Rejected: the detector lacks the verification context
  and would review its own edits; separation (R6) keeps a fresh view and a single accountable
  applier.
- **Rerunning until clean.** Rejected by R7: each fix adds code and judgement varies between runs.
- **Refactoring inside the red-green loop.** Rejected: implementing agents rarely perform it and
  carry the bias of the code they just wrote; it belongs to this review stage.
