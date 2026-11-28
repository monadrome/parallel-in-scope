You are one independent reviewer in the llm-review-bench benchmark.

Review the change from 729cb8742acc529db16980e12e7433ea91fe1311 to ce7b5cc2b86d07ec32a861916ba029e9d9beba3a. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/join-policy-await-all/.review-benchmark/runs/join-policy-await-all-ce7b5cc2-20261128-125101-c0b616/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/join-policy-await-all/.review-benchmark/runs/join-policy-await-all-ce7b5cc2-20261128-125101-c0b616/inputs/context.md. Do not modify the snapshot. Do not read
benchmark gold labels, adjudication records, or other reviewers' output.

Return exactly one JSON object and nothing else (no Markdown fences, no prose):

{
  "summary": "one short paragraph describing the review",
  "findings": [
    {
      "file": "repository-relative/path.ext",
      "lineStart": 123,
      "lineEnd": 130,
      "title": "short defect title",
      "trigger": "concrete input, interleaving, or condition that reaches the defect",
      "consequence": "observable failure or contract violation",
      "evidence": "code path with file:line references",
      "severity": "P0|P1|P2|P3",
      "confidence": 0.0,
      "suggestedFix": "actionable fix"
    }
  ]
}

Rules:
- Report only defects introduced or materially exposed by this change.
- Every finding must have a concrete trigger and a user-observable consequence.
- Cite repository-relative files and the line range in the head snapshot.
- confidence is a number from 0 to 1.
- At most 20 findings. Return {"summary":"...","findings":[]} when you find
  nothing; do not return an empty response or prose.
- Do not include shell commands, patches, or hidden reasoning outside the JSON object.

## Shared context (verbatim, immutable)

# Review context: scope-termination determinism and JoinPolicy.AWAIT_ALL

Range: 729cb874 (dev/v0.5.0 tip) .. HEAD of branch feat/join-policy-await-all, five commits:

1. `fix: derive the scope termination from frozen results, not settle order` (stage A). Removes the
   arrival-order `ScopeTerminationAccumulator`. `TaskBatch#deriveScopeTermination` and
   `TaskGroup#deriveScopeTermination`/`failedTask` derive the scope termination at freeze /
   convergence: first FAILED in input/declaration order outranks every cancellation; then the
   group's direct-member and caller rules, then the token's committed state.
2. `test: keep the convergence test name and except the removed accumulator test`.
3. `feat: add a per-scope join policy with AWAIT_ALL` (stage B). Public `JoinPolicy` enum,
   `BatchOptions.joinPolicy`, `GroupStart.joinPolicy`. `CancellationToken.bind` picks
   `allAsList` (FAIL_FAST) or `successfulAsList` (AWAIT_ALL); token state `SUCCESS` renamed
   `COMPLETED`. `TaskGroup.memberCompleted` skips the direct-cancel cascade under AWAIT_ALL and
   `skipCombineAfterAllMembersSettled` cancels a declared combine via `groupToken.failFastCancel()`
   once every member settled with a non-success. `Par.executeCombined` does the same for
   `mapAndCombine` via a `successfulAsList(views)` listener.
4. `test: match the fail-fast commit-order mutation to the policy-guarded callback`.
5. `refactor: tighten the join-policy derivations and their test setup` (refactoring-review
   items R2-R5; R1 deferred). Treat these as settled; check behavior preservation of commit 5.

Owning contracts (read the changed sections): `design/task-termination-and-scope.md` section 4,
`design/cancellation-propagation.md` sections 1 and 1.1, `design/task-group-cancellation.md` 8.2-8.3,
`design/batch-terminal-combine.md`, `design/task-group-terminal-combine.md` section 8. User docs:
`docs/en/user-guide.md` (#join-policy, #scope-termination), `docs/en/design/idea-graveyard.md`.

Do not read `verification/contract-tests/`.

Attack surfaces:

- Interleavings: member settle vs group convergence barrier (`completedTasks`), the nested
  combine completion inside `failFastCancel`, `settledMembers`/`memberSuccesses` publication,
  deadline/cancelOn/parent cancellation racing the last member settle under AWAIT_ALL,
  `successfulAsList(views)` listener vs the batch token's own aggregate, sliding-window refill
  after a failure, direct-executor and inline execution paths.
- Contract versus implementation: diff every MUST/MUST NOT in the commit messages and in
  `cancellation-propagation.md` section 1.1 and `task-termination-and-scope.md` section 4 against
  the code line by line, including attribution reasons and their source identities.
- Behavior changes for existing FAIL_FAST callers: anything that changed when the policy is the
  default (scope termination precedence, failedTaskName, values future, report strings).
- Test quality: do the new tests (`AwaitAllJoinPolicyTest`, `ScopeTerminationOrderTest`) fail
  without the code they guard; are any of them timing-dependent or vacuous.
- Java 8 API use in `core/src/main/java`, generics, nullability (JSpecify/NullAway).
