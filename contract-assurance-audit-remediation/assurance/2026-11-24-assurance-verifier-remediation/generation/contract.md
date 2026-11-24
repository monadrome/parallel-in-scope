# Frozen contract: assurance run verifier remediation

- Scope: committed diff `6981df77ec413177b9c5d076cf82eb1388036a2a..4106c3798400f879eb06dab6f9846ba901ef384d`.
- Public entry point: `verify(run_dir: Path) -> list[str]` in the sanitized API view.
- Success means an empty error list. Validation failure means one or more stable, human-readable errors; the function MUST NOT mutate the run.
- Evidence sources: `inputs/reproducible-runs.md`, `inputs/adapter.yaml`, `inputs/AGENTS.md`, and `inputs/change.patch`.

## C-1 Accepted-exception evidence binding

Given an otherwise valid run whose report uses `PASS WITH ACCEPTED EXCEPTIONS`, each accepted exception MUST have a non-empty phase, raw status, disposition, `accepted: true`, evidence mapping, and non-blank approver. Its disposition MUST exist in `adapter.acceptedDispositions`; its raw status MUST be allowed by that policy. Every category in the policy's `requiredEvidence` MUST map to a non-empty list of paths. Every referenced evidence path MUST identify an existing file inside the run and MUST be present in either the manifest's `inputs` or `artifacts` hash map. Missing categories, missing files, or unhashed evidence MUST produce validation errors and MUST NOT be accepted as passing evidence. No concurrency, cancellation, lifecycle, or resource-ownership semantics apply.

## C-2 Frozen input identity

An otherwise passing manifest MUST contain a non-empty `inputs` object. Every key MUST be a canonical run-relative path beneath `inputs/`: no absolute path, empty component, `.` or `..`, and no root-level artifact such as `contract.md`. Every listed file MUST exist and match its SHA-256. Empty inputs, root-output aliases, non-canonical paths, and hash mismatches MUST produce validation errors. Input snapshots are immutable after freezing; verification is read-only.

## C-3 Resolved workspace identity

`workspace.base`, `workspace.head`, and top-level `finalHead` MUST each be exactly 40 lowercase hexadecimal characters. `finalHead` MUST equal `workspace.head`. Symbolic refs, short IDs, uppercase IDs, missing values, and mismatched final head MUST produce validation errors. `workspace.dirty` remains required; a dirty run requires a frozen binary patch under `inputs/`.

## C-4 Gate identity and completion

Both `adapter.requiredGates` and top-level `requiredGates` MUST be non-empty lists of strings, and each gate name MUST be non-blank and unique within its list. The two lists MUST match exactly. `checks` MUST contain a valid status for every required gate, and every required gate MUST have status `pass` for a delivery-passing run. Blank names, duplicates, mismatched lists, missing results, or non-pass required results MUST produce validation errors. A top-level run whose checks are all passing has status `pass`.

## C-5 Reproducible passing report

A passing run MUST contain `contract.md`, `contract-gaps.md`, `candidate-status.csv`, `promotion-status.csv`, `verification-summary.md`, and `report.md`, plus `inputs/`, `logs/`, `candidates/`, `promotions/`, and `reverse-verification/`. Every required file MUST be SHA-256 hashed in `artifacts`. `report.md` MUST end with exactly one non-empty trailing `Final gate: PASS` line when there are no accepted exceptions, or `Final gate: PASS WITH ACCEPTED EXCEPTIONS` when valid accepted exceptions exist. A missing required artifact hash or an inconsistent final gate MUST produce a validation error. Header-only `promotion-status.csv` with columns `candidate_id,source_contract_id,contract_id,target_path,archive_path,status,reason` is valid when no candidate is promoted.

## C-6 Unchanged accepted-exception raw status

An accepted mutation-liveness exception MAY allow a passing final gate only when its raw status remains `TIMED_OUT`, its disposition is `EXPECTED_MUTATION_LIVENESS`, and all five evidence categories from the adapter are present and hash-bound. The verifier MUST NOT rewrite a raw timeout as killed or pass. This contract shares the fixture and validation boundary of C-1; it does not introduce Java mutation execution into this Python-only remediation.

## Explicit uncertainties

None for the validation boundaries above. The applicable host gates for this Python-only change are candidate tests, reverse verification, the existing full Python suite, documentation checks, and independent review. Java consumer checks and Java PIT are outside the changed runtime and are not required gates for this run; their non-applicability MUST remain visible in the final report rather than being presented as executed checks.
