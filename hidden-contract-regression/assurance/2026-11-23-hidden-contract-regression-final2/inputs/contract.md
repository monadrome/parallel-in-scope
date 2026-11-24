# Frozen contract: hidden assurance regressions

Scope: `e15010cf224e9ddbd2dfeca8262f6c1961eddee2..4a16413aa43eda10a4e0b00e0b91fa23e8896574`.
Evidence sources: the frozen diff, root and hidden-suite agent instructions, assurance package and adapter, verification-integrity contract, and PIT policy.

## C-101: hidden suite activation

The hidden regression module MUST stay outside the default Maven reactor. Activating `contract-tests` MUST include `verification/contract-tests` and its required upstream modules. The seed class `C0001TaskIdContractTest` MUST execute under that profile and preserve the public `TaskId` normalization and equality evidence already introduced by the change. No cancellation, deadline, context, executor, queue, lifecycle, or concurrency semantics change.

## C-102: metadata traceability

For every Java source under the hidden test root, the checker MUST require exactly one class matching `Cnnnn<Subject>ContractTest`, a matching filename, exactly one `@Tag("C-nnnn")` immediately above that class, and a contract ID unique across classes. Additional non-contract top-level helper classes are not forbidden by this rule. An empty suite MUST fail. Metadata-only checking MUST NOT treat a lexical `@Test` annotation as proof of execution.

Observable result: `python3 scripts/check-contract-tests.py [--test-root PATH]` exits 0 for valid metadata and 1 with `ERROR:` diagnostics for invalid metadata.

## C-103: fully qualified execution evidence

When `--reports PATH` is present, every declared contract class MUST have its own canonical `TEST-<FQCN>.xml` report containing at least one Surefire `<testcase>` whose `classname` exactly equals the fully qualified source class name (the declared package plus class, or the simple class for a default-package source). Aggregate or differently named report files MUST NOT satisfy this requirement. A same-named class in another package MUST NOT satisfy this requirement. Every matching testcase MUST have a non-empty `name`.

A class with no exact matching testcase MUST fail. Any matching testcase containing a direct `<skipped>`, `<failure>`, or `<error>` child MUST fail. Missing XML reports and malformed XML MUST fail rather than silently passing.

Observable result: `python3 scripts/check-contract-tests.py --reports PATH` exits 0 only when metadata and all execution evidence pass. CI and assurance promotion MUST obtain the reports from an explicit clean test run.

## C-104: promotion records and execution commands

`promotion-status.csv` MUST have exactly this header:

```text
candidate_id,source_contract_id,contract_id,target_path,archive_path,status,reason
```

Each row MUST have status `promoted` or `blocked` and a non-empty reason. A header-only file MUST be accepted when no candidate is eligible.

For every `promoted` row:
- `candidate_id` MUST be non-empty and unique among promoted rows, and every sealed `candidate-status.csv` candidate ID MUST also be non-empty and unique;
- a promoted `candidate_id` MUST occur in exactly one sealed candidate record;
- `source_contract_id` MUST be non-empty;
- `contract_id` MUST match `C-[0-9]{4}` and be unique among promoted rows;
- target and archive paths MUST use canonical relative POSIX spelling, with no empty, `.` or `..` components;
- `target_path` is repository-relative, unique, and under `adapter.regressionSuite.path`; `archive_path` is unique, under `promotions/`, exists, and occurs in the manifest's `artifacts` SHA-256 mapping with a matching hash;
- `promotionTargets` MUST map the target to `promotion-targets/<target_path>`; that frozen snapshot MUST exist, be hashed, and byte-match the archive;
- the run MUST have a passing `regression-tests` gate in both required-gate lists and checks;
- `adapter.regressionSuite` MUST freeze exact argv lists for `regression-metadata`, `regression-tests`, and `regression-execution`, and the command log MUST contain a successful exact-argv record for each phase.

The run verifier MUST reject a promoted claim with a missing/unhashed archive, an unsealed candidate, a path alias, or missing/failing promotion commands even when every other run artifact is valid.

## C-105: assurance finalization and isolation

A passing assurance run MUST use schema `contract-assurance-run/v2`, identical frozen `workspace.head` and `finalHead` when present, non-empty adapter-approved required gates with passing results, existing command logs, and valid input/artifact hashes. The report MUST end with exactly one passing final-gate line for delivery.

Implementation and production-fixing agents keep the hidden suite unread by default. The isolated generator receives only this contract, sanitized public CLI/type views, and execution instructions. Promotion is owned by a fresh assurance/maintenance context, not by the implementation agent or isolated generator. This is a context-isolation convention, not filesystem access control.

## C-106: verification integrity

The pre-existing accepted-exception verifier tests MAY move from `VerifyRunAcceptedExceptionTest` to the broader `VerifyRunTest` class without deleting their methods or reducing assertions. The integrity gate MUST accept only the sixteen exact reviewed rename keys and MUST continue reporting all protected path changes for code-owner review.

## Explicit uncertainties

Surefire XML is trusted only as output of the prescribed clean CI/promotion run; an arbitrary caller-supplied report is not a cryptographic provenance mechanism. Hidden-suite visibility is an agent context boundary, not secrecy. No historical assurance candidate is claimed as the source of bootstrap C-0001.
