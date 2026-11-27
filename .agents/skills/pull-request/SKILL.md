---
name: pull-request
description: Write, post, and refresh pull-request descriptions for parallel-in-scope. Use when opening a pull request, rewriting or updating a PR description after new commits or review, or reporting verification and review status on a PR.
---

# Pull request

A PR description is the permanent record of the change: when no issue is linked, it is the only
place a reader finds why the change exists, who decided it, what was traded away, and what
proves it. Write it for a maintainer who has not seen the session, the analysis drafts, or the
commit messages, and for a reader searching history a year later. Spend the words on what the
diff and CI cannot show: intent, decisions, risk, and where to look. Size the description to the
change: a one-file fix gets a few sentences; a public API or contract change gets every section
that applies.

Opening or updating a PR is part of an authorized delivery route; merging is not (root
`AGENTS.md` Permissions).

## 1. Gather the record

Collect each fact from a source a reviewer can open:

- **Goal and decisions**: the request, each maintainer decision with its date, and the durable
  record that owns the rationale (ADR, the owning contract's rejected alternatives, or
  `design/decision-log.md`). Untracked analysis (`.agents/tasks/`, `tmp/`) is invisible to
  reviewers, and links can rot: carry the conclusions into the description itself.
- **Commits**: `git log --reverse --format='%h %s' <base>..<head>`, grouped by concern.
- **Contract changes**: every MUST / MUST NOT added, changed, or removed, from the commit bodies
  and the owning contract's diff.
- **Public API**: the `scripts/check-api-surface.py` output against the merge base when public
  types change.
- **Verification**: every check that ran, its actual status, and the reason for each non-pass;
  PIT per the compact record in `verification/pit.md`; each review seat with its model and
  status.
- **Open asks**: approvals (protected-file exceptions, decisions), pending or unauthorized gates.
- **Current body** (existing PR): `gh pr view <n> --json body,reviews,comments`. Content a
  maintainer added is kept unless they asked for it to go.

Done when every section you will write has its facts and each fact names its source.

## 2. Check scope

One PR carries one concern. When the range also carries an unrelated change (a workflow or skill
edit riding along with an API change, for example), propose moving it to its own PR before
drafting. Keep it only when the maintainer says so, and give it its own row in the commit map.

## 3. Draft the body

Write `tmp/pr/<branch>.md`. Start at `##` headings (GitHub shows the title as the page heading)
and keep this order. The **core** sections appear in every PR; a **conditional** section appears
when its trigger holds.

| Section | When | Content |
|---|---|---|
| Goal | core | The outcome in one or two sentences; `Done when:` checkable bullets; `Out of scope:`. |
| Decisions | a choice beyond the request itself | Who decided and when, the fact that drove it, each rejected alternative with its one-line reason, the link to the durable record. |
| Summary | core | What changed and why, by intent rather than file. A public API or documented-contract change shows the best user code before, the same code after, and the failure mode removed. Name the approach's known limitations. |
| Contract changes | behavior or contract change | The MUST / MUST NOT list; otherwise one line saying no contract changes. |
| Compatibility and risk | public API, behavior, build, or dependency change | Who is affected (released `0.4.x` users, snapshot users, contributors), the migration, and whether a revert is clean. |
| Review guide | more than a few lines of logic | Reading order (the main change first), the spots to scrutinize (interleavings, ordering, replaced tests), what you are unsure about, the mechanical commits and how to read them (`git show -M` for a rename), and a commit map table when the range has more than three commits or more than one concern. |
| Needs maintainer | any approval or authorization is open | A checkbox per ask, each with its effect. |
| Related issue | core | `Closes #NN` / `Refs #NN`, or "None; this description is the record." |
| Verification | core | What was run and what it found, as a table. Statuses are `pass`, `fail`, `blocked`, `inconclusive`, or `skipped`, each non-pass with its reason. Add the PIT record and review seats when they ran. Run-by-run history stays in commit messages and the delivery ledger. |
| Evidence | durable artifacts exist | Links pinned to the head commit SHA: contract, changelog, ledger, PIT archive, review runs on `llm-ledger`. Evidence only in gitignored or build paths is not linkable: archive it first, or say where it is and that it is not archived. |
| Breaking changes | core | The checkboxes from `.github/pull_request_template.md`. |

The title is a Conventional Commit subject that stands alone in `git log --oneline`: it names
the goal, uses `!` for a breaking change, and stays within 72 characters.

Done when a reader who knows only the repository can state the goal, the decision and its
reason, the risk, what to review first, and what is still needed from them.

## 4. Readability pass

Run the ZCode pass required by Issue Tracking in
[operations](../project-delivery/references/operations.md), bounded with a timeout (macOS:
`perl -e 'alarm shift; exec @ARGV' 900 zcode --cwd /tmp -p "<instructions>" --attach <draft>`).
Ask it to keep the headings, code blocks, tables, statuses, paths, and identifiers.

Diff the result against the draft. Accept it only when it describes this PR (its branch
identifiers and title terms are present), every section survives, no status or number changed,
and code blocks are identical. Otherwise retry once; if that also fails, post the draft and say
in your report that the pass did not complete.

## 5. Post and confirm

- New PR: `gh pr create --base <development line> --head <branch> --title <title> --body-file <file>`,
  with `--draft` while a required gate (assurance, a required review, CI) is pending or blocked.
- Existing PR: `gh pr edit <n> --title <title> --body-file <file>`. When reviews or comments
  already exist, also post `gh pr comment <n> --body-file <summary>` naming what changed since the
  last review: a body edit notifies no one.
- Read the posted body back with `gh pr view <n> --json title,body,isDraft` and compare it with
  the file.

## 6. Refresh

After new commits, a gate result, or a review round, update the goal if the scope moved, the
commit map, the verification table, the evidence SHAs, and the open asks; then post, comment if
reviewed, and read back. Before `gh pr ready <n>` or asking for merge, reread the whole body
against the final range so it still describes what the PR does.
