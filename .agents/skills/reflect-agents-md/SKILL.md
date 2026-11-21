---
name: reflect-agents-md
description: Audit AGENTS.md files for scope routing, conflicts, stale rules, duplication, and context burden; use only when the user explicitly invokes this skill or asks for an AGENTS.md audit.
---

# Reflect AGENTS.md

This is an explicit audit skill. The user manually invokes it when they want to review whether the applicable `AGENTS.md` rules actually took effect. It is especially useful near the end of a session, after the main work and validation are visible, but end-of-session invocation is optional. Do not invoke it for ordinary repository work, and do not treat its presence as permission to rewrite `AGENTS.md` files.

## Scope

Audit one or more `AGENTS.md` files and, when requested, assess whether the current session followed the rules that applied to it. The default output is a read-only report. Only edit, split, delete, or reorder instruction files when the user explicitly asks for remediation.

## Workflow

1. Establish the audit target:
   - For a repository audit, find every relevant `AGENTS.md` from the workspace root through nested directories. Include the nearest parent instruction files that govern the target paths.
   - For a session audit, inspect the user request, tool calls, changed files, commands, tests, commits, and final claims that are available in the current session.
   - Preserve the user's requested boundary. Do not scan unrelated repositories or global configuration unless asked.
2. Build a scope map. For each instruction, record the path it governs, whether it is global or local, and which narrower file takes precedence when scopes overlap.
3. Classify each rule as always applicable, route-dependent, a validation or delivery gate, or historical rationale/reference material.
4. Check for concrete problems:
   - conflicting instructions at the same or overlapping scope;
   - rules whose referenced files, commands, branches, tools, versions, or paths no longer exist;
   - duplicated requirements that increase context without adding a decision;
   - detailed procedures placed in a global file even though they apply to one route or module;
   - vague, non-verifiable, or overly broad wording;
   - rules that silently expand task scope or imply permission for external actions;
   - missing routing for a high-risk module or workflow;
   - mismatch between the rules and actual repository structure or session behavior.
5. Separate evidence from recommendations. Cite file paths and headings or rule text; avoid relying on line numbers because they drift.
6. Report findings in priority order:
   - **Blocking**: conflict, missing mandatory gate, invalid path/command, or unsafe execution risk.
   - **High**: stale or misleading rule, or routing gap for a high-risk task.
   - **Medium**: duplicated or misplaced detail that creates meaningful context burden.
   - **Low**: wording, naming, or organization improvements.
7. For every finding, state the evidence, affected scope, practical consequence, and smallest recommended change. Mark uncertain conclusions as `inconclusive` and explain what evidence is missing.

## Session compliance mode

When the user asks whether a session used `AGENTS.md` correctly:

- distinguish rules that were applicable from rules that were irrelevant to the task;
- distinguish “not used because not applicable” from “missed”;
- check whether the session classified the request, loaded relevant local instructions, respected permission boundaries, and ran required validation for any actual code change;
- do not demand tests, commits, reviews, delivery records, or release steps for a read-only analysis when the repository rules make those gates conditional on changes;
- identify accidental process violations separately from substantive task errors.

### Session review

Treat the invocation as a retrospective or in-progress audit, not as another implementation step:

1. Reconstruct the user's requested scope, the route selected, and the actions actually taken.
2. For each applicable rule or gate, mark exactly one status:
   - `effective`: the session followed it and the available evidence supports that claim;
   - `ineffective`: the session took an action that contradicted it or failed a required gate;
   - `not applicable`: the rule did not apply to this request;
   - `inconclusive`: the session record does not contain enough evidence.
3. Check whether a rule changed the agent's decisions, commands, files, validation, or final report. A rule being present in context is not evidence that it was effective.
4. Report missed or ineffective rules even when the final product appears correct. Report rules that were not loaded only when they were applicable and should have been consulted.
5. End with a concise audit verdict and a list of concrete follow-up actions, if any. If the session is still in progress, label conclusions based on the evidence available so far. Do not start new implementation work unless the user separately requests it.

## Context-burden assessment

Evaluate context cost by asking:

- Does the root file contain rules that only one module or route needs?
- Can a short routing rule point to a maintained specialized document instead?
- Are the same requirements repeated in multiple files?
- Are historical explanations mixed with commands the agent must execute?
- Can a rule be checked mechanically rather than remembered in prose?

Prefer `short root rules + route table + on-demand module references`. Do not recommend deleting a rule merely because it is long; retain details that prevent a real decision error, and move them when they are only conditionally relevant.

## Output

Use this structure unless the user requests another format:

```text
## AGENTS.md Audit

### Scope
- [files and session boundary inspected]

### Findings
1. [severity] [short title]
   - Evidence: [path and heading/rule]
   - Impact: [what can go wrong or what context is wasted]
   - Recommendation: [smallest useful change]

### Applicable Rules
- [rule] → `effective` / `ineffective` / `not applicable` / `inconclusive`: [evidence]

### Not Applicable
- [rules intentionally not used and why]

### Session Verdict
- [whether the applicable rules took effect, with unresolved evidence gaps]

### Overall Assessment
- [pass / needs changes / blocked / inconclusive]
```

Keep the report factual. Do not claim a rule is stale, a command passes, or a session complied unless inspected evidence supports that claim.
