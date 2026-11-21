---
name: project-delivery
description: Deliver repository changes. Use before modifying code, documentation, scripts, skills, or configuration in parallel-in-scope.
---

# Project Delivery

Follow the root `AGENTS.md` task routes and permissions. The steps below own the delivery
sequence; references supply branch-specific rules. Paths and commands are repository-relative.

## Authority And Stops

A change request authorizes the selected route through delivery, including tests, formatting,
documentation/changelog maintenance, review/fixes, commits, and push. Settle implementation
choices within that scope; design for the best end state in `0.x`, recording breaks in the
changelog rather than selecting for compatibility or migration cost.

Stop for an undecided public API, contract, concurrency protocol, or unnamed large refactor;
an approval required by root Permissions or Design Decisions; an unrelated outward action;
or an unremovable blocker. Finish independent work, then ask once with each open decision,
its recommendation, and its effect. Analysis alone authorizes no edits.

Retry after addressing the cause, or once for a known transient. Report an unchanged repeated
failure. If the environment denies a prescribed action, report the exact command rather than
working around the denial. After the same user correction twice, propose a preventive rule;
permission/gate changes require maintainer approval and cannot widen their originating task.

Track failed required checks, decision stops, and user corrections as they occur for the ledger.

## Delivery Steps

### 1. Establish The Route And Checkout

Fetch the development line declared in `.agents/project-baseline.json`; start new work there.
Select every applicable root task route and the nearest scoped instructions. Before the first
edit and each commit, check for unfamiliar modified/untracked files, other agents' commits
ahead of the fetched tip, and unexpected branch/HEAD movement. If any appear, follow
[Checkout Isolation](references/workflow.md#checkout-isolation) before proceeding.

**Done:** the checkout is owned, the base is freshly fetched, every applicable route is named,
and required direction/approvals are settled. Reclassify if the task later crosses a gate.

### 2. Implement And Verify Locally

Before editing code or prose, read [conventions](references/conventions.md). For documentation,
instruction, or skill edits, also read [documentation maintenance](references/documentation.md).
Before creating or editing skills, agent instructions, or documents reached through their
pointers, use [writing-for-agents](../writing-for-agents/SKILL.md).
Load subject contracts through the root routes; use [commands](references/commands.md) only
for the specialized check or maintenance task being performed.

Read [Local Verification](references/workflow.md#local-verification) for the code/docs branch.
Reproduce defects before fixing them; use the selected route's regression and concurrency gates.

**Done:** each requested change and its documentation/changelog obligations are accounted for,
all applicable local checks pass, and every required regression is reverse-verified. Fix
change-caused failures before the first commit; report unrelated failures or blockers.

### 3. Commit And Review

Read [Commit And Preflight](references/workflow.md#commit-and-preflight) before committing.
For code changes, run the project refactoring-review skill after the initial commit and before
independent review; its planner decides applicability and detector scope.

For every non-empty change, read Independent Review in [review](references/review.md).
For a high-risk route, also follow its Adversarial Review section through the project
multi-review skill. Workflow contracts take that full-review route even when Markdown-only.
Fix confirmed findings in follow-up commits and apply the review's re-review/stop rules.

**Done:** the committed tree passes applicable preflight; the review evidence covers every
changed path, including metadata; and no required review or confirmed blocking finding remains
unresolved. A failed, timed-out, or unreadable review is blocked, not clean.

### 4. Record And Deliver

For a non-mechanical delivery, read `verification/delivery-ledger.md` and record the change
after its last implementation/fix commit; commit the ledger alone as
`chore: record delivery <topic>`. Record unknown usage as unknown.

Before pushing, read Current Development Line in [operations](references/operations.md),
including for ordinary development delivery: its push can trigger snapshot publication.
Follow [Delivery And CI](references/workflow.md#delivery-and-ci) for direct delivery, an
existing PR, an external contributor, or the unattended issue runner, as applicable.

**Done:** delivery followed the authorized route, required CI for the pushed tip is terminal
and passing, and every check has its own pass/fail/blocked/inconclusive/skipped status with
a reason for each non-pass. Behavior tests, external consumers, review, and mutation coverage
are distinct results. Required pending or blocked checks mean delivery remains incomplete.
