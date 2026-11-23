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
    def make_run(self, exception, policy, final_gate="PASS WITH ACCEPTED EXCEPTIONS"):
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
            content = f"evidence\nFinal gate: {final_gate}\n" if relative == "report.md" else "evidence\n"
            path.write_text(content, encoding="utf-8")

        digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
        manifest = {
            "schemaVersion": "contract-assurance-run/v2",
            "runId": "test",
            "createdAt": "2026-01-01T00:00:00Z",
            "package": {},
            "adapter": {"acceptedDispositions": policy, "requiredGates": ["pit"]},
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
            "acceptedExceptions": [] if exception is None else [exception],
        }
        (run / "run-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        return run

    def set_report(self, run, content):
        report = run / "report.md"
        if isinstance(content, bytes):
            report.write_bytes(content)
        else:
            report.write_text(content, encoding="utf-8")
        manifest_path = run / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["artifacts"]["report.md"] = hashlib.sha256(report.read_bytes()).hexdigest()
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    def update_manifest(self, run, **updates):
        manifest_path = run / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest.update(updates)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

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

    def test_accepts_pass_without_exceptions(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.assertEqual(verify_run.verify(run), [])

    def test_rejects_missing_final_gate(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.set_report(run, "evidence\n")
        errors = verify_run.verify(run)
        self.assertIn("report.md must contain exactly one final-gate line", errors)

    def test_rejects_duplicate_final_gate(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.set_report(run, "Final gate: PASS\nevidence\nFinal gate: PASS\n")
        errors = verify_run.verify(run)
        self.assertIn("report.md must contain exactly one final-gate line", errors)

    def test_rejects_failing_final_gate(self):
        run = self.make_run(None, {}, final_gate="FAIL")
        errors = verify_run.verify(run)
        self.assertIn("report final gate is not delivery-passing: FAIL", errors)
        self.assertIn("report final gate FAIL does not match run status pass", errors)

    def test_rejects_pass_with_accepted_exceptions_without_exceptions(self):
        run = self.make_run(None, {})
        errors = verify_run.verify(run)
        self.assertIn(
            "report final gate PASS WITH ACCEPTED EXCEPTIONS requires an accepted exception",
            errors,
        )

    def test_rejects_pass_with_unreported_exceptions(self):
        run = self.make_run(
            self.exception(),
            {"EXPECTED_MUTATION_LIVENESS": ["TIMED_OUT"]},
            final_gate="PASS",
        )
        errors = verify_run.verify(run)
        self.assertIn("report final gate PASS requires no accepted exceptions", errors)

    def test_rejects_content_after_final_gate(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.set_report(run, "evidence\nFinal gate: PASS\nlater evidence\n")
        errors = verify_run.verify(run)
        self.assertIn("report final-gate line must be the last non-empty line", errors)

    def test_rejects_non_pass_required_gate(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.update_manifest(run, checks={"pit": "fail"})
        errors = verify_run.verify(run)
        self.assertIn("required gate is not pass: pit (fail)", errors)

    def test_rejects_status_checks_mismatch(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.update_manifest(run, checks={"pit": "inconclusive"})
        errors = verify_run.verify(run)
        self.assertIn("run status pass does not match checks status inconclusive", errors)

    def test_rejects_v1_schema(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.update_manifest(run, schemaVersion="contract-assurance-run/v1")
        errors = verify_run.verify(run)
        self.assertIn("unsupported or missing run manifest schemaVersion", errors)

    def test_rejects_non_utf8_report(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.set_report(run, b"\xff")
        errors = verify_run.verify(run)
        self.assertTrue(any("cannot read report.md" in error for error in errors))

    def test_rejects_empty_required_gates_and_checks(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.update_manifest(run, requiredGates=[], checks={})
        errors = verify_run.verify(run)
        self.assertIn("requiredGates must be a non-empty list of strings", errors)
        self.assertIn("checks must contain the resolved gate results", errors)

    def test_rejects_adapter_required_gate_mismatch(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.update_manifest(run, requiredGates=["docs"])
        errors = verify_run.verify(run)
        self.assertIn("requiredGates does not match adapter.requiredGates", errors)


if __name__ == "__main__":
    unittest.main()
