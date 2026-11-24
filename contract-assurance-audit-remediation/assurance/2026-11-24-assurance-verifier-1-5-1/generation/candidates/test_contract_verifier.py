"""Sealed contract candidates for the assurance-run verifier."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path


REQUIRED_FILES = (
    "contract.md",
    "contract-gaps.md",
    "candidate-status.csv",
    "promotion-status.csv",
    "verification-summary.md",
    "report.md",
)
REQUIRED_DIRECTORIES = (
    "inputs",
    "logs",
    "candidates",
    "promotions",
    "reverse-verification",
)
EVIDENCE_CATEGORIES = (
    "repeatable-focused-rerun",
    "unmutated-tests-pass",
    "complete-xml-and-logs",
    "source-level-liveness-invariant",
    "maintainer-or-independent-review",
)


class ContractVerifierCandidates(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        verifier_path = os.environ.get("VERIFY_RUN_PATH")
        if not verifier_path:
            raise unittest.SkipTest("VERIFY_RUN_PATH is required to load the verifier")

        spec = importlib.util.spec_from_file_location(
            "sealed_contract_verifier", verifier_path
        )
        if spec is None or spec.loader is None:
            raise RuntimeError("VERIFY_RUN_PATH does not identify an importable module")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        cls._verify_run = staticmethod(module.verify)

    def _errors(self, run_dir: Path) -> list[str]:
        errors = self._verify_run(run_dir)
        self.assertIsInstance(errors, list)
        return errors

    def _assert_valid(self, run_dir: Path) -> None:
        self.assertEqual([], self._errors(run_dir))

    def _assert_rejected(self, run_dir: Path, message: str) -> None:
        self.assertNotEqual([], self._errors(run_dir), message)

    @staticmethod
    def _sha256(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()

    @staticmethod
    def _manifest_path(run_dir: Path) -> Path:
        return run_dir / "run-manifest.json"

    def _read_manifest(self, run_dir: Path) -> dict:
        return json.loads(self._manifest_path(run_dir).read_text(encoding="utf-8"))

    def _write_manifest(self, run_dir: Path, manifest: dict) -> None:
        self._manifest_path(run_dir).write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )

    def _create_baseline(
        self,
        run_dir: Path,
        *,
        accepted_exception: bool = False,
        commit_id_length: int = 40,
    ) -> None:
        for directory in REQUIRED_DIRECTORIES:
            (run_dir / directory).mkdir()

        contents = {
            "contract.md": "# Contract\n",
            "contract-gaps.md": "# Contract gaps\n",
            "candidate-status.csv": "candidate_id\n",
            "promotion-status.csv": (
                "candidate_id,source_contract_id,contract_id,target_path,archive_path,"
                "status,reason\n"
            ),
            "verification-summary.md": "# Verification summary\n",
            "report.md": (
                "Verification completed.\n\n"
                f"Final gate: {'PASS WITH ACCEPTED EXCEPTIONS' if accepted_exception else 'PASS'}\n"
            ),
        }
        for relative, content in contents.items():
            (run_dir / relative).write_text(content, encoding="utf-8")

        base_input = run_dir / "inputs" / "evidence.txt"
        base_input.write_text("baseline evidence\n", encoding="utf-8")
        inputs = {"inputs/evidence.txt": self._sha256(base_input)}
        accepted_dispositions: dict = {}
        accepted_exceptions: list[dict] = []

        if accepted_exception:
            evidence: dict[str, list[str]] = {}
            for index, category in enumerate(EVIDENCE_CATEGORIES, start=1):
                relative = f"inputs/evidence-{index}.txt"
                evidence_file = run_dir / relative
                evidence_file.write_text(f"{category}\n", encoding="utf-8")
                inputs[relative] = self._sha256(evidence_file)
                evidence[category] = [relative]

            accepted_dispositions = {
                "EXPECTED_MUTATION_LIVENESS": {
                    "allowedRawStatuses": ["TIMED_OUT"],
                    "requiredEvidence": list(EVIDENCE_CATEGORIES),
                }
            }
            accepted_exceptions = [
                {
                    "phase": "mutation-testing",
                    "rawStatus": "TIMED_OUT",
                    "disposition": "EXPECTED_MUTATION_LIVENESS",
                    "accepted": True,
                    "evidence": evidence,
                    "approvedBy": "independent-reviewer",
                }
            ]

        base = "a" * commit_id_length
        head = "b" * commit_id_length
        manifest = {
            "schemaVersion": "contract-assurance-run/v2",
            "runId": "contract-assurance-run-001",
            "createdAt": "2026-11-24T00:00:00Z",
            "package": {},
            "adapter": {
                "requiredGates": ["candidate-tests"],
                "acceptedDispositions": accepted_dispositions,
            },
            "workspace": {"base": base, "head": head, "dirty": False},
            "finalHead": head,
            "inputs": inputs,
            "commands": [],
            "artifacts": {
                relative: self._sha256(run_dir / relative)
                for relative in REQUIRED_FILES
            },
            "status": "pass",
            "requiredGates": ["candidate-tests"],
            "checks": {"candidate-tests": "pass"},
            "acceptedExceptions": accepted_exceptions,
        }
        self._write_manifest(run_dir, manifest)

    def test_CONTRACT_TDD_001_accepts_bound_timeout_exception(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run_dir = Path(temporary)
            self._create_baseline(run_dir, accepted_exception=True)

            self._assert_valid(run_dir)
            manifest = self._read_manifest(run_dir)
            self.assertEqual(
                "TIMED_OUT", manifest["acceptedExceptions"][0]["rawStatus"]
            )

    def test_CONTRACT_TDD_002_rejects_invalid_accepted_exception_binding(self) -> None:
        cases = (
            "missing_required_category",
            "missing_evidence_file",
            "unhashed_evidence_path",
            "disallowed_raw_status",
            "disallowed_disposition",
        )
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                run_dir = Path(temporary)
                self._create_baseline(run_dir, accepted_exception=True)
                self._assert_valid(run_dir)
                manifest = self._read_manifest(run_dir)
                exception = manifest["acceptedExceptions"][0]
                first_category = EVIDENCE_CATEGORIES[0]

                if case == "missing_required_category":
                    del exception["evidence"][first_category]
                    self._write_manifest(run_dir, manifest)
                elif case == "missing_evidence_file":
                    (run_dir / exception["evidence"][first_category][0]).unlink()
                elif case == "unhashed_evidence_path":
                    unhashed = run_dir / "logs" / "unhashed-evidence.txt"
                    unhashed.write_text("unhashed evidence\n", encoding="utf-8")
                    exception["evidence"][first_category] = [
                        "logs/unhashed-evidence.txt"
                    ]
                    self._write_manifest(run_dir, manifest)
                elif case == "disallowed_raw_status":
                    exception["rawStatus"] = "KILLED"
                    self._write_manifest(run_dir, manifest)
                else:
                    exception["disposition"] = "UNAPPROVED_DISPOSITION"
                    self._write_manifest(run_dir, manifest)

                self._assert_rejected(run_dir, f"{case} was accepted")

    def test_CONTRACT_TDD_003_rejects_malformed_required_evidence_policy(self) -> None:
        cases = {
            "empty": [],
            "blank": [" "],
            "duplicate": [
                EVIDENCE_CATEGORIES[0],
                EVIDENCE_CATEGORIES[0],
            ],
        }
        for case, required_evidence in cases.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                run_dir = Path(temporary)
                self._create_baseline(run_dir, accepted_exception=True)
                self._assert_valid(run_dir)
                manifest = self._read_manifest(run_dir)
                exception = manifest["acceptedExceptions"][0]
                policy = manifest["adapter"]["acceptedDispositions"][
                    "EXPECTED_MUTATION_LIVENESS"
                ]
                evidence_path = exception["evidence"][EVIDENCE_CATEGORIES[0]]
                policy["requiredEvidence"] = required_evidence
                exception["evidence"] = {
                    category: list(evidence_path)
                    for category in dict.fromkeys(required_evidence)
                }
                self._write_manifest(run_dir, manifest)

                self._assert_rejected(
                    run_dir, f"{case} requiredEvidence policy was accepted"
                )

    def test_CONTRACT_TDD_004_rejects_invalid_input_declarations(self) -> None:
        cases = (
            "empty_inputs",
            "root_output_alias",
            "noncanonical_path",
            "digest_mismatch",
        )
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                run_dir = Path(temporary)
                self._create_baseline(run_dir)
                self._assert_valid(run_dir)
                manifest = self._read_manifest(run_dir)
                digest = manifest["inputs"]["inputs/evidence.txt"]

                if case == "empty_inputs":
                    manifest["inputs"] = {}
                elif case == "root_output_alias":
                    manifest["inputs"] = {
                        "contract.md": self._sha256(run_dir / "contract.md")
                    }
                elif case == "noncanonical_path":
                    manifest["inputs"] = {"inputs/../inputs/evidence.txt": digest}
                else:
                    replacement = ("0" if digest[0] != "0" else "1") + digest[1:]
                    manifest["inputs"] = {"inputs/evidence.txt": replacement}
                self._write_manifest(run_dir, manifest)

                self._assert_rejected(run_dir, f"{case} input declaration was accepted")

    def test_CONTRACT_TDD_005_rejects_output_backed_input_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run_dir = Path(temporary)
            self._create_baseline(run_dir)
            self._assert_valid(run_dir)
            manifest = self._read_manifest(run_dir)
            alias = run_dir / "inputs" / "output-alias.md"
            alias.symlink_to("../contract.md")
            manifest["inputs"] = {
                "inputs/output-alias.md": self._sha256(run_dir / "contract.md")
            }
            self._write_manifest(run_dir, manifest)

            self._assert_rejected(
                run_dir,
                "an input symlink resolving to a root output was accepted",
            )

    def test_CONTRACT_TDD_006_accepts_full_sha256_workspace_ids(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run_dir = Path(temporary)
            self._create_baseline(run_dir, commit_id_length=64)

            self._assert_valid(run_dir)

    def test_CONTRACT_TDD_007_rejects_invalid_workspace_identity(self) -> None:
        cases = (
            "symbolic_ref",
            "short_id",
            "uppercase_id",
            "missing_base",
            "other_length",
            "mismatched_final_head",
            "missing_dirty",
        )
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                run_dir = Path(temporary)
                self._create_baseline(run_dir)
                self._assert_valid(run_dir)
                manifest = self._read_manifest(run_dir)
                workspace = manifest["workspace"]

                if case == "symbolic_ref":
                    workspace["base"] = "refs/heads/main"
                elif case == "short_id":
                    workspace["head"] = "b" * 12
                    manifest["finalHead"] = workspace["head"]
                elif case == "uppercase_id":
                    workspace["base"] = "A" * 40
                elif case == "missing_base":
                    del workspace["base"]
                elif case == "other_length":
                    workspace["base"] = "a" * 41
                elif case == "mismatched_final_head":
                    manifest["finalHead"] = "c" * 40
                else:
                    del workspace["dirty"]
                self._write_manifest(run_dir, manifest)

                self._assert_rejected(run_dir, f"{case} workspace identity was accepted")


if __name__ == "__main__":
    unittest.main()
