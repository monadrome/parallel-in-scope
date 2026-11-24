# Reproducible assurance runs

An assurance result is reproducible only when the run records the exact inputs
and execution environment that produced it. A report copied without its run
manifest is an incomplete result.

## Run manifest

Initialize `${RUN_DIR}/run-manifest.json` before phase one with the schema, package, adapter,
workspace, and frozen input hashes. Result fields are appended at finalization; the completed
manifest has this shape:

```json
{
  "schemaVersion": "contract-assurance-run/v2",
  "runId": "2026-11-12-example",
  "createdAt": "2026-11-12T12:00:00Z",
  "package": {"name": "contract-first-assurance", "version": "1.5.1"},
  "adapter": {
    "name": "vformation",
    "version": "1.5.1",
    "requiredGates": ["full-suite", "consumer", "pit", "independent-review", "docs"],
    "acceptedDispositions": {
      "EXPECTED_MUTATION_LIVENESS": {
        "allowedRawStatuses": ["TIMED_OUT"],
        "requiredEvidence": [
          "repeatable-focused-rerun",
          "unmutated-tests-pass",
          "complete-xml-and-logs",
          "source-level-liveness-invariant",
          "maintainer-or-independent-review"
        ]
      }
    }
  },
  "workspace": {
    "base": "<resolved commit>",
    "head": "<resolved commit>",
    "dirty": false
  },
  "finalHead": "<resolved commit>",
  "status": "pass",
  "requiredGates": ["full-suite", "consumer", "pit", "independent-review", "docs"],
  "checks": {
    "full-suite": "pass",
    "consumer": "pass",
    "pit": "pass",
    "independent-review": "pass",
    "docs": "pass"
  },
  "inputs": {
    "inputs/contract-source.md": "<sha256>",
    "inputs/pit-audit.md": "<sha256>"
  },
  "commands": [
    {
      "phase": "full-suite",
      "argv": ["mvn", "test"],
      "exitCode": 0,
      "stdout": "logs/full-suite.stdout.log",
      "stderr": "logs/full-suite.stderr.log"
    }
  ],
  "artifacts": {
    "contract.md": "<sha256>",
    "contract-gaps.md": "<sha256>",
    "candidate-status.csv": "<sha256>",
    "promotion-status.csv": "<sha256>",
    "verification-summary.md": "<sha256>",
    "report.md": "<sha256>",
    "logs/pit.stdout.log": "<sha256>"
  },
  "acceptedExceptions": [
    {
      "phase": "pit",
      "rawStatus": "TIMED_OUT",
      "disposition": "EXPECTED_MUTATION_LIVENESS",
      "accepted": true,
      "evidence": {
        "repeatable-focused-rerun": ["logs/pit.stdout.log"],
        "unmutated-tests-pass": ["logs/pit.stdout.log"],
        "complete-xml-and-logs": ["logs/pit.stdout.log", "inputs/pit-audit.md"],
        "source-level-liveness-invariant": ["inputs/pit-audit.md"],
        "maintainer-or-independent-review": ["inputs/pit-audit.md"]
      },
      "approvedBy": "maintainer"
    }
  ]
}
```

Rules:

- `base`, `head`, and `finalHead` are full resolved 40-character SHA-1 or 64-character SHA-256
  commit IDs, never symbolic refs alone;
- `adapter.requiredGates` is the resolved route's required-gate list; gate names are non-blank
  and unique, the top-level `requiredGates` matches it, and `checks` contains a result for every gate;
- `inputs` is non-empty and contains only frozen files whose declared and resolved paths remain
  beneath `inputs/`; an input symlink to a root output or another run directory is invalid;
- a dirty workspace must include its binary patch under `inputs/`;
- every copied contract, API view, adapter, command log, promotion record, promoted test copy,
  and report is hashed;
- every promoted row also requires a passing `regression-tests` gate and successful exact-argv
  command records for `regression-metadata`, `regression-tests`, and `regression-execution`, with
  those argv values frozen in `adapter.regressionSuite`;
- every promoted target has a `promotionTargets` entry and a hashed, byte-identical frozen copy at
  `promotion-targets/<target_path>` beneath the configured suite root;
- command records preserve the exact argv, exit code, stdout path, and stderr path;
- paths in the manifest are relative to `${RUN_DIR}` and must not contain `..`;
- a timeout, missing log, missing hash, or hash mismatch makes the run
  `INCONCLUSIVE`, never successful;
- an accepted exception may make the final gate `pass`, but only when its raw
  status, disposition, evidence, and approver are recorded and the disposition is
  allowed by the resolved host adapter; copy each disposition's `allowedRawStatuses`
  and non-empty, non-blank, unique `requiredEvidence` policy into the manifest, map every
  required evidence category to existing paths, and hash those paths under `inputs` or `artifacts` so
  `verify_run.py` can enforce the policy without reading a mutable parent checkout;
- frozen metadata, input snapshots, and their hashes are written before tests run and are never
  rewritten; `status`, `requiredGates`, `checks`, `acceptedExceptions`, command results, and
  final-artifact hashes are appended at finalization, including for failed or inconclusive runs.

## Required layout

```text
${RUN_DIR}/
├── run-manifest.json
├── inputs/
├── logs/
├── contract.md
├── contract-gaps.md
├── candidates/
├── candidate-status.csv
├── promotions/
├── promotion-status.csv
├── reverse-verification/
├── verification-summary.md
└── report.md
```

Run `scripts/verify_run.py --run-dir <run-dir>` after the final report. The
verifier checks required artifacts, relative paths, command logs, input hashes,
output hashes, and the report's final-gate consistency without contacting a remote
service. A report whose final gate is not `PASS` or `PASS WITH ACCEPTED EXCEPTIONS`
fails this delivery verifier even when its failure evidence is complete.
