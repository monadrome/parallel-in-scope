# Refactoring-Review Report Template

The main agent writes `report.md` in the run directory with these sections. Keep every disposition,
including rejections: the reasons are what a later run needs.

```markdown
# Refactoring review: <topic>

- Base / behavior head: <base sha> / <head sha>
- Plan: <mode>, <agent count> detectors, lenses <ids>; uncovered: <none or list>
- Rules read from: <base | head (absent at base)>

## Detectors

| Agent | Lenses | Reviewer | Status | Findings |
|---|---|---|---|---|

Status is completed, rerun-completed, or blocked (with the failure); blocked is never empty.

## Dispositions

| Merged id | Rule | Symbol | Tier (detector -> final) | Disposition | Reason |
|---|---|---|---|---|---|

Disposition is one of:
- applied: landed in a refactor commit (name it)
- rejected: wrong, suppressed by a higher source (cite it), or already rejected in
  `design/decision-log.md` or `docs/zh/design/idea-graveyard.md`
- deferred: T2 or over a stop rule; give the destination (BACKLOG entry, proposal, open item)
- reverted: applied, then undone because verification failed or a test assertion had to change

## Verification

One status per check (pass, fail, blocked, inconclusive, skipped) with a reason for every non-pass:
spotless, targeted tests, full `mvn test`, `check-race-claims.py` (T1 concurrency items),
`refactoring-review.py verify`, PIT applicability per `verification/pit.md`.

## Proposed checks

Mechanizable findings and the deterministic check each one should become.

## Handoff to code review

- Review range: <base>..<HEAD>; refactor commits: <shas>
- Attack surface: behavior preservation of each refactor commit, plus each applied T1 item
- Settled findings code review should not re-raise: <merged ids>
```
