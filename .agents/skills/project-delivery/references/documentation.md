# Documentation Maintenance

Read before writing, moving, or deleting documentation, including instruction and skill files.
The Design Documents sections below govern `design/`; paths such as `analysis/` there are
relative to `design/`. Other backticked repository paths are relative to the repository root.

## Documentation Audiences

Documentation has two top-level audiences; keep their entry points and responsibilities distinct:

- **User-facing documentation** explains supported usage and observable behavior: `README*.md`,
  `docs/`, `demo/`, `integrations/`, and public contributor instructions.
  `docs/{en,zh}/quickstart.md` is the canonical development-line quickstart. The README provides
  a minimal stable-release example; demo articles provide detailed examples rather than a second
  quickstart.
- **Agent-facing documentation** guides implementation and maintenance: `AGENTS.md`, `design/`,
  `adr/`, and verification instructions. `design/` owns current contracts, proposals, and review
  history; `adr/` owns durable decisions and their rationale. Users must not need these internal
  documents to use the public API.

All newly added project-authored agent-facing and maintainer-facing documentation is English,
including `AGENTS.md`, `design/`, `adr/`, `verification/`, `refactoring-review/`, repository
scripts/tooling documentation, and issue/PR templates. Code snippets, proper names, quoted external
text, and historical records may retain their language when translation would change meaning.
This rule does not require translating existing Chinese content. If a task proposes translating, editing,
renaming, or deleting existing Chinese content in an agent-facing or maintainer-facing area, first
prepare the inventory, proposed wording, and affected-reference list for human review. Do not apply
that change until the user has explicitly approved it. New or maintained Chinese user-facing
documentation remains covered by the documented Chinese-user exceptions.

Group related contracts, ADRs, and history by subject in their existing routes where useful. Link
related documents by subject without duplicating implementation rationale. Subject grouping is a
navigation aid, not a third documentation category or a replacement for the
lifecycle rules in `design/AGENTS.md` and `adr/AGENTS.md`.

## Documentation References

Documentation must stay readable and correct without the Git object database:

- Keep only the current view. The repository keeps no full-text archive of
  retired proposals, harvested analyses, review transcripts, or superseded ADR
  wording. Before deleting such material, harvest its conclusions into the owning
  contract, ADR, or `design/decision-log.md`, with at least one reason for every
  rejected or superseded direction so the rejection can later be revisited.
- Do not cite commit hashes in documentation, not even as provenance: a hash
  changes when history is rewritten and forces chained edits in every document
  that cites it. Name the date, the proposal number (`proposal:P<NNNN>`), the
  release, or the mechanism instead. Never point at git history to retrieve
  content.
- Never identify code or prose by a line number appended to a path. Cite the
  symbol, the section heading, or the file path instead. Line numbers drift and
  make the document wrong by default.
- Write a pointer so that it reads on its own: when the target holds a single
  sentence, write that sentence at the pointer instead of a jump to it.
- `scripts/check-doc-refs.py` enforces these rules on live documents. Until the
  maintainer rewrites them, `todo.md` (scratch), analysis inputs
  under `design/analysis/`, the changelog, and the dated records under
  `verification/` and `tools/` are exempt and may still carry hashes; do not
  add new ones. Most hashes they cite are already unreachable from any branch.

## Design Documents

Usage rule: **read this table first, match it against the current task by summary, load only the
documents it hits, and do not pre-read everything.**
Contract-class documents are written with implementation-binding force (MUST/MUST NOT/SHOULD)
and are the authoritative basis for this repository's current behavior.
When you need decision background, rejected alternatives, or consequences, read the ADRs linked
under the subject. The unreplaced parts of an `Accepted` ADR remain valid; a `Partially
superseded` ADR must be read together with its successors; a `Superseded` ADR serves only to
explain why the direction changed and must not be used as a basis for current implementation.
Boundary questions that need to be permanently closed to reopening may be held by an ADR; every
other current behavior, lifecycle, and concurrency invariant is held by the live contracts and
is not duplicated in ADRs. ADR headers carry `Stability` and `Category` lines: `Stability: beta`
means the subject it constrains has not shipped in a stable release; `Category: core` records
are background for core behavioral decisions whose current authority sits in the live
contracts; `non-core` records (artifact/repository boundaries, testing methods, documentation
practices, build tools, dependency policy) remain the authority for their question until
superseded. Retired ADRs keep no full text; their conclusions and rejection reasons are in
[decision-log.md](../../../../design/decision-log.md).

This directory keeps only the current view, in four classes: **live contracts** (tracked,
constrain current implementation), **in-flight proposals** (tracked, in `proposal/`, written but
not yet landed), **analysis drafts** (temporary inputs, deleted after harvest; committed to
`analysis/` only at the user's request, see maintenance principle 9), and **archived proposals**
(tracked, in `archive/proposal/`, dormant directions kept as full text for human reflection and
history analysis; agents MUST NOT load them by default, live documents MUST NOT link to or depend
on them, and their outbound links and claims are frozen as of the archive date). Landed proposals
are harvested into live contracts; conclusions and rejection reasons of rejected or superseded
directions go to [decision-log.md](../../../../design/decision-log.md). The repository keeps no full-text archive
beyond `archive/proposal/`: superseded full text, review transcripts, and verification records are
deleted once their conclusions and rejection reasons are harvested, and current documents must not
depend on them. **Do not cite commit hashes or line numbers to locate content.**

New capabilities and contract changes need no issue first: a proposal is written directly into
`proposal/`, added to the in-flight routes, and committed, with no distinction between draft,
accepted, or in-progress. At landing, merge the summary into the live contract, write the
historical back-reference, and delete the proposal and its route row in the same commit; details
are in [proposal-lifecycle.md](../../../../design/proposal-lifecycle.md). Whether to also open an issue for public
discussion is the maintainer's judgment (see Issue Tracking in the root
[AGENTS.md](../../../../AGENTS.md)). Every proposal and PR touching public API or contracts must carry
concrete statements: the best user code writable before the change, the same code after, and the
failure mode removed; breaking changes additionally carry a migration path.

**This table has two routed sections, live contracts and in-flight proposals; analysis drafts and
archived proposals are not routed.** Related ADRs sit under their subject's contracts; no separate
ADR index or second navigation is built. CI's `scripts/check-design-routes.py` validates both
sections bidirectionally: table links must exist, and every tracked top-level live document and
every proposal in `proposal/` must have a row; `analysis/` and `archive/` are outside the check.
`scripts/check-proposal.py` additionally gates proposal identity, abstracts, and the
content bans; archived proposals are checked for identity only, `check-doc-refs.py` and
`check-local-links.py` skip them as sources, and `check-local-links.py` rejects links from live
documents into the archive. When picking up work, read the matching route first, then check
`git status --short design/` for uncommitted analysis inputs.

Adversarial-review findings and dispositions are staged at the end of the proposal document they
belong to during review; when the implementation is finalized and harvested, retained findings
merge into contract rules, and rejected or downgraded findings with their reasons condense into a
"rejected alternatives and reasons" or "claims rejected in review" section merged into the body or
[decision-log.md](../../../../design/decision-log.md); reverse verifications, PIT classifications, and round
transcripts are not archived — the commit message records the round's outcome.
Baseline reading for later review rounds: read the decision-log and the document body first.
The process is Adversarial Review in the review procedure linked from the root [AGENTS.md](../../../../AGENTS.md).

## Document maintenance principles

When writing, changing, or deleting documents in this directory:

1. **Do not copy facts code can answer** — line numbers, class inventories, and method signatures
   drift inevitably. Reference mechanism names, symbol names, or section headings; never write
   "file:line", and never cite commit hashes: a hash goes stale on rebase or rewrite, and every
   document citing it must follow, forming chained edits. When a source is needed, name the
   date, the proposal number, or the mechanism.
2. **Single source of truth**: a fact is written fully in exactly one place; every other place
   may only point, never restate (positive example: P1–P4's full text lives in
   [interruption-contract.md](../../../../design/interruption-contract.md); core/AGENTS.md keeps only the
   summary). A pointer must read on its own: when the target holds a single sentence, write that
   sentence at the pointer instead of building a one-line jump chain of "see somewhere, some
   line".
3. **Supersession rewrites in place** — no "superseded" annotations in live documents; the old
   shape keeps no full text, and the reasons for abandonment are written per principle 4.
4. **Rejection records outvalue adoption records** — the reasons for an adoption can be inferred
   from code; the reasons for a rejection cannot and must be written down.
   Every rejection or dismissal carries at least one reason, so that a future un-rejection knows
   what must be overturned; "review-contrast value only" and "superseded" do not count as
   reasons. When a tracked proposal is rejected or superseded, the conclusion and rejection
   reasons go into [decision-log.md](../../../../design/decision-log.md) (when reopening must be closed, write an
   ADR per principle 10, with the log keeping a pointer) — that is a precondition for deleting
   the proposal file and its route row; it does not count as implementation landing and writes
   no landing back-reference into behavioral contracts.
5. **Lifecycle exits are one-way**: an in-flight proposal ends as a live contract (landed), as
   decision-log residue (rejected or superseded), or as an archived dormant file under
   `archive/proposal/`; the archive standard and re-entry are defined in
   [proposal-lifecycle.md](../../../../design/proposal-lifecycle.md). Material that no longer constrains
   implementation keeps no full text outside the archive and is deleted after harvest.
   The summary is written with the proposal and updated as the argument evolves; at landing, the
   edit merges it into the contract, writes the historical back-reference, and lands in the same
   commit as the implementation, the proposal's deletion, and the route row's removal. Harvesting
   keeps the core motivation, the design principles and invariants, and the rejected directions
   with reasons; core code may be kept (a state-machine skeleton or protocol order — code that
   expresses more precisely than prose); line numbers, class/method inventories, signature
   enumerations, test counts, command outputs, and multi-round review transcripts are deleted.
   **No "file:line" and no commit hashes** — reference mechanism names, symbol names, or section
   headings. Existing live documents are not forced to be rewritten immediately; condense them in
   passing the next time a related change touches them.
6. **Contract and implementation in the same commit** — the two check each other in review. A
   behavior-changing commit message or PR description lists the MUST/MUST NOT items it changes
   or adds, so no contract section drifts silently behind.
7. **The keep/drop criterion**: "if it were deleted, would decision quality drop?" Questions
   code, tests, or git can answer are negative assets when written down.
8. **Long-lived documents write invariants and mechanisms, not enumerations** (write the rules of
   the "token propagation tree", not a "public API inventory").
9. **Analysis drafts are inputs, not documents**: the conclusions of audits, probe records, and
   decision briefs are harvested into fixes, the decision log, BACKLOG, or in-flight proposals,
   then deleted. Confirm each item's landing place one by one; an untracked file has no in-repo
   copy — when unsure, keep it and say why. Analysis drafts are not committed on one's own
   initiative by default; they enter the repository only at the user's request: placed in
   `analysis/` (not routed, not constraining implementation), opened with an archival note —
   committed at the user's request, the date and purpose, and that it is not a current contract;
   it must pass `scripts/check-local-links.py`. Deleted after harvesting, with no full text kept
   elsewhere.
10. **Decision rationale lands in exactly one place by nature**, with pointers elsewhere:
    - **ADR** ([adr/](../../../../adr)): cross-module, hard-to-reverse, or reopening-closing decisions
      (artifact boundaries, core protocol orders, conclusions of the "do not reopen in reviews,
      defect triage, or refactoring proposals" kind). An ADR's decision is never rewritten: when
      the decision changes, write a successor ADR and mark the old one `Superseded`; outdated
      non-decision content is condensed and deleted per [adr/AGENTS.md](../../../../adr/AGENTS.md). When
      an ADR's status or successor relationship changes, update the owning subject's route links
      in the same commit.
    - **A live contract body's "rejected alternatives" section**: trade-offs affecting a single
      contract, rewritten in place with it.
    - **[decision-log.md](../../../../design/decision-log.md)**: the residue of rejected, superseded, or harvested
      tracked proposals and analysis drafts (conclusions, rejection reasons, landing places); it
      prevents reopening only and does not define current behavior. When a log conclusion needs
      reopening closed, write an ADR at the same time; the log row keeps a one-sentence
      conclusion and the ADR pointer, with the full reasons only in the ADR.
    - Existing entries are not force-migrated: a log row already saying "do not reopen / do not
      re-raise" is promoted to an ADR the next time a related change touches it.
11. **Structural text may be gate-coupled**: check scripts can hardcode document structure —
    route-table section names, heading text, or table shapes (example:
    `scripts/check-design-routes.py` reads this file's `Live contracts` / `In-flight proposals`
    section headings). Before renaming a heading, restructuring a route table, or reshaping a
    gated table, grep `scripts/` and `scripts/tests/` for the literal and update every coupling
    in the same commit.


## Instruction Layout

The root `AGENTS.md` owns task classification, approval boundaries, and loading triggers.
The project-delivery skill owns the change workflow and its conditional references; scoped
`AGENTS.md` files own directory-specific guidance. Keep one authoritative full rule and use
short routing summaries elsewhere. Root section anchors remain entry points for existing links.

The maintainer chose this layout to reduce mandatory startup reading without weakening gates.
A separate skill for each checklist was rejected because it would multiply discovery and
cross-skill navigation. Moving all policies into skills was rejected because safety and
approval boundaries must remain visible before skill selection. Deleting safeguards merely
to meet a line target was rejected because brevity must not change the delivery contract.
