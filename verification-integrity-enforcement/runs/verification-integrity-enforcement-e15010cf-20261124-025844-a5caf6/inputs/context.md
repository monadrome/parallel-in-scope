# Contract-first assurance skip confirmation

Read the complete 0e804a8b08a8c5bfcce12e9d831dc44b197b557e..e15010cf224e9ddbd2dfeca8262f6c1961eddee2 diff and every changed file read-only. Also read `.agents/skills/project-delivery/references/assurance.md` at the head and apply its Run required and Skip eligible conditions to the complete semantic diff.

This review's primary purpose is to decide whether the original verification-integrity-gate delivery is eligible for `assurance: SKIPPED`. The changed scope is the repository's Java/Python/TypeScript verification-integrity checker and tests, CI/CODEOWNERS wiring, project command routing, gate contract, and delivery ledger. Confirm that this scope changes neither library runtime contracts or observable behavior nor behavioral evidence for those contracts. Do not treat the number of files or lines as the reason for skip eligibility.

If every Run required condition is absent, return zero findings and make the summary explicitly state: `assurance: SKIPPED confirmed against the complete diff`, followed by the changed scope and semantic reason. If any Run required condition holds or cannot be established, return a concrete finding identifying the condition and evidence instead of confirming the skip.

Also report any new blocking defect you encounter in the complete diff, but do not repeat previously settled findings unless still defective. Do not run commands or tests, modify files, or inspect benchmark judgments or gold.
