---
name: project-delivery
description: Execute a repository change from implementation through verification, independent review, commit, delivery ledger, push, and CI monitoring. Use for changes to code, documentation, scripts, skills, or configuration in parallel-in-scope; not for read-only analysis or questions. Preserves the root task routes and approval boundaries.
---

# Project Delivery

Use this skill for a requested modification, not a read-only investigation. Classify the task
with the root `AGENTS.md` first; routes are cumulative and the most restrictive gate wins.
This skill orchestrates existing procedures rather than replacing their contracts or scripts.
All commands run from the repository root.

## Load Only What Applies

| When | Read |
|---|---|
| Every modification, before editing | [workflow.md](references/workflow.md) and [review.md](references/review.md) |
| Editing project-authored code or prose | [conventions.md](references/conventions.md) |
| Writing, moving, or deleting documentation, instructions, or skills | [documentation.md](references/documentation.md) |
| Selecting a less-common command, maintaining skills, or diagnosing a gate | Relevant section of [commands.md](references/commands.md) |
| Before any development-line push (including ordinary delivery), or release/snapshot/development-line maintenance | Current Development Line in [operations.md](references/operations.md); stop at root approval boundaries |
| Issue/PR maintenance or issue-runner delivery | Issue Tracking in [operations.md](references/operations.md), plus the routed issue-runner contract when applicable |

Do not preload every reference. Read the owning subject contract through `design/AGENTS.md`
when behavior is affected; archived proposals and historical ADRs are not default reading.

## Execute The Selected Route

1. Fetch the development line and check checkout ownership before editing. Isolate work if
   another agent's changes, commits, or HEAD movement appear; follow the workflow exactly.
2. Resolve only the open directions that require maintainer input. Reproduce defects before
   fixing them; state invariants/interleavings for concurrency changes. Keep scope bounded.
3. Implement with the applicable scoped instructions. Run the route's local checks and
   update documentation/changelog obligations before the initial commit.
4. Commit with the required contract statements. For code changes, run the existing
   refactoring-review skill after that commit and before independent review. Accepted
   behavior-preserving refactors remain separate commits.
5. Obtain read-only, different-model review of the complete commit diff. Apply the review
   procedure's lightweight/full classification, verified dispositions, and retry/stop rules.
   Workflow-contract changes require full review even when the files are Markdown only.
6. Finish applicable committed-tree preflight checks. Record the delivery in a separate ledger
   commit, rebase onto the freshly fetched development tip, push by the authorized route,
   and monitor only that pushed tip's CI to a terminal result.
7. Report separate honest statuses for checks, review, consumer verification, and mutation
   coverage. Required blocked checks mean delivery is not complete.

Use the existing dependency-audit, refactoring-review, and multi-review skills only when the
selected route calls for them. Assurance remains an optional, separately versioned bundle;
this skill must not copy its algorithms or host-adapter assumptions.
