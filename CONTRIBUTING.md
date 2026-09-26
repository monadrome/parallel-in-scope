# Contributing to parallel-in-scope

Thanks for your interest in contributing! Bug reports, documentation fixes,
tests, small bug fixes, and design proposals are all welcome. This file is the
short entry point for human contributors; the detailed engineering conventions
live in [AGENTS.md](AGENTS.md), which this document links to rather than
repeats.

## Before you write code: file an issue first

For external contributors, most changes start with an issue — not as ceremony,
but because the issue is where the direction is agreed before anyone invests in
a pull request. (Maintainer-driven work may skip this step; see
[AGENTS.md — Issue Tracking](AGENTS.md#issue-tracking). Contributions arriving
as pull requests still follow the rules below.)

**An issue is required first for:**

- a new capability; a new public type, method, or option
- a change to existing behaviour or to a documented contract
- a signature change; anything that needs a `design/` proposal

**No issue needed for:**

- renames, wording and typo fixes
- small bug fixes whose root cause is obvious
- test-only repairs
- internal refactors that leave public signatures and contracts untouched
- dependency or version bumps, and routine maintenance

When in doubt, file the issue — even for a change you think is small, letting
others know what you are doing helps.

Don't surprise the maintainers with a large pull request for something that
required an issue: open the issue, agree on the direction there, then code. If
you plan to work on an existing issue, leave a comment so effort is not
duplicated.

### Which form to use

The issue forms live in [.github/ISSUE_TEMPLATE/](.github/ISSUE_TEMPLATE/):

| Form | Use it for |
|---|---|
| **Bug report** | Something behaves differently from what the documentation promises |
| **Design proposal** | A new capability, or a change to existing behaviour or API |
| **Documentation issue** | A page, javadoc, or example that is wrong, missing, or misleading |

Blank issues are also enabled for anything that does not fit a form. The
new-issue page links the [user guide](docs/en/user-guide.md), the
[migration notes](docs/en/migration-v0.3.md), and
[design/first-principles.md](design/first-principles.md) — the criteria a
proposal is judged against. Ideas already weighed and declined are recorded in
the [idea graveyard](docs/zh/design/idea-graveyard.md)
([en](docs/en/design/idea-graveyard.md)); check there before proposing.

## Set up and verify locally

The library targets Java 8 (`src/main/java` must stay on Java 8 APIs); tests
compile at release 11, so build with JDK 11 or newer and Maven.

```bash
mvn test                               # all tests
mvn test -Dtest='ClassName#methodName' # targeted test while iterating
mvn spotless:apply                     # format Java sources before committing
```

For a code change, finish with a green `mvn test`. Documentation-only changes
do not need Java tests — see
[AGENTS.md — Verification And Completion](AGENTS.md#verification-and-completion).

## Commits and pull requests

- Work on a branch; do not push to `main` directly. Open a pull request
  against `main`.
- Commit messages follow Conventional Commits with a lowercase summary, e.g.
  `feat: add batch deadline option`, `fix: drain queue before close`,
  `docs: clarify cancellation contract` (see
  [AGENTS.md — Git Workflow](AGENTS.md#git-workflow)).
- Link the pull request to its issue: `Closes #NN` when the PR completes the
  issue, `Refs #NN` when it is one step of it. Changes that don't require an
  issue (list above) may omit the link.
- The pull request template asks for what changed and why, the verification you
  ran, and any breaking changes — keep it short, but fill it in.
- A PR may be opened early to discuss direction, but it is merged only when
  the applicable verification above is green.

### Breaking changes

The library is in `0.x`: breaking changes are acceptable when they carry a
documented rationale. If your change renames or alters a public API or a
documented contract, update [docs/en/migration-v0.3.md](docs/en/migration-v0.3.md)
and [docs/zh/migration-v0.3.md](docs/zh/migration-v0.3.md) in the same pull
request, and state the rationale in the PR description.

## Design proposals

Direction discussions happen either in a design proposal issue (form above) or
directly in a document under `design/` that ships with the implementing pull
request — the maintainer chooses the venue; the document carries the reasoning
either way. Whether in an issue or a PR, a proposal that touches public API or
a documented contract must state specifically: the best code a user can write
today, the same code with the change applied, and the failure mode the change
removes; breaking changes additionally name the migration path. Before changing
execution-engine, cancellation, task-group, or queue behaviour, read
[design/AGENTS.md](design/AGENTS.md) — it routes you to the current design
contracts, which are the authority for how the library behaves.

## License

parallel-in-scope is licensed under the [Apache License 2.0](LICENSE). By
submitting a contribution, you agree that it is licensed under the same terms.
