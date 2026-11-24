# Frozen contract: assurance run verifier 1.5.2

- Scope: committed diff `6981df77ec413177b9c5d076cf82eb1388036a2a..4b20479a9dd5180b305a57cac8bea89d53cf5338`.
- Public entry point: `verify(run_dir: Path) -> list[str]` in the sanitized API view.
- Success returns `[]`; invalid input returns one or more errors. Verification MUST NOT mutate the run.

## C-1 Valid baseline fixture

Every candidate needs a valid baseline before changing one boundary. Create the manifest at exactly `run-manifest.json` in the temporary run root. It MUST contain `schemaVersion: contract-assurance-run/v2`, `runId`, `createdAt`, `package`, `adapter`, `workspace`, `inputs`, `commands`, `artifacts`, `status`, `requiredGates`, and `checks`. `commands` MAY be `[]`. `package` MAY be `{}` for these tests.

Create these files: `contract.md`, `contract-gaps.md`, `candidate-status.csv`, `promotion-status.csv`, `verification-summary.md`, and `report.md`. Create directories `inputs/`, `logs/`, `candidates/`, `promotions/`, and `reverse-verification/`. Hash every required file in `artifacts`. Hash at least one real file under `inputs/` in `inputs`. `promotion-status.csv` has exactly header `candidate_id,source_contract_id,contract_id,target_path,archive_path,status,reason`; a header-only file is valid. `candidate-status.csv` has a `candidate_id` header.

For a no-exception baseline, use one gate `candidate-tests`, set both required-gate lists to `["candidate-tests"]`, use `checks: {"candidate-tests": "pass"}`, `status: "pass"`, `acceptedExceptions: []`, and end `report.md` with exactly `Final gate: PASS`. Use distinct full lowercase hexadecimal base/head IDs and set `finalHead` equal to head. Assert the baseline returns `[]` before each negative mutation.

## C-2 Accepted-exception policy and evidence binding

The adapter policy shape is `acceptedDispositions: {EXPECTED_MUTATION_LIVENESS: {allowedRawStatuses: ["TIMED_OUT"], requiredEvidence: [...]}}`; the field is exactly `allowedRawStatuses`, not `rawStatuses`.

For an accepted exception baseline, use all five categories `repeatable-focused-rerun`, `unmutated-tests-pass`, `complete-xml-and-logs`, `source-level-liveness-invariant`, and `maintainer-or-independent-review`. The exception contains non-empty `phase`, `rawStatus`, `disposition`, `accepted: true`, `evidence`, and non-blank `approvedBy`. Each required category maps to a non-empty path list. Every path exists inside the run and is a key in `inputs` or `artifacts` with the correct SHA-256. The report ends `Final gate: PASS WITH ACCEPTED EXCEPTIONS`; status and required checks remain passing.

A missing required category, missing evidence file, unhashed path, disallowed raw status, or disallowed disposition MUST produce an error. A raw `TIMED_OUT` MUST NOT be rewritten as `KILLED` or pass.

## C-3 Global disposition-policy validity

Every declared `adapter.acceptedDispositions` entry MUST be an object with non-empty, non-blank, unique string lists for both `allowedRawStatuses` and `requiredEvidence`. This applies even when `acceptedExceptions` is empty and the policy is unused. Empty lists, blank values, duplicates, and non-object policies MUST produce errors. For every accepted exception, the selected policy MUST satisfy the same rules before its raw status and evidence are checked.

## C-4 Frozen input identity and resolution

`inputs` MUST be non-empty. Every key MUST be a canonical run-relative path beneath `inputs/`, with no absolute path, empty component, `.` or `..`, or root-output alias such as `contract.md`. Each declared file MUST exist and match its SHA-256. Its resolved path MUST also remain beneath the resolved `inputs/` directory. In particular, `inputs/output-alias.md` as a symlink to `../contract.md` MUST produce an error even if the declared digest matches the target. The `inputs/` directory itself MUST NOT be a symbolic link: replacing it with a symlink to the run root and declaring `inputs/contract.md` MUST produce an error. Empty inputs, root aliases, non-canonical paths, output symlinks, a symlinked input root, and hash mismatches MUST NOT pass.

## C-5 Resolved workspace identity

`workspace.base`, `workspace.head`, and `finalHead` MUST each be a full lowercase hexadecimal Git object ID: exactly 40 characters for SHA-1 or exactly 64 for SHA-256. Both forms MUST be accepted. `finalHead` MUST equal `workspace.head`. Symbolic refs, short IDs, uppercase IDs, missing values, other lengths, and mismatched final head MUST produce errors. `workspace.dirty` is required.

## C-6 Gate identity and completion

Both `adapter.requiredGates` and top-level `requiredGates` MUST be non-empty lists of non-blank, unique strings. The lists MUST match exactly. `checks` MUST contain a valid status for each required gate, and every required gate MUST be `pass` in a delivery-passing run. Blank names, duplicates, mismatched lists, missing results, and non-pass required results MUST produce errors. All-pass checks correspond to run status `pass`.

## C-7 Reproducible report and artifacts

The six required files and five directories from C-1 MUST exist, and every required file MUST be hashed in `artifacts`. `report.md` MUST have exactly one final-gate line as its last non-empty line. With no accepted exceptions it is `Final gate: PASS`; with valid accepted exceptions it is `Final gate: PASS WITH ACCEPTED EXCEPTIONS`. Missing hashes, duplicate or trailing final-gate content, and gate/status inconsistency MUST produce errors.

## C-8 Documentation example binding

The completed-manifest example in `inputs/reproducible-runs.md` MUST show hashes for all required artifacts and for every evidence path it references, including `inputs/pit-audit.md`. Its adapter policy uses `allowedRawStatuses` and the five non-empty unique categories from C-2.

## Candidate provenance and promotion

The 29 sealed candidates are byte-identical copies from the fresh, ephemeral Codex generation at the same frozen head and under this same contract. They are inputs to this continuation, not candidates revised after seeing implementation failures. Four behaviorally new candidates are eligible for promotion only after contrary-behavior verification and all frozen Python promotion commands pass. The remaining 25 are duplicate coverage and MUST NOT be promoted.

The run-specific Python regression destination is `scripts/`, using the CI discovery command recorded in `inputs/regression-suite.json`. Verification-integrity metadata and verbose discovery execution commands are frozen before promotion. New contract IDs C-0001 through C-0004 MUST map respectively to candidates 008, 013, 015, and 021.

## Applicability and uncertainties

No concurrency, cancellation, deadline, lifecycle, executor, queue, or resource-ownership semantics apply. Applicable required gates are candidate tests, reverse verification, the existing full Python suite, documentation checks, independent review, and regression tests for promoted candidates. Java consumer checks and Java PIT are not applicable to this Python-only tooling change and MUST NOT be reported as executed. Exact error wording and error order are not contracts unless explicitly stated; assert the identified validation boundary rather than unrelated messages.
