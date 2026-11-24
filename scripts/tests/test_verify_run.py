"""Tests for assurance-run verification."""

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


class VerifyRunTest(unittest.TestCase):
    def make_run(self, exception, policy, final_gate="PASS WITH ACCEPTED EXCEPTIONS"):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        run = Path(temp.name)
        for directory in ("inputs", "logs", "candidates", "promotions", "reverse-verification"):
            (run / directory).mkdir()
        for relative in (
            "contract.md",
            "contract-gaps.md",
            "candidate-status.csv",
            "promotion-status.csv",
            "verification-summary.md",
            "report.md",
            "inputs/pit-audit.md",
            "logs/pit.log",
        ):
            path = run / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            if relative == "report.md":
                content = f"evidence\nFinal gate: {final_gate}\n"
            elif relative == "promotion-status.csv":
                content = (
                    "candidate_id,source_contract_id,contract_id,target_path,archive_path,status,reason\n"
                )
            elif relative == "candidate-status.csv":
                content = "candidate_id\n"
            else:
                content = "evidence\n"
            path.write_text(content, encoding="utf-8")

        digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
        manifest = {
            "schemaVersion": "contract-assurance-run/v2",
            "runId": "test",
            "createdAt": "2026-01-01T00:00:00Z",
            "package": {},
            "adapter": {"acceptedDispositions": policy, "requiredGates": ["pit"]},
            "workspace": {"base": "a" * 40, "head": "a" * 40, "dirty": False},
            "finalHead": "a" * 40,
            "inputs": {"inputs/pit-audit.md": digest(run / "inputs/pit-audit.md")},
            "commands": [],
            "artifacts": {
                relative: digest(run / relative)
                for relative in (
                    "contract.md",
                    "contract-gaps.md",
                    "candidate-status.csv",
                    "promotion-status.csv",
                    "verification-summary.md",
                    "report.md",
                    "logs/pit.log",
                )
            },
            "status": "pass",
            "requiredGates": ["pit"],
            "checks": {"pit": "pass"},
            "acceptedExceptions": [] if exception is None else [exception],
        }
        (run / "run-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        return run

    def set_promotion_status(self, run, rows):
        path = run / "promotion-status.csv"
        header = "candidate_id,source_contract_id,contract_id,target_path,archive_path,status,reason\n"
        path.write_text(header + "".join(f"{row}\n" for row in rows), encoding="utf-8")
        manifest_path = run / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["artifacts"]["promotion-status.csv"] = hashlib.sha256(path.read_bytes()).hexdigest()
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    def add_promoted_copy(self, run, relative="promotions/C0001TaskIdContractTest.java"):
        path = run / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("class C0001TaskIdContractTest {}\n", encoding="utf-8")
        manifest_path = run / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["artifacts"][relative] = hashlib.sha256(path.read_bytes()).hexdigest()
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    def set_candidates(self, run, candidate_ids):
        path = run / "candidate-status.csv"
        path.write_text("candidate_id\n" + "".join(f"{candidate}\n" for candidate in candidate_ids), encoding="utf-8")
        manifest_path = run / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["artifacts"]["candidate-status.csv"] = hashlib.sha256(path.read_bytes()).hexdigest()
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    def add_promotion_evidence(self, run):
        manifest_path = run / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        gates = manifest["requiredGates"] + ["regression-tests"]
        manifest["requiredGates"] = gates
        manifest["adapter"]["requiredGates"] = gates
        manifest["checks"]["regression-tests"] = "pass"
        commands = {
            "regression-metadata": ["python3", "scripts/check-contract-tests.py"],
            "regression-tests": [
                "mvn",
                "-Pcontract-tests",
                "-pl",
                "verification/contract-tests",
                "-am",
                "clean",
                "test",
            ],
            "regression-execution": [
                "python3",
                "scripts/check-contract-tests.py",
                "--reports",
                "verification/contract-tests/target/surefire-reports",
            ],
        }
        manifest["adapter"]["regressionSuite"] = {**commands, "path": "verification/contract-tests"}
        target = "verification/contract-tests/src/test/java/C0001TaskIdContractTest.java"
        snapshot = f"promotion-targets/{target}"
        snapshot_path = run / snapshot
        snapshot_path.parent.mkdir(parents=True, exist_ok=True)
        snapshot_path.write_bytes((run / "promotions/C0001TaskIdContractTest.java").read_bytes())
        manifest["promotionTargets"] = {target: snapshot}
        manifest["artifacts"][snapshot] = hashlib.sha256(snapshot_path.read_bytes()).hexdigest()
        for phase, argv in commands.items():
            stdout = f"logs/{phase}.stdout.log"
            stderr = f"logs/{phase}.stderr.log"
            (run / stdout).write_text("pass\n", encoding="utf-8")
            (run / stderr).write_text("", encoding="utf-8")
            manifest["commands"].append(
                {"phase": phase, "argv": argv, "exitCode": 0, "stdout": stdout, "stderr": stderr}
            )
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    def make_promoted_run(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.add_promoted_copy(run)
        self.set_candidates(run, ["CONTRACT-TDD-001"])
        self.set_promotion_status(
            run,
            [
                "CONTRACT-TDD-001,C-0001,C-0001,"
                "verification/contract-tests/src/test/java/C0001TaskIdContractTest.java,"
                "promotions/C0001TaskIdContractTest.java,promoted,passed promotion gates"
            ],
        )
        self.add_promotion_evidence(run)
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

    def mutation_policy(self):
        return {
            "EXPECTED_MUTATION_LIVENESS": {
                "allowedRawStatuses": ["TIMED_OUT"],
                "requiredEvidence": [
                    "repeatable-focused-rerun",
                    "unmutated-tests-pass",
                    "complete-xml-and-logs",
                    "source-level-liveness-invariant",
                    "maintainer-or-independent-review",
                ],
            }
        }

    def exception(self, raw_status="TIMED_OUT", disposition="EXPECTED_MUTATION_LIVENESS"):
        return {
            "phase": "pit",
            "rawStatus": raw_status,
            "disposition": disposition,
            "accepted": True,
            "evidence": {
                "repeatable-focused-rerun": ["logs/pit.log"],
                "unmutated-tests-pass": ["logs/pit.log"],
                "complete-xml-and-logs": ["logs/pit.log", "inputs/pit-audit.md"],
                "source-level-liveness-invariant": ["inputs/pit-audit.md"],
                "maintainer-or-independent-review": ["inputs/pit-audit.md"],
            },
            "approvedBy": "maintainer",
        }

    def test_accepts_status_allowed_by_adapter(self):
        run = self.make_run(self.exception(), self.mutation_policy())
        self.assertEqual(verify_run.verify(run), [])

    def test_rejects_status_not_allowed_by_adapter(self):
        run = self.make_run(self.exception("MEMORY_ERROR"), self.mutation_policy())
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
            self.mutation_policy(),
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

    def test_rejects_promoted_row_without_archived_copy(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.set_promotion_status(
            run,
            [
                "CONTRACT-TDD-001,C-0001,C-0001,"
                "verification/contract-tests/src/test/java/C0001TaskIdContractTest.java,"
                "promotions/C0001TaskIdContractTest.java,promoted,passed promotion gates"
            ],
        )
        errors = verify_run.verify(run)
        self.assertTrue(any("promoted archive is missing" in error for error in errors))
        self.assertTrue(any("promoted archive is not hashed in artifacts" in error for error in errors))

    def test_accepts_promoted_row_with_a_hashed_archive(self):
        run = self.make_promoted_run()
        self.assertEqual(verify_run.verify(run), [])

    def test_rejects_promoted_row_without_frozen_target_snapshot(self):
        run = self.make_promoted_run()
        self.update_manifest(run, promotionTargets={})
        errors = verify_run.verify(run)
        self.assertTrue(any("missing frozen target snapshot" in error for error in errors))

    def test_rejects_promoted_target_snapshot_that_differs_from_archive(self):
        run = self.make_promoted_run()
        target = "verification/contract-tests/src/test/java/C0001TaskIdContractTest.java"
        snapshot = run / "promotion-targets" / target
        snapshot.write_text("changed\n", encoding="utf-8")
        errors = verify_run.verify(run)
        self.assertTrue(any("target snapshot does not byte-match archive" in error for error in errors))

    def test_rejects_duplicate_sealed_candidate_ids(self):
        run = self.make_promoted_run()
        self.set_candidates(run, ["CONTRACT-TDD-001", "CONTRACT-TDD-001"])
        errors = verify_run.verify(run)
        self.assertTrue(any("duplicate sealed candidate_id" in error for error in errors))

    def test_rejects_promoted_row_without_regression_command_evidence(self):
        run = self.make_promoted_run()
        self.update_manifest(run, commands=[])
        errors = verify_run.verify(run)
        for phase in ("regression-metadata", "regression-tests", "regression-execution"):
            self.assertTrue(any(f"missing successful {phase} command" in error for error in errors))

    def test_rejects_promoted_row_with_failed_regression_command(self):
        run = self.make_promoted_run()
        manifest_path = run / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["commands"][0]["exitCode"] = 1
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = verify_run.verify(run)
        self.assertTrue(any("missing successful regression-metadata command" in error for error in errors))

    def test_rejects_promoted_row_without_regression_gate(self):
        run = self.make_promoted_run()
        manifest_path = run / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["requiredGates"] = ["pit"]
        manifest["adapter"]["requiredGates"] = ["pit"]
        del manifest["checks"]["regression-tests"]
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = verify_run.verify(run)
        self.assertIn("promoted run requires a passing regression-tests gate", errors)

    def test_rejects_invalid_promotion_status_and_empty_reason(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.set_promotion_status(
            run,
            [
                "CONTRACT-TDD-001,C-0001,,,,blocked,",
                "CONTRACT-TDD-002,C-0002,,,,unknown,no eligible candidate",
            ],
        )
        errors = verify_run.verify(run)
        self.assertTrue(any("reason must be non-empty" in error for error in errors))
        self.assertTrue(any("unsupported status: unknown" in error for error in errors))

    def test_rejects_invalid_promoted_identity_and_paths(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.set_promotion_status(
            run,
            [
                "CONTRACT-TDD-001,C-0001,C-1,/tmp/C0001TaskIdContractTest.java,"
                "../C0001TaskIdContractTest.java,promoted,passed promotion gates"
            ],
        )
        errors = verify_run.verify(run)
        self.assertTrue(any("invalid promoted contract_id: C-1" in error for error in errors))
        self.assertTrue(any("safe path under promotions/" in error for error in errors))
        self.assertTrue(any("safe repository-relative path: /tmp/" in error for error in errors))

    def test_rejects_duplicate_promoted_identity_and_paths(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.add_promoted_copy(run)
        row = (
            "CONTRACT-TDD-001,C-0001,C-0001,"
            "verification/contract-tests/src/test/java/C0001TaskIdContractTest.java,"
            "promotions/C0001TaskIdContractTest.java,promoted,passed promotion gates"
        )
        self.set_promotion_status(run, [row, row.replace("CONTRACT-TDD-001", "CONTRACT-TDD-002")])
        errors = verify_run.verify(run)
        for field in ("contract_id", "archive_path", "target_path"):
            self.assertTrue(any(f"duplicates promoted {field}" in error for error in errors))

    def test_rejects_noncanonical_promotion_path_aliases(self):
        run = self.make_promoted_run()
        self.set_candidates(run, ["CONTRACT-TDD-001", "CONTRACT-TDD-002"])
        self.set_promotion_status(
            run,
            [
                "CONTRACT-TDD-001,C-0001,C-0001,"
                "verification/contract-tests/src/test/java/C0001TaskIdContractTest.java,"
                "promotions/C0001TaskIdContractTest.java,promoted,passed promotion gates",
                "CONTRACT-TDD-002,C-0002,C-0002,"
                "verification/contract-tests/src/test/java/./C0002TaskIdContractTest.java,"
                "promotions/./C0001TaskIdContractTest.java,promoted,passed promotion gates",
            ],
        )
        manifest_path = run / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["artifacts"]["promotions/./C0001TaskIdContractTest.java"] = manifest["artifacts"][
            "promotions/C0001TaskIdContractTest.java"
        ]
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = verify_run.verify(run)
        self.assertTrue(any("archive_path must be canonical" in error for error in errors))
        self.assertTrue(any("target_path must be canonical" in error for error in errors))

    def test_rejects_promoted_row_without_candidate_origin(self):
        run = self.make_promoted_run()
        self.set_promotion_status(
            run,
            [
                ",,C-0001,verification/contract-tests/src/test/java/C0001TaskIdContractTest.java,"
                "promotions/C0001TaskIdContractTest.java,promoted,passed promotion gates"
            ],
        )
        errors = verify_run.verify(run)
        self.assertTrue(any("candidate_id must be non-empty" in error for error in errors))
        self.assertTrue(any("source_contract_id must be non-empty" in error for error in errors))

    def test_rejects_duplicate_promoted_candidates(self):
        run = self.make_promoted_run()
        self.set_promotion_status(
            run,
            [
                "CONTRACT-TDD-001,C-0001,C-0001,"
                "verification/contract-tests/src/test/java/C0001TaskIdContractTest.java,"
                "promotions/C0001TaskIdContractTest.java,promoted,passed promotion gates",
                "CONTRACT-TDD-001,C-0002,C-0002,"
                "verification/contract-tests/src/test/java/C0002TaskIdContractTest.java,"
                "promotions/C0002TaskIdContractTest.java,promoted,passed promotion gates",
            ],
        )
        errors = verify_run.verify(run)
        self.assertTrue(any("duplicates promoted candidate_id" in error for error in errors))

    def test_rejects_unsealed_promoted_candidate(self):
        run = self.make_promoted_run()
        self.set_candidates(run, ["CONTRACT-TDD-999"])
        errors = verify_run.verify(run)
        self.assertTrue(any("candidate_id is not sealed" in error for error in errors))

    def test_rejects_invalid_promotion_header(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.set_promotion_status(run, [])
        (run / "promotion-status.csv").write_text("candidate_id,status\n", encoding="utf-8")
        errors = verify_run.verify(run)
        self.assertTrue(any("promotion-status.csv header must be" in error for error in errors))

    def test_accepts_header_only_promotion_status(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.assertEqual(verify_run.verify(run), [])

    def test_requires_hashes_for_promoted_test_copies(self):
        run = self.make_run(self.exception(), self.mutation_policy())
        (run / "promotions/C0001ContractTest.java").write_text("class C0001ContractTest {}\n", encoding="utf-8")
        errors = verify_run.verify(run)
        self.assertTrue(any("artifacts does not hash promoted test copy" in error for error in errors))

    def test_rejects_placeholder_accepted_exception_evidence(self):
        exception = self.exception()
        del exception["evidence"]["source-level-liveness-invariant"]
        run = self.make_run(exception, self.mutation_policy())
        errors = verify_run.verify(run)
        self.assertIn(
            "accepted exception 0 is missing required evidence: source-level-liveness-invariant",
            errors,
        )

    def test_rejects_unhashed_accepted_exception_evidence(self):
        run = self.make_run(self.exception(), self.mutation_policy())
        manifest_path = run / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        del manifest["artifacts"]["logs/pit.log"]
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        errors = verify_run.verify(run)
        self.assertIn("accepted exception 0 evidence is not hashed: logs/pit.log", errors)

    def test_rejects_empty_frozen_inputs(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.update_manifest(run, inputs={})
        errors = verify_run.verify(run)
        self.assertIn("inputs must contain frozen files under inputs/", errors)

    def test_rejects_frozen_input_outside_inputs_directory(self):
        run = self.make_run(None, {}, final_gate="PASS")
        digest = hashlib.sha256((run / "contract.md").read_bytes()).hexdigest()
        self.update_manifest(run, inputs={"contract.md": digest})
        errors = verify_run.verify(run)
        self.assertIn("inputs must contain only files under inputs/: contract.md", errors)

    def test_rejects_symbolic_workspace_revisions(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.update_manifest(
            run,
            workspace={"base": "origin/dev/v0.5.0", "head": "HEAD", "dirty": False},
            finalHead=None,
        )
        errors = verify_run.verify(run)
        self.assertTrue(any("workspace.base must be a full commit ID" in error for error in errors))
        self.assertTrue(any("workspace.head must be a full commit ID" in error for error in errors))
        self.assertIn("finalHead must be the full final commit ID", errors)

    def test_rejects_blank_and_duplicate_required_gates(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.update_manifest(
            run,
            requiredGates=[" ", "pit", "pit"],
            checks={" ": "pass", "pit": "pass"},
        )
        self.update_manifest(run, adapter={"acceptedDispositions": {}, "requiredGates": [" ", "pit", "pit"]})
        errors = verify_run.verify(run)
        self.assertTrue(any("gate names must be non-blank" in error for error in errors))
        self.assertTrue(any("gate names must be unique" in error for error in errors))

    def test_rejects_empty_blank_and_duplicate_required_evidence_policies(self):
        for required_evidence in ([], [" "], ["complete-xml-and-logs", "complete-xml-and-logs"]):
            with self.subTest(required_evidence=required_evidence):
                policy = self.mutation_policy()
                policy["EXPECTED_MUTATION_LIVENESS"]["requiredEvidence"] = required_evidence
                exception = self.exception()
                exception["evidence"] = {category: ["logs/pit.log"] for category in required_evidence}
                run = self.make_run(exception, policy)
                errors = verify_run.verify(run)
                self.assertIn(
                    "accepted exception 0 has invalid requiredEvidence: EXPECTED_MUTATION_LIVENESS",
                    errors,
                )

    def test_rejects_frozen_input_symlink_to_output_artifact(self):
        run = self.make_run(None, {}, final_gate="PASS")
        alias = run / "inputs/output-alias.md"
        alias.symlink_to(run / "contract.md")
        digest = hashlib.sha256(alias.read_bytes()).hexdigest()
        self.update_manifest(run, inputs={"inputs/output-alias.md": digest})
        errors = verify_run.verify(run)
        self.assertIn("inputs entries must resolve beneath inputs/: inputs/output-alias.md", errors)

    def test_accepts_full_sha256_workspace_revisions(self):
        run = self.make_run(None, {}, final_gate="PASS")
        self.update_manifest(
            run,
            workspace={"base": "a" * 64, "head": "b" * 64, "dirty": False},
            finalHead="b" * 64,
        )
        self.assertEqual(verify_run.verify(run), [])

    def test_completed_manifest_example_hashes_all_evidence_paths(self):
        reference = (
            Path(__file__).resolve().parents[2]
            / ".agents/skills/contract-first-assurance/references/reproducible-runs.md"
        )
        example = reference.read_text(encoding="utf-8").split("```json", 1)[1].split("```", 1)[0]
        manifest = json.loads(example)
        hashed = set(manifest["inputs"]) | set(manifest["artifacts"])
        evidence = {
            path
            for exception in manifest["acceptedExceptions"]
            for paths in exception["evidence"].values()
            for path in paths
        }
        self.assertTrue(evidence <= hashed, f"unhashed example evidence: {sorted(evidence - hashed)}")


if __name__ == "__main__":
    unittest.main()
