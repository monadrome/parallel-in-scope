# Backlog

Changes planned before a specific release. Landed items are removed rather
than struck through; the changelog and Git history keep the record. Open
design questions live in `design/proposal/`, not here. Entries cite the
retired inventory ids once for traceability.

## Before 1.0.0 — breaking API changes, land as one batch

- Expose `TaskGroupResult.failedTask()` and unify combine attribution:
  `failedTaskName()` can name the terminal combine, which `members()` does
  not contain, so `members().get(failedTaskName())` fails on a combine
  failure (was A-P3). Land together with the `PublicApiSurfaceTest`,
  migration-note, and user-guide updates.

## Before the v0.5.0 release

- Flip the `blob/dev/v0.5.0/` source links in `docs/` when the release
  merges to `main`: live contracts and guides point at `main`, dated notes
  may keep the dev snapshot. There is no link-flip automation; decide per
  link in the release PR (was O-18).

## Small fixes

- Route the `ParRuntime` registration warnings through the guarded
  `announce` idiom used by the other log stations, so a throwing user JUL
  handler cannot alter control flow (audit follow-up, 2026-10-04).
