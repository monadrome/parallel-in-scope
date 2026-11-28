# Refactoring review: feat/join-policy-await-all

- Base / behavior head: 729cb8742acc / 66886dd38d57
- Plan: sharded, 6 detectors, lenses M, C, D, S, V, T; uncovered: C shards 2-4, D shard 2, S shard 2, V shard 2, T shards 2-3 (the plan's six-agent cap; the change is 1003 code lines over 22 files, and splitting it was not possible after the fact because stages A and B share TaskBatch and TaskGroup)
- Rules read from: base
- Evaluation: `gray-evaluation` complexity evidence is advisory and does not change pass/fail gates.

## Detectors

| Agent | Lenses | Reviewer | Status | Findings |
|---|---|---|---|---|
| M | M | codex, model deepseek/deepseek-v4-pro | rerun-completed (attempt 1 on kimi k3.1-pro failed: "Prompt turn ended with reason: failed") | 1 |
| C-1 | C | codex, model deepseek/deepseek-v4-pro | rerun-completed (attempt 1 on kimi failed, same error) | 0 |
| D-1 | D | codex, model deepseek/deepseek-v4-pro | rerun-completed (attempt 1 on kimi failed, same error) | 1 |
| S-1 | S | codex, model deepseek/deepseek-v4-pro | rerun-completed (attempt 1 on kimi timed out at 1500 s with no output) | 0 |
| V-1 | V | codex, model deepseek/deepseek-v4-pro | rerun-completed (attempt 1 on kimi timed out at 1500 s with no output) | 2 |
| T-1 | T | codex, model deepseek/deepseek-v4-pro | rerun-completed (attempt 1 on kimi timed out at 1500 s with no output) | 1 |

The codex seat was routed to DeepSeek through `magpie codex model`, and the previous selection
(`ctrip-ada/claude-auto[1M]`) was restored afterwards. The implementer is Claude (claude-opus-5.5).

## Dispositions

| Merged id | Rule | Symbol | Tier (detector -> final) | Disposition | Reason |
|---|---|---|---|---|---|
| R1 | M05 | TaskGroup#skipCombineAfterAllMembersSettled | T2 -> T2 | deferred: open item in the delivery report | The group (settled-member counter) and the batch (`successfulAsList(views)` listener) skip their combine with two mechanisms. One owner is plausible, but the group's skip must run on the settling thread before the convergence barrier counts that member, which a listener on a second aggregate does not guarantee without re-deriving the barrier ordering; that is a design change, not a refactoring. |
| R2 | D02 | TaskGroup#failedTask | T1 -> T1 | applied in ce7b5cc2 | Convergence records every termination before `failedTask()` runs; the null guard only hid a broken invariant. Guarded by AwaitAllJoinPolicyTest, TaskGroupConvergenceTest, TaskGroupFailedTaskResultTest. |
| R3 | V04 | TaskGroup#skipCombineAfterAllMembersSettled | T0 -> T0 | applied in ce7b5cc2 | Ordering proof moved beside the statement (ADR 0012); the race claim await-all-combine-skip-after-success-count still covers it. |
| R4 | V04 | TaskBatch#deriveScopeTermination | T0 -> T0 | applied in ce7b5cc2 | Priority list moved into a block comment; the contract sentence stays in Javadoc. |
| R5 | T04 | AwaitAllJoinPolicyTest combine tests | T0 -> T0 | applied in ce7b5cc2 | Shared setup helper; assertions and settle orders unchanged (`verify` reports no oracle edits). |

Conflict R1/R3 on the same symbol: R3 (comment placement) landed; R1 (merge two mechanisms) is
deferred for the reason above, so the two do not compete.

## Verification

- spotless: pass (`mvn spotless:apply`, no further diff).
- targeted tests: pass (AwaitAllJoinPolicyTest, ScopeTerminationOrderTest, TaskGroup*Test, TaskBatchResultTest, BatchSettleAttributionTest: 240 tests).
- full `mvn test`: pass (3395 + 2 Lincheck).
- `check-race-claims.py`: pass for the touched claims (group-scope-termination-declaration-order, await-all-group-combine-skip, await-all-combine-skip-after-success-count, scope-termination-input-order: PROVEN); the group-scope mutation was updated to R2's text.
- `refactoring-review.py verify`: pass (growth 0.066 of 0.3, no oracle edits, no non-refactor commits).
- PIT: not run in this review; see the delivery report.

## Proposed checks

None: no finding was marked mechanizable.

## Handoff to code review

- Review range: 729cb8742acc..ce7b5cc2; refactor commit: ce7b5cc2
- Attack surface: behavior preservation of ce7b5cc2, especially R2's Verify on a convergence path
- Settled findings code review should not re-raise: R1 (deferred with reason), R2-R5
