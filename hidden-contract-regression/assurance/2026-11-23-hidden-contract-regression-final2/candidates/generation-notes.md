# Generation notes

**Status:** sealed

## Authorized inputs

This candidate set was derived only from:

- `contract.md`
- `api.md`
- `commands.md`

No repository source, tests, reports, Git metadata, parent directories, hidden suites, or real implementation were inspected.

## Candidate design

`candidate_tests.py` uses Python standard-library `unittest`, `subprocess`, `tempfile`, `pathlib`, and string helpers. Every fixture is created under a fresh `TemporaryDirectory` and removed automatically. The only command encoded in the candidates is the sanitized public checker invocation:

```text
python3 <ASSURANCE_REPO>/scripts/check-contract-tests.py --test-root <fixture> [--reports <reports>]
```

Each test method has a `CONTRACT-TDD-nnn` identifier and records its preconditions, operation, observation point, assertion, and uncertainty in its docstring.

| Candidate ID | Contract coverage | Intent |
| --- | --- | --- |
| CONTRACT-TDD-001 | C-102 | Accept one correctly named and tagged contract class while allowing a non-contract top-level helper. |
| CONTRACT-TDD-002 | C-102 | Reject an empty suite, displaced or repeated tags, filename mismatch, duplicate IDs, and multiple contract classes in one Java source. |
| CONTRACT-TDD-003 | C-103 | Accept exact FQCN evidence in the required canonical class-specific report. |
| CONTRACT-TDD-004 | C-103 | Reject a same-named testcase from another package. |
| CONTRACT-TDD-005 | C-103 | Reject an aggregate or differently named report even when it contains an exact-FQCN testcase. |
| CONTRACT-TDD-006 | C-103 | Reject a canonical report containing any exact-FQCN testcase with an empty method name. |
| CONTRACT-TDD-007 | C-102, C-103 | Show that a lexical `@Test` does not replace the required execution report. |
| CONTRACT-TDD-008 | C-103 | Reject malformed canonical XML. |
| CONTRACT-TDD-009 | C-103 | Reject direct `skipped`, `failure`, and `error` testcase outcomes even if another testcase passes. |

## Execution boundary

The candidates were not executed. They are intended for the main assurance context to run with `ASSURANCE_REPO` set to a real verification checkout, as specified by `commands.md`.

## Known uncertainty

The candidates assert only behavior required by the frozen contract and sanitized public API. They do not claim XML provenance, inspect the checker implementation, or prescribe diagnostic wording for C-103 failures.

<!-- SEALED: generated from contract.md, api.md, and commands.md only. -->
