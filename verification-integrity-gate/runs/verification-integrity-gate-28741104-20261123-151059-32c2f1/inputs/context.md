# Independent verification-integrity review

Read the full origin/dev/v0.5.0..HEAD diff and all 9 changed files. Review the
current implementation, tests, CI, CODEOWNERS, and contract read-only. Do not run
commands, tests, or exploratory probes; read only the files needed to validate a
concrete concern, then return the required JSON immediately.

Check for new blocking defects in test deletion/suppression/assertion detection,
Java/Python/TypeScript parsing, baseline and exact-move handling, CODEOWNERS
last-match owner coverage, CI base selection, and refactor behavior preservation.
The implementation already has regressions for the suppression forms named in the
contract and for CODEOWNERS ownerless/last-match overrides and the 3 MB limit.
Do not re-report documented heuristic limitations or optional refactors.

Return at most 3 findings with a concrete trigger and observable consequence.
If none is supported by the changed code, return an empty findings list promptly.
