# Proposal Lifecycle Contract

A proposal is a change direction that has been written down but not yet landed; live contracts
constrain current implementation. Directory location distinguishes the states, so proposals carry
no status field. The enforcement entry points are `scripts/check-design-routes.py` and
`scripts/check-proposal.py`; CI runs the same scripts.

## 1. Locations and states

Four locations hold design material; an agent's default reading path covers only the first two:

- **Live contracts** (`design/*.md`): the authority on current behavior.
- **In-flight proposals** (`design/proposal/`): directions written down but not yet landed. They
  MUST NOT constrain current behavior.
- **Archived proposals** (`design/archive/proposal/`): dormant directions kept as full text for
  human reflection and history analysis. Agents MUST NOT load them by default; live documents
  MUST NOT link to or depend on archived content. An archived file is frozen as of its archive
  date; its outbound links and factual claims are not maintained.
- **Decision residue** ([decision-log.md](decision-log.md)): conclusions and rejection reasons of
  landed, rejected, superseded, or harvested directions.

States move one way: in-flight → landed (distilled into contracts), in-flight → rejected (residue
in the decision log), in-flight → archived (dormant). Archiving is not a verdict; re-entry moves a
file back to in-flight.

## 2. Intake and identity

- A proposal MUST live at `design/proposal/P<NNNN>-<kebab-slug>.md` and be committed; drafts,
  accepted, and in-progress proposals all follow the same rules. Proposals MUST NOT carry a
  status field or header status line.
- The id MUST be four decimal digits and the slug MUST be a lowercase hyphenated word group. One
  id maps to exactly one proposal; an id MUST NOT be reused, including ids of deleted or archived
  proposals. The next id is the current maximum plus one, where the maximum is taken from
  `design/proposal/` filenames, `design/archive/` filenames, `proposal:P<NNNN>` back-references
  in live contracts, and [decision-log.md](decision-log.md); no separate registry is kept. Ids
  only increase. When a proposal is deleted, its id MUST remain visible in a contract
  back-reference or the decision log, or the next intake cannot see it.
- A proposal MUST have one summary row in the in-flight table of [AGENTS.md](AGENTS.md); that
  table lists unlanded directions and MUST NOT be read as an approved implementation list.
- Every proposal MUST contain a non-empty `## 蒸馏摘要` or `## Distillation summary` section with
  the core motivation, the invariants and proposed MUST/MUST NOT items, and the rejected
  directions with reasons (or a statement that there are none). The summary evolves with the
  argument; it is not a one-shot template. The section MUST be a real section: a template heading
  inside a code fence or HTML comment does not satisfy it.
- A public API or contract proposal MUST show the best user code before, the same code after, and
  the failure mode removed; a breaking change MUST name the migration path. Evaluate benefits
  against [first-principles.md](first-principles.md).
- **Verbosity.** A proposal carries decision content only. It MUST NOT contain `file:line`
  citations, commit hashes, member or class inventories, test counts, command output, multi-round
  review transcripts, or implementation checklists; cite mechanism names, symbol names, or section
  headings instead. Working evidence lives in the implementing PR or is harvested into contracts
  and the decision log; it is not parked in the proposal.

## 3. Landing and deletion

Landing is decided by the implementation satisfying the proposal's goals, not by a document still
being called a proposal. A landing commit MUST, in the same commit:

1. Edit the final distillation summary into the receiving `design/` live contract, preserving the
   motivation, invariants, and rejection reasons in current wording; candidate MUSTs MUST NOT be
   passed off as established constraints.
2. Write a `proposal:P<NNNN>` historical back-reference in each receiving contract at the point
   that receives the content.
3. Delete the proposal file and its route row.

A back-reference MUST be plain text or inline code and MUST NOT be a file link. After deletion
the back-reference intentionally has no current file target; it is a legal history marker and
MUST NOT be treated as a dead link. The proposal's full text is not preserved elsewhere: at
landing time the motivation, invariants, and rejection reasons already live in the contract, so
after deletion neither commit hashes nor a retrieval path for the full text may be cited.

When part of a proposal has landed and the rest has not, the remaining direction MUST first be
split into a new-numbered proposal with a route row; only then is the original distilled and
deleted. One id MUST NOT stand for both a landed source and remaining work.

Rejection or supersession is not landing: the conclusion, complete reasons, and the landing spots
of any remaining direction MUST be recorded in [decision-log.md](decision-log.md) first, and the
proposal file and route row are deleted in the same commit. A rejection MUST NOT write landing
back-references into behavior contracts.

## 4. Archive

**Standard.** A proposal is archivable only when all three conditions hold:

1. **Open**: no conclusion has been reached — the direction is neither implemented nor decided
   against. A concluded proposal takes the landing or rejection path instead.
2. **Dormant**: no work or decision is expected from it in the current development line. Evidence
   is any of: the maintainer explicitly parks it; the question it carried moved elsewhere while
   its analysis remains worth keeping; an inactivity review finds it untouched and unreferenced
   by current work for roughly a release cycle. Inactivity triggers a review, never an automatic
   move.
3. **Text worth keeping**: the full text carries analysis, evidence, or rejected alternatives
   that decision-log residue cannot hold and that would be expensive to reconstruct. If the
   durable content is only a conclusion plus reasons, distill it into the decision log and delete
   the file instead.

**Anti-criteria.** Archiving MUST NOT substitute for:

- rejection — a direction judged wrong gets its reasons in the decision log; archiving it loses
  the do-not-reopen record and invites re-proposal;
- landing — implemented means distilled and deleted, always;
- slimming — verbose-but-active stays active and is distilled in place;
- work-tracking — a checklist with no open design choice goes to `BACKLOG.md` or the decision log
  and the file is deleted; that is reclassification, not archiving.

**Bias.** When uncertain between active and archived, archive: the in-flight reading path costs
attention on every design task, and re-entry is one commit. When uncertain between archive and
reject, reject: the rejection record is the durable asset.

**Move.** The maintainer confirms each archive; an agent proposes it with the evidence (which
dormant signal fired, why the text is worth keeping). An archive move is one commit: move the
file to `design/archive/proposal/`, add the banner, remove the route row, and rewrite inbound
links from live documents as plain-text archived references. The banner is a blockquote
immediately after the title:

```text
> Archived <YYYY-MM-DD>. Reason: <one line naming the dormant signal>. Direction
> remains open; re-entry is a maintainer decision: move back to
> design/proposal/ and restore the route row in one commit. Content and
> links are as of the archive date and are not maintained.
```

**Re-entry.** A maintainer decision moves the file back to `design/proposal/`, removes the
banner, and restores the route row in one commit.

## 5. Gates and their boundaries

`python3 scripts/check-design-routes.py` MUST check, with one shared route-validation logic:

- A: the live-contract table covers every tracked, still-existing top-level `design/*.md`
  (excluding AGENTS), and the in-flight table covers every Markdown proposal in
  `design/proposal/`, including unstaged new files; the two sections MUST match their sets
  bidirectionally, casual links in prose do not replace route rows, and all local links MUST
  exist. Route identity comes only from a table's first column; cross-references in the summary
  column, code samples, and HTML comments MUST NOT count as routes. `design/analysis/` and
  `design/archive/` are outside both tables.
- Identity premise: proposal filename shape, id uniqueness, and the non-empty summary MUST be
  mechanically checkable.
- B: a live contract that carries a `proposal:P<NNNN>` back-reference whose same-id proposal file
  still exists in `design/proposal/` MUST fail; a back-reference with no same-id in-flight
  proposal MUST pass. The scan excludes proposals and analysis drafts (including those committed
  to `design/analysis/` at the user's request).

A and B are independent: removing a route row triggers A, and keeping both a route row and a
landed proposal triggers B. Both inspect the current tree, parse no commit diff, and build no
state machine. A full back-reference inside a Markdown example still counts as a marker to B;
mechanism prose uses the `proposal:P<NNNN>` placeholder.

`python3 scripts/check-proposal.py` MUST check: filename shape and id uniqueness across
`design/proposal/` and `design/archive/proposal/` (an archived id blocks reuse); on in-flight
proposals only, the non-empty summary as a real section and the content bans of section 2
(line-number citations, status sections; commit hashes are covered by
`scripts/check-doc-refs.py`). Archived files are checked for identity only.
`scripts/check-doc-refs.py` and `scripts/check-local-links.py` exempt `design/archive/` as scan
sources, and `scripts/check-local-links.py` rejects links from live documents into the archive.

The gates cannot prove that a summary's semantics entered a contract, that an implementation met
its goals, that a deletion shipped in the same commit as the implementation, or that an id was
never used historically. Pre-commit review MUST check those against the diff; a gate pass MUST
NOT be reported as distillation-quality or implementation-correctness verification.

## 6. Analysis inputs and non-goals

Analysis drafts are inputs, not documents: their findings MUST be harvested item by item into
fixes, the decision log, `BACKLOG.md`, or standalone proposals before deletion, and no full text
is kept; the decision log records conclusions and reasons and does not link deleted drafts. When
an untracked file has no in-repo copy and its harvest cannot be confirmed, it MUST be kept and
the open items reported. Converting a draft into a proposal keeps the problem, the evidence
limits, the actionable goals, and the rejection reasons; a historical probe MUST NOT be cited as
proof of the current implementation.

There is no `distill-proposal.py`: choosing where and how to edit a contract takes judgment, and
moving the summary, deleting the file, and removing the route row are already directly checkable
by the existing gates. A script cannot prove semantic harvest, so a second edit path is not worth
its cost. The lifecycle keeps no draft/accepted/implementing states, maintains no landed-proposal
catalog, generates no contract text, and adds no further gate scripts beyond the two named in
section 5. The archive directory is the single exception to the repository's no-full-text-archive
rule, and it holds dormant proposals only; landed or rejected proposals still leave no full text.

## 7. Review trade-offs

Structural-recognition and harvest findings from independent read-only review were kept and
fixed: fenced table rows, summary-column links, and fenced summary templates cannot satisfy the
required structure, and route recognition and summary recognition share one fence and
HTML-comment filter, with valid links in the first column deciding route identity. Each
counterexample is reproduced failing first, then the fixed gate is confirmed to reject it; no new
Markdown dependency and no second judgment logic was introduced. Follow-up candidates harvested
from analysis inputs are recorded at their addressable landing spots rather than under a blanket
"carried into maintenance" claim.
