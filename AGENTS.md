# AGENTS.md

## Project

**parallel-in-scope** is a structured-concurrency toolkit built on Guava `ListenableFuture`
and Alibaba `TransmittableThreadLocal`. Versions live in `pom.xml`.
Build with JDK 25: library release 8, tests release 11/JUnit 6; demo and verification consumers
retain JUnit 5 for Java 8. Core consumes the standalone `blocking-queues` artifact; its queue
implementations and tests belong to that separate repository, not this reading scope.

## Current Development Line

- All new work targets `dev/v0.5.0`, including agent pull requests; never develop on `main`.
- `.agents/project-baseline.json` declares the development line and upstream ref.
- Library, demo, and default verification consumer use `0.5.0-SNAPSHOT`; stable installation
  examples stay on `0.4.1`. Release integration and syncing default-branch workflows or
  Dependabot configuration are separate maintainer actions.
- For release/snapshot or development-line maintenance, read Current Development Line in
  [.agents/skills/project-delivery/references/operations.md](.agents/skills/project-delivery/references/operations.md).

## Context Loading

Read this root file, select the task route, then load only matching subject documents and
scoped `AGENTS.md` files. Do not pre-read all contracts, ADRs, proposals, or skill references.
A request for analysis authorizes analysis, not applying its proposals.

For **every modification**, use the project-level
[project-delivery skill](.agents/skills/project-delivery/SKILL.md) before editing. It owns the
complete workflow and routes conditional procedures. It is not required for read-only questions.
Required project skills are tracked in `.worktreeinclude.yaml`; a new worktree must pass
`python3 scripts/check-worktree-skills.py` before invoking a skill.

## Task Routes

Classify by the smallest change satisfying the request. Routes are cumulative; the more
restrictive gate wins. If the task crosses into a gated route, stop and reclassify.

| Request shape | Route | Do not |
|---|---|---|
| Mechanical, non-behavioral one-file edit | Edit, narrow check, commit, lightweight independent review | Write a proposal or run full adversarial review |
| Reproducible behavior defect | Reproduce first, fix the root cause, regression test, reverse verification | Fix by inspection or finish without a failing case |
| Public API, documented contract, or new mechanism | Read `design/first-principles.md` and owning contract; write/update a proposal; stop for maintainer direction | Implement before direction is settled |
| Cancellation, interruption, deadline, visibility, context propagation, executor binding, or queue behavior | State invariants/interleavings; test every race using shared fixtures; run registered race-claim checks, full suite, and adversarial review | Treat compilation or one green run as proof |
| Documentation/site only | Check diff, referenced paths and commands; run `python3 scripts/check-doc-refs.py`; lightweight independent review | Run Java tests or formatting unless another route applies |
| Dependency/build | Patch/minor: dependency-audit skill, `mvn test`, local runtime-consumer matrix | Add a dependency or take a major upgrade without asking |
| Release, snapshot, merge into `main`, or issue-runner delivery | Maintainer action, or `design/issue-automation.md` for the runner | Initiate, push, or merge outside that route |

Workflow/governance contracts take the documented-contract route, with explicit maintainer
direction replacing a proposal. Their commits require `MUST:` and `MUST NOT:` body lines.
When public API/contract direction is settled in session and lands immediately, update the
owning contract directly rather than create a transient proposal; record rejected alternatives
under the documentation-maintenance decision-rationale rules.

## Commands

Run from the repository root; select checks by route, not by copying the whole catalog.

```bash
mvn test -Dtest='ClassName#methodName' # targeted test
mvn test                            # full library suite
mvn spotless:apply                  # Java changes only
python3 scripts/check-doc-refs.py
python3 scripts/check-local-links.py
python3 scripts/check-worktree-skills.py
python3 scripts/check-project-baseline.py
```

The [command reference](.agents/skills/project-delivery/references/commands.md) owns specialized
checks, skill-bundle maintenance, static analysis, metrics, and CI-watching commands.

## Design Decisions

Optimize correctness, API coherence, maintainability, and user experience across the affected
path, not just diff size or local complexity. Trace callers/shared mechanisms within the reading
scope; count complexity moved to other components or users. Explain material tradeoffs and
report unrelated improvements separately. Optimize for structured concurrency and user safety
and convenience; prefer parameterizing existing mechanisms.
For new capabilities/APIs/mechanisms, apply `design/first-principles.md` before implementation.

Before introducing or changing direct low-level concurrency primitives (`Thread.interrupt()`,
`synchronized`), explain why higher-level alternatives are insufficient, present the mechanism
and risks, and obtain explicit human confirmation. The ordered ladder is in
`refactoring-review/lenses/concurrency.md`.

## Key Conventions

For changes to code or prose, implementers and reviewers read
[code/prose conventions](.agents/skills/project-delivery/references/conventions.md): it owns
Javadoc/comment layers, spelling, Java naming, nullability, checks,
logging, and public API evolution. Read `core/AGENTS.md` for core architecture and interruption
invariants; `demo/AGENTS.md` and `integrations/AGENTS.md` govern their separate consumers.

New maintainer/agent-facing documentation is English; Chinese user-facing documentation may
remain Chinese. Before changing, translating, renaming, or deleting existing Chinese
maintainer-facing content, prepare its inventory, proposed wording, and affected references
for explicit human approval. Preserve quotes, proper names, code, and historical records.

## Independent Review

Every non-empty change requires read-only review of the complete commit diff by a different
model, including documentation, skills, scripts, tests, and ledger metadata. Compare the review
input manifest with `git diff --name-only` for the range; obtain supplementary coverage for
omitted paths. No available different-model reviewer means report the review accordingly,
not a silent same-model substitution. A failed/timed-out/unreadable review is blocked, not clean.
The [review procedure](.agents/skills/project-delivery/references/review.md) owns retries,
coverage, dispositions, and evidence.

## Adversarial Review

Public API, documented-contract, concurrency/security-sensitive, and other high-risk changes
require full independent review after the initial commit via the project multi-review skill.
Read Adversarial Review in the [review procedure](.agents/skills/project-delivery/references/review.md).
Executable core changes and mutation-relevant changes also route to `verification/pit.md`.
PIT authorization is not a release authorization; preserve raw statuses and do not claim
incomplete or unresolved timeout evidence as a pass.

## Verification And Completion

The [project-delivery workflow](.agents/skills/project-delivery/references/workflow.md) owns
verification, autonomous completion, commits, preflight, ledger, rebase/push, and CI monitoring.
A change request authorizes that whole prescribed route, not unrelated outward actions.
Stop for undecided direction, required approval, or an unremovable blocker; finish independent
work first and ask once. Do not claim completion with required checks blocked or pending.

For code: targeted tests while iterating, Java formatting, final green full suite, then
post-commit refactoring-review and independent review. For docs only: referenced paths,
commands, applicable documentation gates, and independent review; no Java tests/formatting.
Track failed checks and decision stops as they occur; every non-mechanical delivery gets a
separate ledger commit. Run Maven build/test/PIT serially within a checkout. Report each
check separately as pass/fail/blocked/inconclusive/skipped, explaining every non-pass.

## Issue Tracking

Maintainer work needs no issue. Public API/contract proposals and PRs must show user code
before/after and the failure mode removed; breaking changes name migration in the changelog.
For issue/PR maintenance, load Issue Tracking in
[operations](.agents/skills/project-delivery/references/operations.md); external contributors
also follow `CONTRIBUTING.md`. The optional issue runner follows `design/issue-automation.md`.

## Permissions

Authorized: local tests, Java formatting, repository checks using the existing toolchain and
installed dependencies; PIT under its verification contract; deleting tracked files retired
or replaced by this change, recording where their behavior/conclusions live. Patch/minor
upgrades of dependencies/plugins in the three audited POMs require dependency-audit plus
passing library tests and the local runtime-consumer CI job.

Ask before adding dependencies/plugins, major upgrades, changing build JDK/Java 8 baseline,
editing `.github/workflows/` or `mkdocs/requirements.txt`, deleting others' untracked or out-of-scope
files, full release builds, or `mvn deploy`. Never commit secrets, `.env`, keys, or credentials.
A change request does not authorize releases, merging into `main`, or messages outside the session.

## Document Routes

Load only the matching row, then follow that document's subject-specific routes.

| Subject | Read when relevant |
|---|---|
| Execution engine, cancellation, groups, context, extensions, proposals | `design/AGENTS.md`: match summaries; current contracts govern behavior; proposals/analysis do not |
| Core architecture, token binding, prepared task views, interruption | `core/AGENTS.md` |
| Documentation, instruction, or skill changes | [documentation maintenance](.agents/skills/project-delivery/references/documentation.md): audiences, references, design lifecycle, decision rationale |
| New capability/tradeoff or reopening rejected direction | `design/first-principles.md`, `design/decision-log.md`, `docs/zh/design/philosophy.md`, `docs/zh/design/idea-graveyard.md` |
| ADR rationale or boundary decision | `adr/AGENTS.md` and subject-linked ADRs; live behavior contracts take precedence except ADR-owned boundaries |
| Refactoring-review | `refactoring-review/AGENTS.md` and its project skill; detectors read assigned lenses only |
| Races or ordering claims | `verification/race-claims/README.md`; every race gets a fixture-based test; run `python3 scripts/check-race-claims.py` for registered claims |
| Lincheck tests | `verification/lincheck.md` before changing `*LincheckTest` |
| PIT or SpotBugs | `verification/pit.md` or `verification/spotbugs.md` before selecting/running the respective scope |
| User-facing behavior | Update `docs/en/user-guide.md`; canonical dev quickstart is `docs/{en,zh}/quickstart.md` |
| Demo/integrations | Their scoped `AGENTS.md`; integrations is standalone, outside root tests/CI |
| Site pages | `mkdocs/mkdocs.yml` navigation, locales, redirects when adding/renaming/moving docs pages |
| Planned release work | `BACKLOG.md`; verify entries against current sources; remove landed entries |
| Issue automation | `design/issue-automation.md` before changing the runner, labels, or issue workflows |
| Delivery record | `verification/delivery-ledger.md` before delivery or process-metrics changes |
| Java convention gate | `verification/java-conventions.md` when changing the gate or diagnosing a finding |

Check `git status --short design/` for uncommitted analysis when taking up design work.
Do not commit analysis drafts unless explicitly asked; harvest conclusions before deleting.
Archive proposals are not default reading; live documents must not depend on them.
