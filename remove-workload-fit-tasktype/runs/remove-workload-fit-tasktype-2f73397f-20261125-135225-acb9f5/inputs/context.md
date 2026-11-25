# Remove TaskType and workload-fit screening: review round 5 (installGlobal disposition fix)

Review `758b7df6..2f73397f` read-only. The implementer is Claude (Opus). Do not edit files, run
Maven, or commit. You may run read-only commands such as `git`, `grep`, and `sed -n`.
`verification/contract-tests/` is out of scope.

## The commit

`2f73397f docs: withdraw installGlobal PIT equivalence claim` changes one file,
`verification/pit-audit/2026-11-24-remove-tasktype.md`. Round 4 reported that the record's
equivalence claim for the `ParRuntime.installGlobal` survivor (removed closed-runtime
`Preconditions::checkState`) fails when a closed runtime's installation races a live runtime's.
The commit removes that claim and records the survivor as a fourth unresolved pre-existing
`ParRuntime` gap.

Rounds 1 to 4 are settled. Do not re-report their findings or re-review unchanged text, except
where the new text contradicts it.

## What to check

1. Does the commit resolve the round-4 finding? Check the new gap entry against
   `ParRuntime.installGlobal` and its Javadoc in
   `core/src/main/java/io/github/monadrome/parallelinscope/ParRuntime.java`: the contract it
   quotes, the interleaving, the claim about the unmutated code (including the same-instance
   close race the Javadoc permits), the impact, and the next actions.
2. `verification/pit.md`, "Dispositions and completion": does the entry carry the contract,
   evidence, impact, and next action that an unresolved gap requires?
3. Consistency: the changed-classes table, the gap section's introduction, and "Overall status"
   must agree (four gaps, three remaining equivalents, unchanged counts).
4. Prose: symbols instead of source line numbers, English, working links.

## Output

In `summary`, give one line for item 1 (`RESOLVED` or `NOT RESOLVED`, with the reason) and one
line for items 2 to 4 together (`ACCEPT` or `REJECT`). Report every defect as a finding tied to a
file and a line, with a concrete failure scenario.
