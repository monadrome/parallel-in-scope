# Change Workflow

Read for every modification, before editing. Commands and backticked repository paths in this
skill and its references are relative to the repository root unless stated otherwise.
The root task routes and permissions still apply; this workflow grants no additional authority.

## Autonomous Completion

- A request to change something authorizes the whole route this file prescribes:
  implementation, tests, formatting, documentation and changelog notes, the
  review-and-fix loop, commit, and delivery. Do not wait for confirmation between
  these steps. Stop only for:
  - a direction the request leaves undecided: public API shape, contract
    semantics, a concurrency protocol, or a large refactor it did not name;
  - an action that Permissions or Design Decisions says to confirm first, or an
    outward action this file does not prescribe (a release, a merge into
    `main`, a message to people outside the session);
  - a blocker you cannot remove.
- Finish all work that does not depend on the open point first, then ask once:
  list each open decision with your recommendation and what it changes.
- Settle implementation choices yourself instead of presenting options. Design
  for the best end state; in `0.x`, compatibility and migration cost are not
  selection criteria, and the changelog records the break.
- A request for analysis authorizes the analysis, not applying its proposals.
- Start new work from the freshly fetched development line named under Current
  Development Line, not from a stale local branch or `main`.
- Another agent may share this checkout. Before the first edit and before each
  commit, look for its signs: modified or untracked files you did not create,
  local commits ahead of the fetched development line that are not yours, or a
  branch or HEAD that moved under you. When you find any, move your work to a
  worktree of your own instead of working around it:
  `git worktree add -b <topic> .worktrees/<topic> origin/dev/vX.Y.Z`. Carry over
  only your own changes, as a patch of your paths, then return those paths in
  the shared checkout to their committed state. Verify, commit, and deliver to
  the development line from the worktree. Do not commit, stash, reset, rebase,
  or switch branches in the shared checkout, and never push the other agent's
  commits. After delivery, remove the worktree and delete its branch.
- Retry a failure only after addressing its cause, or once when it is a known
  transient; report a failure that repeats unchanged. When an environment
  denies an action this file prescribes, leave the exact command in the report
  instead of working around the denial.
- When the user corrects the same behavior twice, propose the rule that would
  have prevented it. Changes to permissions or gates take effect only after a
  maintainer approves them; until then they do not widen the task that proposed
  them.

## Verification And Completion

- Behavior-changing commits list the contract MUST/MUST NOT items they add or
  change in the commit message or PR description, so no contract section is
  silently left behind. Verification and workflow contract commits use explicit
  `MUST:` and `MUST NOT:` body lines so the rule remains visible after delivery.
- Add or update tests when changing cancellation, context propagation, executor
  binding, or queue behavior.
- For code changes, use targeted tests while iterating, run `mvn spotless:apply`,
  and finish with a green `mvn test`. A full suite already passing on the final
  code satisfies the targeted-test requirement; do not rerun tests solely to
  satisfy another workflow step.
- For documentation-only changes, check the diff, referenced paths, and any
  commands against their source configuration; Java tests and formatting are
  unnecessary unless executable code or build behavior also changes.
- Complete applicable local verification before the initial commit. Fix failures
  caused by the change and rerun affected checks without pausing for review of
  the first implementation. Report unrelated failures or blockers; do not claim
  completion while required checks are blocked.
- After the behavior commit exists, run every CI/CD job that is reproducible
  locally (for example the workflow's documentation, Maven, runtime-consumer,
  static-analysis, and packaging commands) before pushing or waiting for the
  remote workflow. These preflight checks inspect the exact committed tree; if
  one fails, fix it in a follow-up commit and rerun the affected local job.
  Local preflight does not replace remote CI: after pushing, monitor the remote
  checks to a terminal result and apply any environment-specific fixes there.
  Attribute runs by the pushed tip: monitor only runs whose head_sha equals the
  pushed commit (`python3 scripts/watch-delivery-ci.py <sha>` does this), and
  rerun or fix only failures in those runs. A newer run at a later head_sha is
  another delivery's responsibility; do not watch, retry, or fix it.
- For every code change, run `/refactoring-review`
  (`.agents/skills/refactoring-review/SKILL.md`) after the behavior commit and
  before Adversarial Review or delivery. `scripts/refactoring-review.py plan`
  decides whether a diff counts (documentation-only and comment-only diffs do
  not) and how many read-only detectors cover which lenses; rules are routed
  by `refactoring-review/AGENTS.md`. Accepted refactors land as separate
  `refactor:` commits that leave test assertions unchanged; it runs once per
  change and never on its own refactor commits.
- Report each check separately and honestly: behavior tests, external consumer,
  independent review, and mutation coverage are distinct facts. Give each check
  one status (pass, fail, blocked, inconclusive, or skipped) and a reason for
  every status except pass. A PIT run that started but timed out, or
  produced no `mutations.xml`, is not a pass; never describe a started or partial
  check as passing. Run Maven build/test/PIT serially within one checkout;
  independent verification checkouts may run in parallel.
- Mockito's inline mock maker self-attaches its agent. On the JDK 25 build this
  currently succeeds with only a deprecation warning and the Mockito test classes
  pass without any javaagent, but self-attach is deprecated and will stop working
  on a future JDK, so treat an attach error as an environment-specific condition
  rather than a code defect. If the Mockito tests do fail with an attach error,
  rerun with the jar matching the `mockito.version` in `pom.xml` passed explicitly.
  The current POM value is `5.24.0`:

  ```bash
  mvn -o test -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.24.0/mockito-core-5.24.0.jar
  ```

  The `$HOME` form resolves on other checkouts without hardcoding a user path.
- Record every delivery except a mechanical edit in the delivery ledger
  (`verification/delivery-ledger.md`): after the change's last commit, run
  `scripts/process-metrics.py record` with the change's review runs, PIT
  reports, local gate failures, maintainer asks, and user corrections, and
  commit the record alone as `chore: record delivery <topic>`. Keep a count of
  failed required checks and of stops for a decision while you work; record
  unknown usage as unknown instead of estimating it.
- Before any development-line push, read Current Development Line in
  [operations.md](operations.md): an ordinary delivery can trigger snapshot publication.
  Preserve its publication safeguards; this route does not authorize manual deployment.
- After verification, commit and deliver to the development line: rebase onto
  its freshly fetched tip, rerun the checks whose inputs the incoming commits
  changed, and fast-forward push it; when the push is rejected, repeat from the
  rebase. Push a separate branch only when the user names one or an open pull
  request already carries the work, and then update that pull request's
  description and verification to match its final commits. Contributors
  without write access open a pull request as `CONTRIBUTING.md` describes.
- After pushing a pull request, actively monitor every required CI/CD check until
  it reaches a terminal result. Wait for queued or running jobs when necessary;
  do not report delivery complete while a required check is still pending or
  failing. If a check fails, inspect its logs, fix the cause, push the fix, and
  repeat the monitoring cycle until the required checks pass or an external
  blocker is recorded.
- Delivery includes documentation maintenance and in-flight proposals. Proposals
  are tracked under `design/proposal/`; their lifecycle is defined in
  `design/proposal-lifecycle.md`. Dormant proposals are archived under
  `design/archive/proposal/` and are not loaded by default. Analysis drafts are temporary inputs: do not
  commit them on your own initiative; commit one only when the user asks, under
  `design/analysis/` per [documentation maintenance](documentation.md) principle 9. Harvest their
  conclusions before deleting them either way.
- Unattended issue-runner sessions (`scripts/issue-agent.py`) override the push
  rule above: the agent commits locally only, and the runner pushes an
  `auto/issue-*` branch, opens a pull request into the current `dev/vX.Y.Z`
  line, and merges it only after its own gates. Agent pull requests never
  target `main`; see `design/issue-automation.md`.
- Commit only this change's files; leave unrelated modifications and staged
  changes uncommitted. Use Conventional Commits with a lowercase summary
  (`feat:`/`fix:`/`refactor:`/`docs:`/`test:`).
