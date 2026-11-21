# Release And Issue Operations

Read only the section matching the operation. This is not authorization to release or change workflows.

## Current Development Line

- All new work targets `dev/v0.5.0`, including agent pull requests. Do not
  commit to `main` or target it with development pull requests; release
  integration is a separate maintainer action.
- The library, demo, and default verification consumer track `0.5.0-SNAPSHOT`.
  Keep stable installation examples on the latest published release, `0.4.1`.
- Preserve the snapshot publishing policy: pushes to `dev/v0.5.0`, the daily
  scheduled Snapshot run, and manual Snapshot runs consult Central metadata and
  publish only when no snapshot exists or the branch tip's UTC date is later
  than the last publication's UTC date. Verify the library and demo before
  deploying. The schedule lives in `.github/workflows/snapshot.yml`; GitHub
  fires `schedule` only from the default branch's copy of that file, so opening
  a development line or changing the workflow includes syncing that copy on
  `main` -- a maintainer action outside the development line. A matching
  `v0.5.0-SNAPSHOT` tag remains the manual publication path that bypasses this
  date gate; snapshot tags never create a GitHub Release.
- Dependabot reads `.github/dependabot.yml` from the default branch. Sync that
  file to `main` as a maintainer action; its update entries target `dev/v0.5.0`.

## Issue Tracking

- Maintainer work needs no issue. Decisions live in the implementing PR and,
  when extended reasoning is needed, a `design/` document.
- Every public API or documented-contract proposal and PR must show the best
  user code today, the same code after the change, and the failure mode removed.
  Breaking changes must name the migration path in their changelog entry; PRs
  missing this rationale are not mergeable.
- Open issues for public input, downstream discoverability, or deferred work.
  Use `.github/ISSUE_TEMPLATE/`: `design_proposal.yml` for capabilities/contracts,
  `bug_report.yml` for defects, `documentation.yml` for guides/Javadoc.
- External contributors file an issue first except for the trivial changes
  listed in `CONTRIBUTING.md`.
- PRs implementing issues link them with `Closes #NN` / `Refs #NN`; otherwise
  the PR description must be self-contained. Before opening or updating an
  issue, read `CONTRIBUTING.md` section "Issue maintenance" for ownership,
  direction updates, and milestone rules.
- Before a drafted PR description is posted for review, run it through a ZCode
  pass that optimizes it for a general reader and drops unnecessary detail:
  each verification states what was run and what it found, and run-by-run
  counts, round-by-round history, and tool idiosyncrasies stay in the commit
  messages, the change's `design/` document, the review run directory, or the
  delivery ledger rather than the description. Honest statuses and the links
  to the evidence survive the pass.
- Issues may opt into the agent runner. Triage suggests `priority/*`, a type,
  and `agent/eligible` or `needs-decision`; a maintainer's `agent/ready`
  authorizes a run and `needs-decision` vetoes one. The runner is optional:
  nothing in the normal flow depends on it. Its trust boundary, labels, and
  gates are in `design/issue-automation.md`.
