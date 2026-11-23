# AGENTS.md

## Project

**parallel-in-scope** is a Java structured-concurrency toolkit built on Guava `ListenableFuture`
and Alibaba `TransmittableThreadLocal`. Build and dependency versions live in `pom.xml`;
executable verification commands live in `.github/workflows/`.
The standalone `blocking-queues` implementation and tests belong to its separate repository.

## Current Development Line

- All new work targets `dev/v0.5.0`, including agent pull requests; never develop on `main`.
- `.agents/project-baseline.json` is the checked source for the development line and upstream.
- Before any development-line push, or release/snapshot/development-line maintenance, read
  Current Development Line in
  [operations](.agents/skills/project-delivery/references/operations.md): publication safeguards,
  release/example versions, and default-branch maintainer actions.

## Context Loading

Select the task route below, then load matching subject documents and scoped `AGENTS.md` files.
Follow each pointer when its stated condition applies; leave other references unloaded.
A request for analysis authorizes analysis, not applying its proposals.

For **every modification**, use the project-level
[project-delivery skill](.agents/skills/project-delivery/SKILL.md) before editing. It owns the
delivery sequence and its completion criteria. Read-only questions use the subject routes only.
Required project skills are tracked in `.worktreeinclude.yaml`. A new worktree must pass its
own skill check before invoking a skill; the script inspects the checkout that contains it, so
run it inside that worktree (see Commands).

### Exploratory Design Loading

For option generation in a large refactor, removal, or dependency redesign, start with the
requested end state, explicit requirements, first principles, and live correctness/safety
invariants. Treat current APIs and implementations as candidates for change, not defaults that
a proposal must preserve.

Load current-usage and historical-preference evidence for candidate evaluation, after options
exist: usage examples and selling points, implementation/test structure, adoption and migration
costs, and historical rejection records. Scoped `AGENTS.md` files own directory-specific timing.
Before recommending or implementing, account for every affected user-visible behavior, document,
test, and migration path. Existing usage informs the impact assessment but does not veto a better
`0.x` design; unknown usage remains unknown.

Defect fixes and behavior-preservation requests treat reproduced behavior and its contracts as
requirements from the outset. A blanket documentation ban is rejected: deferred evidence is
mandatory before a decision.

## Task Routes

Classify by the smallest change satisfying the request. Routes are cumulative; the more
restrictive gate wins. If the task crosses into a gated route, stop and reclassify.

| Request shape | Route | Do not |
|---|---|---|
| Mechanical, non-behavioral one-file edit | Edit, narrow check, commit, lightweight independent review | Write a proposal or run full adversarial review |
| Reproducible behavior defect | Reproduce first, fix the root cause, regression test, reverse verification | Fix by inspection or finish without a failing case |
| Public API, documented contract, or new mechanism | Read `design/first-principles.md` and owning contract; apply [assurance routing](.agents/skills/project-delivery/references/assurance.md); write/update a proposal; stop for maintainer direction | Implement before direction is settled |
| Cancellation, interruption, deadline, visibility, context propagation, executor binding, or queue behavior | State invariants/interleavings; test every race using shared fixtures; run registered race-claim checks, full suite, and adversarial review; apply [assurance routing](.agents/skills/project-delivery/references/assurance.md) | Treat compilation or one green run as proof |
| Documentation/site only | Check diff, referenced paths and commands; run `python3 scripts/check-doc-refs.py`; lightweight independent review | Run Java tests or formatting unless another route applies |
| Dependency/build | Patch/minor: dependency-audit skill, `mvn test`, local runtime-consumer matrix | Add a dependency or take a major upgrade without asking |
| Release, snapshot, merge into `main`, or issue-runner delivery | Maintainer action, or `design/issue-automation.md` for the runner | Initiate, push, or merge outside that route |

Workflow/governance contracts take the documented-contract route, with explicit maintainer
direction replacing a proposal. Their commits require `MUST:` and `MUST NOT:` body lines.
When public API/contract direction is settled in session and lands immediately, update the
owning contract directly rather than create a transient proposal; record rejected alternatives
under the documentation-maintenance decision-rationale rules.

## Commands

Run from the repository root, except the worktree skill check, which runs inside the new
worktree. Select checks by route, not by copying the whole catalog.

```bash
mvn test -Dtest='ClassName#methodName' # targeted test
mvn test                            # full library suite
mvn spotless:apply                  # Java changes only
python3 scripts/check-doc-refs.py
python3 scripts/check-local-links.py
(cd .worktrees/<topic> && python3 scripts/check-worktree-skills.py)  # inside the new worktree
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

For implementation or review of code or prose, read
[code/prose conventions](.agents/skills/project-delivery/references/conventions.md): it owns
Javadoc/comment layers, spelling, Java naming, nullability, checks,
logging, and public API evolution. For documentation language and the approval boundary around
existing Chinese maintainer content, read Documentation Audiences in
[documentation maintenance](.agents/skills/project-delivery/references/documentation.md)
before editing that content.

## Independent Review

For every non-empty change, read Independent Review in the
[review procedure](.agents/skills/project-delivery/references/review.md) before commissioning
review: different-model independence, complete diff coverage, retries, and evidence.

## Adversarial Review

For public API, documented-contract, concurrency/security-sensitive, or other high-risk changes,
read Adversarial Review in the [review procedure](.agents/skills/project-delivery/references/review.md)
after the initial commit. For executable production, mutation-relevant test/helper, build/PIT
configuration changes, or investigation of a PIT result, read `verification/pit.md` before
selecting scope or reporting completion.

## Verification And Completion

For a change request, follow the [project-delivery skill](.agents/skills/project-delivery/SKILL.md)
through its final completion criterion. Its steps route verification, commits, review, ledger,
delivery, and CI monitoring; its stop conditions preserve the approval boundaries below.

## Issue Tracking

For issue/PR maintenance or a public API/contract proposal, read Issue Tracking in
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
| Races or ordering claims | `verification/race-claims/README.md`; run `python3 scripts/check-race-claims.py` for registered claims |
| Lincheck tests | `verification/lincheck.md` before changing `*LincheckTest` |
| PIT or SpotBugs | `verification/pit.md` or `verification/spotbugs.md` before selecting/running the respective scope |
| User-facing behavior | Follow `docs/AGENTS.md`; update `docs/en/user-guide.md`; canonical dev quickstart is `docs/{en,zh}/quickstart.md` |
| Demo/integrations | Their scoped `AGENTS.md`; integrations is standalone, outside root tests/CI |
| Site pages | `mkdocs/mkdocs.yml` navigation, locales, redirects when adding/renaming/moving docs pages |
| Planned release work | `BACKLOG.md`; verify entries against current sources; remove landed entries |
| Issue automation | `design/issue-automation.md` before changing the runner, labels, or issue workflows |
| Delivery record | `verification/delivery-ledger.md` before delivery or process-metrics changes |
| Java convention gate | `verification/java-conventions.md` when changing the gate or diagnosing a finding |
| Verification integrity gate | `verification/verification-integrity.md` when changing tests or verification infrastructure, or diagnosing a finding |

For design work, check `git status --short design/` for uncommitted analysis and follow the
analysis/proposal lifecycle in documentation maintenance before committing or retiring drafts.
