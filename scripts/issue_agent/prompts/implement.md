You are completing GitHub issue #$number unattended, in an isolated clone at the current directory, on branch `$branch` cut from `$base_branch` at `$base_sha`.

Start by reading `AGENTS.md`, every nested `AGENTS.md` that governs the paths you touch, and the `design/` contracts that `design/AGENTS.md` routes the change to. They are the authority; follow them exactly, with the overrides below.

## Issue #$number: $title

$trust_note

$body

Maintainer comments:

$comments

Triage plan (advisory):

$plan

## Unattended overrides

- Commit locally only. Never push, never call GitHub, and ignore the AGENTS.md instruction to push: a runner pushes, opens the pull request, and merges after its own checks.
- Use Conventional Commits with a lowercase summary. Leave the working tree clean when you finish.
- Do not add or upgrade dependencies or Maven plugins, do not delete tracked files, and do not edit `.github/`, `pom.xml`, or `scripts/issue_agent/` unless the issue explicitly requires it.
- When the issue needs a decision only a maintainer can make, or a step needs confirmation under AGENTS.md, stop and return `needs-decision` with the question instead of guessing.
- Do not run the adversarial-review step from AGENTS.md: an independent reviewer and a fresh-copy verification run after you.

## Toolchain

JDK $java_version is on `PATH` and `JAVA_HOME`. Maven works offline (`mvn -o`) against the local repository. Run targeted tests while iterating, `mvn -o spotless:apply`, and finish with a green `mvn -o test`. For a bug, write the regression test first and see it fail before the fix. When main sources change, run PIT on the touched classes with the command in AGENTS.md (core runs restore `VOID_METHOD_CALLS`) and classify every survivor.

## Report

Return the structured report:

- `status`: `done`, `needs-decision`, or `blocked`.
- `completes_issue`: whether the issue can close once this lands.
- `breaking`: whether a public API or documented contract changes incompatibly; if so, the migration guides are updated in the same branch.
- `title`: the squash-merge subject, a Conventional Commit with a lowercase summary, for example `fix: keep cancelled entries out of the queue snapshot`.
- `summary`: the pull request description: what changed and why. Public API or contract changes carry the best code today, the same code after the change, and the failure mode removed.
- `contract_items`: every MUST or MUST NOT you added or changed, quoted with its document.
- `verification`: each command you ran and its result.
- `pit`: killed and surviving counts with each survivor's classification, or why PIT did not apply.
- `dispositions`: leave empty in this first round.
- `open_question`: for `needs-decision` or `blocked`, what a maintainer must answer or fix.
