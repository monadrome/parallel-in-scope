# Backlog

Changes planned before a specific release. Landed items are removed rather
than struck through; the changelog and Git history keep the record. Open
design questions live in `design/proposal/`, not here. Entries cite the
retired inventory ids once for traceability.

## Before the v0.5.0 release

- At the release merge, replace `blob/dev/v0.5.0/` with `blob/main/` in the
  7 source links of `docs/en/index.md` and `docs/zh/index.md` (find them
  with `grep -rn "blob/dev/v0.5.0" docs/`). All are live navigation to
  GitHub-only demo and integrations content; none are dated notes, so no
  per-link judgment is needed. Flipping earlier 404s the integrations
  links (no `integrations/` tree on `main` yet) and points the demo links
  at pre-v0.5.0 content (was O-18).
