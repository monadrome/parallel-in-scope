# Reproducible assurance runs

An assurance result is reproducible only when the run records the exact inputs
and execution environment that produced it. A report copied without its run
manifest is an incomplete result.

## Run manifest

Create `${RUN_DIR}/run-manifest.json` before phase one. It must contain:

```json
{
  "schemaVersion": "contract-assurance-run/v1",
  "runId": "2026-11-12-example",
  "createdAt": "2026-11-12T12:00:00Z",
  "package": {"name": "contract-first-assurance", "version": "1.2.0"},
  "adapter": {"name": "vformation", "version": "1.2.0"},
  "workspace": {
    "base": "<resolved commit>",
    "head": "<resolved commit>",
    "dirty": false
  },
  "inputs": {"inputs/contract-source.md": "<sha256>"},
  "commands": [
    {
      "phase": "full-suite",
      "argv": ["mvn", "test"],
      "exitCode": 0,
      "stdout": "logs/full-suite.stdout.log",
      "stderr": "logs/full-suite.stderr.log"
    }
  ],
  "artifacts": {"report.md": "<sha256>"},
  "acceptedExceptions": [
    {
      "phase": "pit",
      "rawStatus": "TIMED_OUT",
      "disposition": "EXPECTED_MUTATION_LIVENESS",
      "evidence": ["logs/pit.stdout.log", "inputs/pit-audit.md"],
      "approvedBy": "maintainer"
    }
  ]
}
```

Rules:

- `base` and `head` are resolved commit IDs, never symbolic refs alone;
- a dirty workspace must include its binary patch under `inputs/`;
- every copied contract, API view, adapter, command log, and report is hashed;
- command records preserve the exact argv, exit code, stdout path, and stderr path;
- paths in the manifest are relative to `${RUN_DIR}` and must not contain `..`;
- a timeout, missing log, missing hash, or hash mismatch makes the run
  `INCONCLUSIVE`, never successful;
- an accepted exception may make the final gate `pass`, but only when its raw
  status, disposition, evidence paths, and approver are recorded and the
  disposition is allowed by the resolved host adapter;
- the manifest and input snapshots are written before tests run and are not
  rewritten after a failure to make a green result appear reproducible.

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
├── reverse-verification/
├── verification-summary.md
└── report.md
```

Run `scripts/verify_run.py --run-dir <run-dir>` after the final report. The
verifier checks required artifacts, relative paths, command logs, input hashes,
and output hashes without contacting a remote service.
