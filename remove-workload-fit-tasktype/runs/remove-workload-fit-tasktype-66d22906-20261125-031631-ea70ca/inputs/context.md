# Remove TaskType and workload-fit screening: adversarial review round 2

Review the fix commit `07f83919e72414a6987648c3288788a5db7abc6b..66d22906a436de2340f04f91e191ac37b581d924`
read-only. The implementer is Claude (Opus). Do not edit files, run builds, or commit.
Report only defects you can tie to a file and symbol with a concrete failure scenario.

## Baseline: round 1 settled findings (all confirmed and fixed by this commit)

- ADR 0016's `Supersedes` line named only ADR 0008 and ADR 0010, while ADR 0001 and ADR 0005 list
  ADR 0016 under `Partially superseded by`. The fix adds the partial supersessions of ADR 0001 (its
  `TaskType` decision rule and L1 row) and ADR 0005 rule 10.
- ADR 0008's dated note and the closing paragraph of its decision called API and options section
  3.2.1 retired, although the section is live as "Admission diagnostics". The fix says only the
  workload-fit screening contract and the `TaskType` clauses of section 3.2.1 were retired, and links
  the live section.
- The Unreleased `Changed` entry of `CHANGELOG.md` on diagnostic strings still described
  `WorkloadFitReport` rendering, although the same release removes the type. The fix deletes the
  clause.

Do not re-report these unless the fix itself is wrong or incomplete.

## What to check

1. Each fix against its finding: does the new `Supersedes` text name exactly what ADR 0016 changed in
   ADR 0001 and ADR 0005 (compare with their `History` sections and the in-place edits); is the new
   ADR 0008 wording accurate about what was retired and what section 3.2.1 still holds (compare with
   `design/task-group-api-and-options.md` section 3.2.1 and ADR 0016 decisions); is the CHANGELOG
   sentence still grammatical and accurate for the remaining types.
2. Rules: `adr/AGENTS.md` File And Status Rules (metadata, both-direction links, dated notes naming
   the holding contract for `core` records).
3. Beyond the baseline: other live documents that still state removed behavior or misdescribe the
   retained admission diagnostics. Frozen migration guides (`docs/*/migration-v0.*.md`), dated
   `verification/` records, and released CHANGELOG sections are intentionally unchanged.

## Verification already run

`check-adr.py`, `check-doc-refs.py`, `check-local-links.py`, and `check-english-docs.py` pass on the
fix commit. No executable file changed.
