# Delivery Branches

Read the section named by the current step in [Project Delivery](../SKILL.md).
That entrypoint owns the common sequence, authority, stops, and completion criteria.

## Checkout Isolation

When the ownership check finds another agent's work or unexpected HEAD movement, create an
owned worktree from the freshly fetched upstream declared in `.agents/project-baseline.json`:

```bash
git worktree add -b <topic> .worktrees/<topic> <upstream-ref>
(cd .worktrees/<topic> && python3 scripts/check-worktree-skills.py)
```

The skill check runs inside the new worktree, before any skill is invoked there; the script
inspects the checkout containing it, so the shared checkout's copy proves nothing. The
subshell leaves the caller in the shared checkout for the carry-over below. Carry over
only your own changes as a patch of your paths, then return those paths in the
shared checkout to their committed state. Verify, commit, and deliver from the owned worktree.
Leave others' files and commits intact: do not commit, stash, reset, rebase, or switch branches
in the shared checkout, and never push their commits. After delivery, preserve the review
evidence, then remove the worktree and delete its branch:

- Archive each review run under the topic name on the `llm-ledger` branch:
  `.review-benchmark/runs/<run-id>` becomes `<topic>/runs/<run-id>`, the matching
  `.review-benchmark/gold/<run-id>` becomes `<topic>/gold/<run-id>`, and a refactoring-review
  run under `.agents/reviews/` becomes `<topic>/refactoring-reviews/<run>`. Exclude `logs/`,
  `agent-output/`, and `snapshot/`; a refactoring-review run also drops its `agents/` subtree
  (raw detector stdout/stderr and last messages), keeping the run-root plan, merged results,
  and report. Raw logs and agent output may contain sensitive fragments and must not be
  committed (the benchmark security boundary); the snapshot is a nested worktree. The rest is
  the audit trail the review procedure requires, and `report`/`rescore` still work against the
  archive with `--output <ledger-checkout>/<topic>`.
- Work in a dedicated ledger worktree: fetch `origin/llm-ledger` and check it out there,
  creating the branch from an orphan root when absent; copy the runs into the paths above,
  `git add` (an orphan root carries no ignore rules), commit, and push. On a non-fast-forward
  rejection, rebase onto the fetched tip and retry; topic-named directories keep concurrent
  appends disjoint. Remove the delivery worktree only once the pushed tip contains the topic.

## Local Verification

- **Code:** use targeted tests while iterating, run `mvn spotless:apply`, and finish with green
  `mvn test`. A full suite passing on the final code also satisfies targeted verification;
  avoid rerunning solely for a second workflow step. Cancellation, context propagation,
  executor binding, and queue changes require added or updated tests.
- **Documentation only:** check the diff, referenced paths, and cited commands against source
  configuration; run the applicable documentation gates. Java tests/formatting apply only
  if executable code or build behavior also changes.
- **Maven execution:** run build/test/PIT serially within one checkout. Independent verification
  checkouts may run in parallel.
- **Mockito attach failure:** if inline-mock tests fail because self-attach is unavailable,
  treat it as environmental and rerun with the jar matching `mockito.version` in `pom.xml`:

  ```bash
  mvn -o test -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/<mockito.version>/mockito-core-<mockito.version>.jar
  ```

  Replace both placeholders with the POM value. Successful self-attach with only a deprecation
  warning needs no workaround.

## Commit And Preflight

- Commit only this change's files, leaving unrelated modifications/staging intact. Use
  Conventional Commits with a lowercase summary (`feat:`, `fix:`, `refactor:`, `docs:`, `test:`).
- Behavior-changing commits or PR descriptions list every contract MUST/MUST NOT added or
  changed. Verification/workflow contract commits use explicit `MUST:` and `MUST NOT:` body
  lines. No changed contract section may be silently omitted.
- **Code refactoring:** the project refactoring-review skill runs once after the behavior
  commit, before adversarial review or delivery. `scripts/refactoring-review.py plan` decides
  whether the diff counts and assigns read-only detectors through `refactoring-review/AGENTS.md`.
  Documentation-only/comment-only diffs do not count. Accepted behavior-preserving refactors
  land as separate `refactor:` commits with unchanged test assertions; those commits do not
  trigger another refactoring-review run.
- **Committed-tree preflight:** after the initial commit, inspect the exact committed tree.
  For code changes, run every CI/CD job reproducible
  locally, regardless of the selected task route: documentation, Maven, runtime-consumer,
  static-analysis, and packaging jobs. The hidden-contract job follows the root Context Loading
  exception for `verification/contract-tests/`. Documentation-only changes follow Local Verification
  instead of running Java jobs. Fix a failure in a follow-up commit and rerun the affected
  job before pushing. Local preflight does not replace remote CI.
- **Documentation lifecycle:** delivery includes maintained documentation and in-flight
  proposals. Before landing/retiring proposals or committing/deleting analysis, read
  [documentation maintenance](documentation.md) and `design/proposal-lifecycle.md`.
  Analysis drafts are temporary inputs; commit them only when explicitly requested, under
  `design/analysis/`. Harvest conclusions before deleting them. Dormant proposals live under
  `design/archive/proposal/` and are not default reading.

## Delivery And CI

- **Direct development delivery:** rebase onto the freshly fetched development tip, rerun
  checks whose inputs incoming commits changed, and fast-forward push. On rejection, repeat
  from the rebase. Root approvals still govern releases, deployment, and `main` integration.
- **Named branch or existing PR:** push a separate branch only when the user names it or an
  open PR already carries the work. Update the PR description and verification for its final
  commits with the [pull-request skill](../../pull-request/SKILL.md). Contributors without write
  access open a PR per `CONTRIBUTING.md`.
- **Unattended issue runner:** in `scripts/issue-agent.py` sessions, commit locally only. The
  runner pushes `auto/issue-*`, opens a PR into the current development line, and merges only
  after its own gates. Read `design/issue-automation.md`; this branch overrides direct push.
  Agent PRs never target `main`.
- **CI monitoring:** after a direct or PR push, monitor every required check to terminal status
  for that pushed `head_sha` only (`python3 scripts/watch-delivery-ci.py <sha>`). Wait through
  queued/running jobs; inspect and fix failures, push the fix, and monitor the new tip. Apply
  environment-specific fixes here when local preflight could not expose them. A later SHA
  from another delivery is not yours to watch, retry, or fix. Record an external blocker
  instead of claiming completion when a required check cannot pass.
