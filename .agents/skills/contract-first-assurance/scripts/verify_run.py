#!/usr/bin/env python3
"""Verify the reproducibility metadata and hashes of an assurance run."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
from pathlib import Path


REQUIRED_FILES = (
    "contract.md",
    "contract-gaps.md",
    "candidate-status.csv",
    "promotion-status.csv",
    "verification-summary.md",
    "report.md",
)
REQUIRED_DIRECTORIES = ("inputs", "logs", "candidates", "promotions", "reverse-verification")
VALID_STATUSES = {"pass", "fail", "blocked", "inconclusive", "skipped"}
FINAL_GATE_RUN_STATUSES = {
    "PASS": "pass",
    "PASS WITH ACCEPTED EXCEPTIONS": "pass",
    "FAIL": "fail",
    "BLOCKED": "blocked",
    "INCONCLUSIVE": "inconclusive",
}
PASSING_FINAL_GATES = {"PASS", "PASS WITH ACCEPTED EXCEPTIONS"}
ACCEPTED_EXCEPTION_FIELDS = ("phase", "rawStatus", "disposition", "accepted", "evidence", "approvedBy")
PROMOTION_FIELDS = (
    "candidate_id",
    "source_contract_id",
    "contract_id",
    "target_path",
    "archive_path",
    "status",
    "reason",
)
PROMOTION_CONTRACT_RE = re.compile(r"C-[0-9]{4}")
COMMIT_ID_RE = re.compile(r"(?:[0-9a-f]{40}|[0-9a-f]{64})")


def gate_name_errors(value, field: str) -> list[str]:
    if not isinstance(value, list) or not value or any(not isinstance(gate, str) for gate in value):
        return [f"{field} must be a non-empty list of strings"]
    errors = []
    if any(not gate.strip() for gate in value):
        errors.append(f"{field} gate names must be non-blank")
    if len(set(value)) != len(value):
        errors.append(f"{field} gate names must be unique")
    return errors


def canonical_path(value: str) -> bool:
    path = Path(value)
    return (
        bool(value)
        and not path.is_absolute()
        and all(part not in {"", ".", ".."} for part in value.split("/"))
        and path.as_posix() == value
    )


def sealed_candidates(run_dir: Path) -> tuple[set[str], list[str]]:
    path = run_dir / "candidate-status.csv"
    try:
        with path.open(encoding="utf-8", newline="") as stream:
            reader = csv.DictReader(stream)
            if reader.fieldnames is None or "candidate_id" not in reader.fieldnames:
                return set(), ["candidate-status.csv must contain a candidate_id column for promoted rows"]
            errors = []
            candidates = set()
            for index, row in enumerate(reader, start=2):
                candidate_id = row.get("candidate_id")
                if not candidate_id:
                    errors.append(f"candidate-status.csv row {index} candidate_id must be non-empty")
                elif candidate_id in candidates:
                    errors.append(f"candidate-status.csv row {index} has duplicate sealed candidate_id: {candidate_id}")
                else:
                    candidates.add(candidate_id)
    except (OSError, UnicodeError, csv.Error) as error:
        return set(), [f"cannot read candidate-status.csv for promoted rows: {error}"]
    return candidates, errors


def verify_promotions(run_dir: Path, manifest: dict) -> tuple[list[str], list[dict]]:
    artifacts = manifest.get("artifacts", {})
    if not isinstance(artifacts, dict):
        artifacts = {}
    errors = []
    path = run_dir / "promotion-status.csv"
    try:
        with path.open(encoding="utf-8", newline="") as stream:
            reader = csv.DictReader(stream)
            if reader.fieldnames != list(PROMOTION_FIELDS):
                return [f"promotion-status.csv header must be {','.join(PROMOTION_FIELDS)}"], []
            rows = list(reader)
    except (OSError, UnicodeError, csv.Error) as error:
        return [f"cannot read promotion-status.csv: {error}"], []

    candidates = set()
    if any(row.get("status") == "promoted" for row in rows):
        candidates, candidate_errors = sealed_candidates(run_dir)
        errors.extend(candidate_errors)
    adapter = manifest.get("adapter", {})
    suite = adapter.get("regressionSuite", {}) if isinstance(adapter, dict) else {}
    suite_root = suite.get("path") if isinstance(suite, dict) else None
    target_records = manifest.get("promotionTargets", {})
    candidate_ids = set()
    contract_ids = set()
    archive_paths = set()
    target_paths = set()
    for index, row in enumerate(rows, start=2):
        prefix = f"promotion-status.csv row {index}"
        if None in row or any(value is None for value in row.values()):
            errors.append(f"{prefix} does not match the promotion CSV schema")
            continue
        status = row["status"]
        if status not in {"promoted", "blocked"}:
            errors.append(f"{prefix} has unsupported status: {status}")
        if not row["reason"].strip():
            errors.append(f"{prefix} reason must be non-empty")
        if status != "promoted":
            continue

        candidate_id = row["candidate_id"]
        if not candidate_id:
            errors.append(f"{prefix} candidate_id must be non-empty")
        elif candidate_id not in candidates:
            errors.append(f"{prefix} candidate_id is not sealed in candidate-status.csv: {candidate_id}")
        if not row["source_contract_id"]:
            errors.append(f"{prefix} source_contract_id must be non-empty")
        contract_id = row["contract_id"]
        if PROMOTION_CONTRACT_RE.fullmatch(contract_id) is None:
            errors.append(f"{prefix} has invalid promoted contract_id: {contract_id}")
        archive_path = row["archive_path"]
        archive = Path(archive_path)
        if not canonical_path(archive_path) or not archive.parts or archive.parts[0] != "promotions":
            errors.append(
                f"{prefix} archive_path must be canonical; promoted archive must be a safe path under promotions/: {archive_path}"
            )
        else:
            archived_copy = run_dir / archive
            if not archived_copy.is_file():
                errors.append(f"{prefix} promoted archive is missing: {archive_path}")
            if archive_path not in artifacts:
                errors.append(f"{prefix} promoted archive is not hashed in artifacts: {archive_path}")
        target_path = row["target_path"]
        if not canonical_path(target_path):
            errors.append(f"{prefix} target_path must be canonical safe repository-relative path: {target_path}")
        else:
            snapshot_path = target_records.get(target_path) if isinstance(target_records, dict) else None
            expected_snapshot = f"promotion-targets/{target_path}"
            if snapshot_path != expected_snapshot:
                errors.append(f"{prefix} missing frozen target snapshot: expected {expected_snapshot}")
            elif not isinstance(suite_root, str) or not target_path.startswith(f"{suite_root}/"):
                errors.append(f"{prefix} target_path must stay under the configured regression suite root")
            else:
                snapshot = run_dir / snapshot_path
                if not snapshot.is_file():
                    errors.append(f"{prefix} frozen target snapshot is missing: {snapshot_path}")
                if snapshot_path not in artifacts:
                    errors.append(f"{prefix} frozen target snapshot is not hashed in artifacts: {snapshot_path}")
                archived_copy = run_dir / archive_path
                archive_is_safe = (
                    canonical_path(archive_path)
                    and bool(archive.parts)
                    and archive.parts[0] == "promotions"
                )
                if (
                    archive_is_safe
                    and snapshot.is_file()
                    and archived_copy.is_file()
                    and snapshot.read_bytes() != archived_copy.read_bytes()
                ):
                    errors.append(f"{prefix} target snapshot does not byte-match archive: {archive_path}")

        for kind, value, seen in (
            ("candidate_id", candidate_id, candidate_ids),
            ("contract_id", contract_id, contract_ids),
            ("archive_path", archive_path, archive_paths),
            ("target_path", target_path, target_paths),
        ):
            if value in seen:
                errors.append(f"{prefix} duplicates promoted {kind}: {value}")
            else:
                seen.add(value)
    return errors, rows


def verify_promotion_execution(manifest: dict, rows: list[dict]) -> list[str]:
    if not any(row.get("status") == "promoted" for row in rows):
        return []
    errors = []
    required_gates = manifest.get("requiredGates", [])
    checks = manifest.get("checks", {})
    regression_status = checks.get("regression-tests") if isinstance(checks, dict) else None
    if (
        not isinstance(required_gates, list)
        or "regression-tests" not in required_gates
        or regression_status != "pass"
    ):
        errors.append("promoted run requires a passing regression-tests gate")
    adapter = manifest.get("adapter", {})
    suite = adapter.get("regressionSuite", {}) if isinstance(adapter, dict) else {}
    commands = manifest.get("commands", [])
    if not isinstance(commands, list):
        commands = []
    for phase in ("regression-metadata", "regression-tests", "regression-execution"):
        expected = suite.get(phase) if isinstance(suite, dict) else None
        if not isinstance(expected, list) or not expected or any(not isinstance(value, str) for value in expected):
            errors.append(f"adapter.regressionSuite is missing a valid {phase} argv")
            continue
        if not any(
            isinstance(command, dict)
            and command.get("phase") == phase
            and command.get("argv") == expected
            and command.get("exitCode") == 0
            for command in commands
        ):
            errors.append(f"promoted run is missing successful {phase} command")
    return errors


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

    if manifest.get("schemaVersion") != "contract-assurance-run/v2":
        errors.append("unsupported or missing run manifest schemaVersion")
    for key in (
        "runId",
        "createdAt",
        "package",
        "adapter",
        "workspace",
        "inputs",
        "commands",
        "artifacts",
        "status",
        "requiredGates",
        "checks",
    ):
        if key not in manifest:
            errors.append(f"run manifest is missing {key}")
    workspace = manifest.get("workspace", {})
    if not isinstance(workspace, dict):
        errors.append("workspace must be an object")
        workspace = {}
    for key in ("base", "head", "dirty"):
        if key not in workspace:
            errors.append(f"workspace metadata is missing {key}")
    if isinstance(workspace, dict):
        for key in ("base", "head"):
            value = workspace.get(key)
            if not isinstance(value, str) or COMMIT_ID_RE.fullmatch(value) is None:
                errors.append(f"workspace.{key} must be a full commit ID")

    final_head = manifest.get("finalHead")
    if not isinstance(final_head, str) or COMMIT_ID_RE.fullmatch(final_head) is None:
        errors.append("finalHead must be the full final commit ID")
    elif final_head != workspace.get("head"):
        errors.append("finalHead does not match workspace.head")

    status = manifest.get("status")
    if status not in VALID_STATUSES:
        errors.append(f"unsupported run status: {status}")

    adapter = manifest.get("adapter", {})
    accepted_policy = adapter.get("acceptedDispositions") if isinstance(adapter, dict) else None
    adapter_required_gates = adapter.get("requiredGates") if isinstance(adapter, dict) else None
    errors.extend(gate_name_errors(adapter_required_gates, "adapter.requiredGates"))
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
        required_evidence = []
        if not isinstance(accepted_policy, dict):
            errors.append("adapter.acceptedDispositions is required when acceptedExceptions are present")
        elif isinstance(disposition, str):
            policy = accepted_policy.get(disposition)
            if not isinstance(policy, dict):
                errors.append(f"accepted exception {index} disposition is not allowed by adapter: {disposition}")
            else:
                allowed_statuses = policy.get("allowedRawStatuses")
                if not isinstance(allowed_statuses, list) or any(
                    not isinstance(value, str) for value in allowed_statuses
                ):
                    errors.append(f"accepted exception {index} has invalid allowedRawStatuses: {disposition}")
                elif raw_status not in allowed_statuses:
                    errors.append(f"accepted exception {index} rawStatus {raw_status} is not allowed for {disposition}")
                required_evidence = policy.get("requiredEvidence")
                if (
                    not isinstance(required_evidence, list)
                    or not required_evidence
                    or any(not isinstance(category, str) or not category.strip() for category in required_evidence)
                    or len(set(required_evidence)) != len(required_evidence)
                ):
                    errors.append(f"accepted exception {index} has invalid requiredEvidence: {disposition}")
                    required_evidence = []
        evidence = exception.get("evidence")
        if not isinstance(evidence, dict):
            errors.append(f"accepted exception {index} evidence must map required evidence categories to paths")
        else:
            for category in required_evidence:
                paths = evidence.get(category)
                if not isinstance(paths, list) or not paths or any(not isinstance(path, str) for path in paths):
                    errors.append(f"accepted exception {index} is missing required evidence: {category}")
            hashed = {
                path
                for field in ("inputs", "artifacts")
                for path in (manifest.get(field, {}) if isinstance(manifest.get(field, {}), dict) else {})
            }
            for category, paths in evidence.items():
                if not isinstance(paths, list):
                    continue
                for path in paths:
                    if not isinstance(path, str):
                        errors.append(f"accepted exception {index} evidence path for {category} must be a string")
                        continue
                    try:
                        exists = safe_path(run_dir, path).is_file()
                    except (TypeError, ValueError) as error:
                        errors.append(f"accepted exception {index} evidence path is invalid: {error}")
                        continue
                    if not exists:
                        errors.append(f"accepted exception {index} references missing evidence: {path}")
                    if path not in hashed:
                        errors.append(f"accepted exception {index} evidence is not hashed: {path}")
        approved_by = exception.get("approvedBy")
        if not isinstance(approved_by, str) or not approved_by.strip():
            errors.append(f"accepted exception {index} approvedBy must be non-empty")
    required_gates = manifest.get("requiredGates", [])
    checks = manifest.get("checks", {})
    if not isinstance(required_gates, list) or not isinstance(checks, dict):
        errors.append("requiredGates must be a list and checks must be an object")
    else:
        errors.extend(gate_name_errors(required_gates, "requiredGates"))
        if not checks:
            errors.append("checks must contain the resolved gate results")
        if isinstance(adapter_required_gates, list) and required_gates != adapter_required_gates:
            errors.append("requiredGates does not match adapter.requiredGates")
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

    report_path = run_dir / "report.md"
    if report_path.is_file():
        try:
            report_lines = report_path.read_text(encoding="utf-8").splitlines()
        except (OSError, UnicodeDecodeError) as error:
            errors.append(f"cannot read report.md: {error}")
        else:
            final_gate_indexes = [
                index for index, line in enumerate(report_lines) if line.startswith("Final gate: ")
            ]
            if len(final_gate_indexes) != 1:
                errors.append("report.md must contain exactly one final-gate line")
            else:
                index = final_gate_indexes[0]
                final_gate = report_lines[index][len("Final gate: "):]
                expected_status = FINAL_GATE_RUN_STATUSES.get(final_gate)
                if expected_status is None:
                    errors.append(f"unsupported report final gate: {final_gate}")
                else:
                    if final_gate not in PASSING_FINAL_GATES:
                        errors.append(f"report final gate is not delivery-passing: {final_gate}")
                    if status != expected_status:
                        errors.append(
                            f"report final gate {final_gate} does not match run status {status}"
                        )
                    if final_gate == "PASS" and accepted_exceptions:
                        errors.append("report final gate PASS requires no accepted exceptions")
                    if final_gate == "PASS WITH ACCEPTED EXCEPTIONS" and not accepted_exceptions:
                        errors.append(
                            "report final gate PASS WITH ACCEPTED EXCEPTIONS requires an accepted exception"
                        )
                if any(line.strip() for line in report_lines[index + 1:]):
                    errors.append("report final-gate line must be the last non-empty line")

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

    inputs = manifest.get("inputs")
    if not isinstance(inputs, dict) or not inputs:
        errors.append("inputs must contain frozen files under inputs/")
    else:
        inputs_root = (run_dir / "inputs").resolve()
        for relative in inputs:
            if not isinstance(relative, str) or not canonical_path(relative) or not relative.startswith("inputs/"):
                errors.append(f"inputs must contain only files under inputs/: {relative}")
                continue
            try:
                resolved = safe_path(run_dir, relative)
            except ValueError as error:
                errors.append(str(error))
                continue
            if inputs_root not in resolved.parents:
                errors.append(f"inputs entries must resolve beneath inputs/: {relative}")

    verify_hashes("inputs")
    verify_hashes("artifacts")
    artifacts = manifest.get("artifacts", {})
    promotion_errors, promotion_rows = verify_promotions(run_dir, manifest)
    errors.extend(promotion_errors)
    errors.extend(verify_promotion_execution(manifest, promotion_rows))
    if isinstance(artifacts, dict):
        for required in REQUIRED_FILES:
            if required not in artifacts:
                errors.append(f"artifacts does not hash required file: {required}")
        promotions = run_dir / "promotions"
        if promotions.is_dir():
            for path in sorted(promotions.rglob("*")):
                if path.is_file():
                    relative = path.relative_to(run_dir).as_posix()
                    if relative not in artifacts:
                        errors.append(f"artifacts does not hash promoted test copy: {relative}")

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
