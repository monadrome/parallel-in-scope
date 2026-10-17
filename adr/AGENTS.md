# AGENTS.md

## Scope

These instructions apply to every Architecture Decision Record in `adr/`.
An ADR records why an important decision was made, not general documentation,
temporary analysis, or a progress log. Which decisions belong in an ADR rather
than a design contract's rejected-alternatives section or
`design/decision-log.md` is defined in `design/AGENTS.md` (document maintenance
principle 10); this file governs only the ADR format.

## File And Status Rules

- Name new records `NNNN-kebab-case-title.md` using the next sequential number.
- Use one record for one decision.
- Include `Status`, `Date`, `Decision scope`, and `Supersedes` metadata.
- Use these statuses: `Proposed`, `Accepted`, `Deprecated`, or `Superseded`.
  A record whose decision only partly changed stays `Accepted` and adds a
  `Partially superseded by` line naming what changed and where it now lives.
- Never rewrite a decision into its replacement. When a decision changes, add a
  successor ADR, or name the live `design/` contract that replaced it; mark the
  old record `Superseded` (or partially superseded) and link both directions.
- When a successor ADR overturns only a rule or premise inside an older record,
  edit that text in place so the older record no longer states the overturned
  rule: state the current rule in one line, name the successor ADR inline, and
  add it to `Partially superseded by`. Keep the rest of the record as decided;
  the successor holds the reasoning, and git history holds the previous wording.

## Condensing Records

Records may be condensed so that outdated content does not pose as current:

- Delete content that carries no decision value: implementation mappings, type,
  field, and test catalogs, work plans, acceptance checklists, usage examples,
  and contract-style rules whose authority now lives in `design/`.
- Keep the decision as it was made: `Context`, `Decision`, `Alternatives
  Considered`, `Consequences`, and reconsideration criteria. Condensing MUST NOT
  change what was decided or why, and MUST NOT add hindsight justification.
- Keep the terms the decision was made in; where a type was later renamed, add
  the current name once (`GlobalPar` (now `ParRuntime`)). Facts that changed
  after the decision go in a dated note, not into the original reasoning.
- End a condensed record with a `History` section naming the commit whose tree
  holds the previous full text (`git show <hash>:adr/<file>`) and listing what
  was removed.

## Required Content

Every ADR must explain:

1. `Context`: the problem, constraints, and forces that require a decision.
2. `Decision`: the chosen approach and any enforceable rules.
3. `Alternatives Considered`: credible options and why they were rejected.
4. `Consequences`: positive effects, costs, risks, and limits.

Add implementation mapping or reconsideration criteria only when they help
future maintainers apply or revisit the decision.

## Content Boundaries

- Keep durable architectural reasoning here.
- Keep test counts, command output, active failures, and temporary investigation
  details in reports outside `adr/`; link them with relative paths when useful.
- Use concrete project types and paths, but do not duplicate source code or
  README material.
- Match the existing ADR language and terminology.
- Do not add ADRs to README or MkDocs navigation (`mkdocs/mkdocs.yml`) unless
  explicitly requested.

## Validation

- Check relative links and referenced files.
- Java tests are not required for documentation-only ADR edits.
