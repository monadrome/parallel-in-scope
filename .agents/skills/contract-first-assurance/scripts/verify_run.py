#!/usr/bin/env python3
"""Verify the reproducibility metadata and hashes of an assurance run."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path


REQUIRED_FILES = (
    "contract.md",
    "contract-gaps.md",
    "candidate-status.csv",
    "verification-summary.md",
    "report.md",
)
REQUIRED_DIRECTORIES = ("inputs", "logs", "candidates", "reverse-verification")
VALID_STATUSES = {"pass", "fail", "blocked", "inconclusive", "skipped"}
ACCEPTED_EXCEPTION_FIELDS = ("phase", "rawStatus", "disposition", "accepted", "evidence", "approvedBy")


def safe_path(run_dir: Path, relative: str) -> Path:
    path = Path(relative)
    if path.is_absolute() or ".." in path.parts:
        raise ValueError(f"manifest path is not relative: {relative}")
    resolved = (run_dir / path).resolve()
    if run_dir.resolve() not in resolved.parents and resolved != run_dir.resolve():
        raise ValueError(f"manifest path escapes run directory: {relative}")
    return resolved


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify(run_dir: Path) -> list[str]:
    errors = []
    manifest_path = run_dir / "run-manifest.json"
    if not manifest_path.is_file():
        return [f"missing {manifest_path}"]
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return [f"cannot read run manifest: {error}"]

    if manifest.get("schemaVersion") != "contract-assurance-run/v1":
        errors.append("unsupported or missing run manifest schemaVersion")
    for key in ("runId", "createdAt", "package", "adapter", "workspace", "inputs", "commands", "artifacts"):
        if key not in manifest:
            errors.append(f"run manifest is missing {key}")
    workspace = manifest.get("workspace", {})
    for key in ("base", "head", "dirty"):
        if key not in workspace:
            errors.append(f"workspace metadata is missing {key}")

    final_head = manifest.get("finalHead")
    if final_head is not None and final_head != workspace.get("head"):
        errors.append("finalHead does not match workspace.head")

    status = manifest.get("status")
    if status is not None and status not in VALID_STATUSES:
        errors.append(f"unsupported run status: {status}")

    adapter = manifest.get("adapter", {})
    accepted_policy = adapter.get("acceptedDispositions") if isinstance(adapter, dict) else None
    accepted_exceptions = manifest.get("acceptedExceptions", [])
    if not isinstance(accepted_exceptions, list):
        errors.append("acceptedExceptions must be a list")
        accepted_exceptions = []
    for index, exception in enumerate(accepted_exceptions):
        if not isinstance(exception, dict):
            errors.append(f"accepted exception {index} is not an object")
            continue
        for field in ACCEPTED_EXCEPTION_FIELDS:
            if field not in exception:
                errors.append(f"accepted exception {index} is missing {field}")
        phase = exception.get("phase")
        if not isinstance(phase, str) or not phase:
            errors.append(f"accepted exception {index} phase must be non-empty")
        raw_status = exception.get("rawStatus")
        if not isinstance(raw_status, str) or not raw_status:
            errors.append(f"accepted exception {index} rawStatus must be a non-empty string")
        disposition = exception.get("disposition")
        if not isinstance(disposition, str) or not disposition:
            errors.append(f"accepted exception {index} disposition must be a non-empty string")
        if exception.get("accepted") is not True:
            errors.append(f"accepted exception {index} accepted must be true")
        if not isinstance(accepted_policy, dict):
            errors.append("adapter.acceptedDispositions is required when acceptedExceptions are present")
        elif isinstance(disposition, str):
            allowed_statuses = accepted_policy.get(disposition)
            if not isinstance(allowed_statuses, list) or any(not isinstance(value, str) for value in allowed_statuses):
                errors.append(f"accepted exception {index} disposition is not allowed by adapter: {disposition}")
            elif raw_status not in allowed_statuses:
                errors.append(f"accepted exception {index} rawStatus {raw_status} is not allowed for {disposition}")
        evidence = exception.get("evidence")
        if not isinstance(evidence, list) or not evidence or any(not isinstance(path, str) for path in evidence):
            errors.append(f"accepted exception {index} evidence must contain relative paths")
        else:
            for path in evidence:
                try:
                    exists = safe_path(run_dir, path).is_file()
                except (TypeError, ValueError) as error:
                    errors.append(f"accepted exception {index} evidence path is invalid: {error}")
                    continue
                if not exists:
                    errors.append(f"accepted exception {index} references missing evidence: {path}")
        approved_by = exception.get("approvedBy")
        if not isinstance(approved_by, str) or not approved_by:
            errors.append(f"accepted exception {index} approvedBy must be non-empty")
    required_gates = manifest.get("requiredGates", [])
    checks = manifest.get("checks", {})
    if not isinstance(required_gates, list) or not isinstance(checks, dict):
        errors.append("requiredGates must be a list and checks must be an object")
    else:
        if any(not isinstance(gate, str) for gate in required_gates):
            errors.append("requiredGates entries must be strings")
        for gate, gate_status in checks.items():
            if gate_status not in VALID_STATUSES:
                errors.append(f"unsupported gate status for {gate}: {gate_status}")
        for gate in required_gates:
            if not isinstance(gate, str):
                continue
            if checks.get(gate) != "pass":
                errors.append(f"required gate is not pass: {gate} ({checks.get(gate, 'missing')})")
        if status is not None and checks:
            precedence = {"skipped": 0, "pass": 1, "inconclusive": 2, "blocked": 3, "fail": 4}
            known = [value for value in checks.values() if value in precedence]
            if known:
                expected = max(known, key=precedence.get)
                if status != expected:
                    errors.append(f"run status {status} does not match checks status {expected}")

    for relative in REQUIRED_FILES:
        try:
            path = safe_path(run_dir, relative)
        except ValueError as error:
            errors.append(str(error))
            continue
        if not path.is_file():
            errors.append(f"missing required artifact: {relative}")
    for relative in REQUIRED_DIRECTORIES:
        if not (run_dir / relative).is_dir():
            errors.append(f"missing required directory: {relative}")

    def verify_hashes(field: str) -> None:
        values = manifest.get(field, {})
        if not isinstance(values, dict):
            errors.append(f"{field} must be an object of relative path to sha256")
            return
        for relative, expected in values.items():
            try:
                path = safe_path(run_dir, relative)
            except ValueError as error:
                errors.append(str(error))
                continue
            if not path.is_file():
                errors.append(f"{field} references missing file: {relative}")
            elif not isinstance(expected, str) or len(expected) != 64 or sha256(path) != expected:
                errors.append(f"{field} hash mismatch: {relative}")

    verify_hashes("inputs")
    verify_hashes("artifacts")
    artifacts = manifest.get("artifacts", {})
    if isinstance(artifacts, dict):
        for required in REQUIRED_FILES:
            if required not in artifacts:
                errors.append(f"artifacts does not hash required file: {required}")

    commands = manifest.get("commands", [])
    if not isinstance(commands, list):
        errors.append("commands must be a list")
    else:
        for index, command in enumerate(commands):
            if not isinstance(command, dict):
                errors.append(f"command {index} is not an object")
                continue
            for key in ("phase", "argv", "exitCode", "stdout", "stderr"):
                if key not in command:
                    errors.append(f"command {index} is missing {key}")
            for key in ("stdout", "stderr"):
                if key in command:
                    try:
                        if not safe_path(run_dir, command[key]).is_file():
                            errors.append(f"command {index} {key} log is missing: {command[key]}")
                    except (TypeError, ValueError) as error:
                        errors.append(f"command {index} {key}: {error}")
            exit_code = command.get("exitCode")
            if exit_code is not None and (isinstance(exit_code, bool) or not isinstance(exit_code, int)):
                errors.append(f"command {index} exitCode must be an integer or null")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", required=True, type=Path)
    args = parser.parse_args()
    errors = verify(args.run_dir.resolve())
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(f"OK: reproducible assurance run {args.run_dir.resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
