from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest


_REQUIRED_FILES = (
    "contract.md",
    "contract-gaps.md",
    "candidate-status.csv",
    "promotion-status.csv",
    "verification-summary.md",
    "report.md",
)
_EVIDENCE_CATEGORIES = (
    "mutation-report",
    "mutation-log",
    "test-results",
    "change-patch",
    "run-notes",
)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _write_json(path: Path, value: object) -> None:
    _write_text(path, json.dumps(value, indent=2, sort_keys=True) + "\n")


def _load_verify():
    verify_run_path = os.environ.get("VERIFY_RUN_PATH")
    if not verify_run_path:
        raise RuntimeError("VERIFY_RUN_PATH must identify the verifier module under test")

    specification = importlib.util.spec_from_file_location(
        "contract_verifier_under_test", Path(verify_run_path)
    )
    if specification is None or specification.loader is None:
        raise RuntimeError("VERIFY_RUN_PATH did not produce an importable verifier module")

    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    verify = getattr(module, "verify", None)
    if not callable(verify):
        raise RuntimeError("The verifier module must expose callable verify(Path)")
    return verify


class ContractVerifierCandidates(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls._verify_function = _load_verify()

    def _write_manifest(self, run_dir: Path, manifest: dict) -> None:
        _write_json(run_dir / "inputs" / "reproducible-runs.md", manifest)

    def _valid_run(self, *, accepted_exception: bool = False) -> tuple[Path, dict]:
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        run_dir = Path(temporary_directory.name)

        for directory in (
            "inputs",
            "logs",
            "candidates",
            "promotions",
            "reverse-verification",
        ):
            (run_dir / directory).mkdir()

        adapter = {
            "requiredGates": ["candidate-tests"],
            "acceptedDispositions": {
                "EXPECTED_MUTATION_LIVENESS": {
                    "rawStatuses": ["TIMED_OUT"],
                    "requiredEvidence": list(_EVIDENCE_CATEGORIES),
                }
            },
        }
        _write_json(run_dir / "inputs" / "adapter.yaml", adapter)
        _write_text(run_dir / "inputs" / "AGENTS.md", "Frozen run instructions.\n")
        _write_text(run_dir / "inputs" / "change.patch", "diff --git a/a b/a\n")

        _write_text(run_dir / "contract.md", "# Contract\n")
        _write_text(run_dir / "contract-gaps.md", "# Contract gaps\n")
        _write_text(
            run_dir / "candidate-status.csv", "candidate_id,contract_id,status\n"
        )
        _write_text(
            run_dir / "promotion-status.csv",
            "candidate_id,source_contract_id,contract_id,target_path,archive_path,status,reason\n",
        )
        _write_text(run_dir / "verification-summary.md", "# Verification summary\n")

        final_gate = "PASS WITH ACCEPTED EXCEPTIONS" if accepted_exception else "PASS"
        _write_text(run_dir / "report.md", f"# Report\n\nFinal gate: {final_gate}\n")

        evidence_paths = {
            "mutation-report": "logs/mutation-report.txt",
            "mutation-log": "logs/mutation.log",
            "test-results": "logs/test-results.txt",
            "change-patch": "inputs/change.patch",
            "run-notes": "inputs/AGENTS.md",
        }
        accepted_exceptions = []
        if accepted_exception:
            for relative_path in evidence_paths.values():
                path = run_dir / relative_path
                if not path.exists():
                    _write_text(path, f"Evidence for {path.name}.\n")
            accepted_exceptions = [
                {
                    "phase": "mutation",
                    "rawStatus": "TIMED_OUT",
                    "disposition": "EXPECTED_MUTATION_LIVENESS",
                    "accepted": True,
                    "evidence": {
                        category: [relative_path]
                        for category, relative_path in evidence_paths.items()
                    },
                    "approvedBy": "reviewer",
                }
            ]

        input_paths = (
            "inputs/adapter.yaml",
            "inputs/AGENTS.md",
            "inputs/change.patch",
        )
        artifact_paths = list(_REQUIRED_FILES)
        if accepted_exception:
            artifact_paths.extend(
                relative_path
                for relative_path in evidence_paths.values()
                if relative_path.startswith("logs/")
            )

        manifest = {
            "workspace": {
                "base": "a" * 40,
                "head": "b" * 40,
                "dirty": False,
            },
            "finalHead": "b" * 40,
            "status": "pass",
            "adapter": adapter,
            "requiredGates": ["candidate-tests"],
            "checks": {"candidate-tests": "pass"},
            "inputs": {
                relative_path: _sha256(run_dir / relative_path)
                for relative_path in input_paths
            },
            "artifacts": {
                relative_path: _sha256(run_dir / relative_path)
                for relative_path in artifact_paths
            },
            "acceptedExceptions": accepted_exceptions,
        }
        self._write_manifest(run_dir, manifest)
        return run_dir, manifest

    def _assert_valid(self, run_dir: Path) -> None:
        errors = type(self)._verify_function(run_dir)
        self.assertIsInstance(errors, list)
        self.assertEqual([], errors, f"baseline fixture must be valid: {errors}")

    def _assert_rejected(self, run_dir: Path) -> None:
        errors = type(self)._verify_function(run_dir)
        self.assertIsInstance(errors, list)
        self.assertTrue(errors, "verifier accepted a contract-violating run")

    def test_CONTRACT_TDD_001_rejects_unhashed_accepted_exception_evidence(self) -> None:
        run_dir, manifest = self._valid_run(accepted_exception=True)
        self._assert_valid(run_dir)

        _write_text(run_dir / "logs" / "unhashed.txt", "Unbound evidence.\n")
        manifest["acceptedExceptions"][0]["evidence"]["mutation-log"] = [
            "logs/unhashed.txt"
        ]
        self._write_manifest(run_dir, manifest)

        self._assert_rejected(run_dir)

    def test_CONTRACT_TDD_002_rejects_root_artifact_as_manifest_input(self) -> None:
        run_dir, manifest = self._valid_run()
        self._assert_valid(run_dir)

        manifest["inputs"] = {"contract.md": _sha256(run_dir / "contract.md")}
        self._write_manifest(run_dir, manifest)

        self._assert_rejected(run_dir)

    def test_CONTRACT_TDD_003_rejects_uppercase_or_mismatched_final_head(self) -> None:
        run_dir, manifest = self._valid_run()
        self._assert_valid(run_dir)

        manifest["finalHead"] = "B" * 40
        self._write_manifest(run_dir, manifest)

        self._assert_rejected(run_dir)

    def test_CONTRACT_TDD_004_rejects_invalid_required_gate_completion(self) -> None:
        with self.subTest(case="mismatched required gate lists"):
            run_dir, manifest = self._valid_run()
            self._assert_valid(run_dir)

            manifest["requiredGates"] = ["candidate-tests", "documentation"]
            self._write_manifest(run_dir, manifest)

            self._assert_rejected(run_dir)

        with self.subTest(case="non-passing required gate"):
            run_dir, manifest = self._valid_run()
            self._assert_valid(run_dir)

            manifest["checks"]["candidate-tests"] = "fail"
            self._write_manifest(run_dir, manifest)

            self._assert_rejected(run_dir)

    def test_CONTRACT_TDD_005_rejects_incomplete_or_ambiguous_passing_report(self) -> None:
        with self.subTest(case="missing required artifact hash"):
            run_dir, manifest = self._valid_run()
            self._assert_valid(run_dir)

            del manifest["artifacts"]["report.md"]
            self._write_manifest(run_dir, manifest)

            self._assert_rejected(run_dir)

        with self.subTest(case="duplicate final gate"):
            run_dir, manifest = self._valid_run()
            self._assert_valid(run_dir)

            report_path = run_dir / "report.md"
            _write_text(report_path, "# Report\n\nFinal gate: PASS\nFinal gate: PASS\n")
            manifest["artifacts"]["report.md"] = _sha256(report_path)
            self._write_manifest(run_dir, manifest)

            self._assert_rejected(run_dir)

    def test_CONTRACT_TDD_006_rejects_rewritten_accepted_exception_raw_status(self) -> None:
        run_dir, manifest = self._valid_run(accepted_exception=True)
        self._assert_valid(run_dir)

        manifest["acceptedExceptions"][0]["rawStatus"] = "KILLED"
        self._write_manifest(run_dir, manifest)

        self._assert_rejected(run_dir)


if __name__ == "__main__":
    unittest.main()
