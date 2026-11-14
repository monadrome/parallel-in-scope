"""Tests for assurance-run accepted mutation exception validation."""

import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

VERIFY_RUN = Path(__file__).resolve().parents[2] / ".agents/skills/contract-first-assurance/scripts/verify_run.py"
spec = importlib.util.spec_from_file_location("verify_run", VERIFY_RUN)
verify_run = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify_run)


class VerifyRunAcceptedExceptionTest(unittest.TestCase):
    def make_run(self, exception, policy):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        run = Path(temp.name)
        for directory in ("inputs", "logs", "candidates", "reverse-verification"):
            (run / directory).mkdir()
        for relative in (
            "contract.md",
            "contract-gaps.md",
            "candidate-status.csv",
            "verification-summary.md",
            "report.md",
            "inputs/pit-audit.md",
            "logs/pit.log",
        ):
            path = run / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("evidence\n", encoding="utf-8")

        digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
        manifest = {
            "schemaVersion": "contract-assurance-run/v1",
            "runId": "test",
            "createdAt": "2026-01-01T00:00:00Z",
            "package": {},
            "adapter": {"acceptedDispositions": policy},
            "workspace": {"base": "a", "head": "a", "dirty": False},
            "inputs": {"inputs/pit-audit.md": digest(run / "inputs/pit-audit.md")},
            "commands": [],
            "artifacts": {
                relative: digest(run / relative)
                for relative in (
                    "contract.md",
                    "contract-gaps.md",
                    "candidate-status.csv",
                    "verification-summary.md",
                    "report.md",
                )
            },
            "status": "pass",
            "requiredGates": ["pit"],
            "checks": {"pit": "pass"},
            "acceptedExceptions": [exception],
        }
        (run / "run-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        return run

    def exception(self, raw_status="TIMED_OUT", disposition="EXPECTED_MUTATION_LIVENESS"):
        return {
            "phase": "pit",
            "rawStatus": raw_status,
            "disposition": disposition,
            "accepted": True,
            "evidence": ["logs/pit.log", "inputs/pit-audit.md"],
            "approvedBy": "maintainer",
        }

    def test_accepts_status_allowed_by_adapter(self):
        run = self.make_run(self.exception(), {"EXPECTED_MUTATION_LIVENESS": ["TIMED_OUT"]})
        self.assertEqual(verify_run.verify(run), [])

    def test_rejects_status_not_allowed_by_adapter(self):
        run = self.make_run(self.exception("MEMORY_ERROR"), {"EXPECTED_MUTATION_LIVENESS": ["TIMED_OUT"]})
        errors = verify_run.verify(run)
        self.assertTrue(any("rawStatus MEMORY_ERROR is not allowed" in error for error in errors))

    def test_rejects_missing_adapter_policy(self):
        run = self.make_run(self.exception(), {})
        errors = verify_run.verify(run)
        self.assertTrue(any("disposition is not allowed by adapter" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
