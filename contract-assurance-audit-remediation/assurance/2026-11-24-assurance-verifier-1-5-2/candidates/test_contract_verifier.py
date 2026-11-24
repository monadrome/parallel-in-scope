"""Sealed unittest candidates for the assurance run verifier 1.5.2 contract.

These tests build a self-contained valid temporary baseline fixture per C-1 and
then mutate one boundary at a time. The verifier under test is loaded at run
time from the VERIFY_RUN_PATH environment variable; the real source is never
read during generation.
"""
import hashlib
import importlib.util
import json
import os
import shutil
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
REQUIRED_DIRS = ("inputs", "logs", "candidates", "promotions", "reverse-verification")
MANIFEST_NAME = "run-manifest.json"

EVIDENCE_CATEGORIES = (
    "repeatable-focused-rerun",
    "unmutated-tests-pass",
    "complete-xml-and-logs",
    "source-level-liveness-invariant",
    "maintainer-or-independent-review",
)

_VERIFIER = None


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _write_bytes(root, rel, data):
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return path


def _write_manifest(root, manifest):
    _write_bytes(root, MANIFEST_NAME, json.dumps(manifest, indent=2).encode("utf-8"))


def _mkrun(root, accepted=False):
    """Create a valid temporary baseline run; return the manifest dict."""
    for directory in REQUIRED_DIRS:
        (root / directory).mkdir(parents=True, exist_ok=True)

    if accepted:
        report = b"# report\nFinal gate: PASS WITH ACCEPTED EXCEPTIONS\n"
    else:
        report = b"# report\nFinal gate: PASS\n"

    files = {
        "contract.md": b"# contract\n",
        "contract-gaps.md": b"# gaps\n",
        "candidate-status.csv": b"candidate_id\n",
        "promotion-status.csv": b"candidate_id,source_contract_id,contract_id,target_path,archive_path,status,reason\n",
        "verification-summary.md": b"# summary\n",
        "report.md": report,
    }
    artifacts = {}
    for name, data in files.items():
        _write_bytes(root, name, data)
        artifacts[name] = _sha(data)

    sample = b"input sample\n"
    _write_bytes(root, "inputs/sample.txt", sample)
    inputs = {"inputs/sample.txt": _sha(sample)}

    adapter = {"requiredGates": ["candidate-tests"]}
    accepted_exceptions = []
    if accepted:
        evidence = {}
        for category in EVIDENCE_CATEGORIES:
            data = ("evidence %s\n" % category).encode("utf-8")
            rel = "inputs/%s.md" % category
            _write_bytes(root, rel, data)
            inputs[rel] = _sha(data)
            evidence[category] = [rel]
        adapter["acceptedDispositions"] = {
            "EXPECTED_MUTATION_LIVENESS": {
                "allowedRawStatuses": ["TIMED_OUT"],
                "requiredEvidence": list(EVIDENCE_CATEGORIES),
            }
        }
        accepted_exceptions = [
            {
                "phase": "candidate-tests",
                "rawStatus": "TIMED_OUT",
                "disposition": "EXPECTED_MUTATION_LIVENESS",
                "accepted": True,
                "evidence": evidence,
                "approvedBy": "reviewer",
            }
        ]

    head = "2" * 40
    base = "1" * 40
    manifest = {
        "schemaVersion": "contract-assurance-run/v2",
        "runId": "run-0001",
        "createdAt": "2026-11-24T00:00:00Z",
        "package": {},
        "adapter": adapter,
        "workspace": {"base": base, "head": head, "dirty": False},
        "inputs": inputs,
        "commands": [],
        "artifacts": artifacts,
        "status": "pass",
        "requiredGates": ["candidate-tests"],
        "checks": {"candidate-tests": "pass"},
        "acceptedExceptions": accepted_exceptions,
        "finalHead": head,
    }
    _write_manifest(root, manifest)
    return manifest


def _verifier():
    global _VERIFIER
    if _VERIFIER is not None:
        return _VERIFIER
    path = os.environ.get("VERIFY_RUN_PATH")
    if not path:
        raise unittest.SkipTest("VERIFY_RUN_PATH is not set; cannot load the verifier")
    module_path = Path(path)
    if module_path.is_dir():
        module_path = module_path / "verify_run.py"
    spec = importlib.util.spec_from_file_location("verify_run", str(module_path))
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load verifier from %r" % (path,))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    _VERIFIER = module
    return module


def _verify(run_dir):
    return _verifier().verify(run_dir)


class ContractVerifierTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.run = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def assertNoErrors(self, errors):
        self.assertEqual([], errors)

    def assertHasErrors(self, errors):
        self.assertTrue(errors, "expected one or more errors, got []")

    def test_CONTRACT_TDD_001_valid_no_exception_baseline(self):
        _mkrun(self.run)
        self.assertNoErrors(_verify(self.run))

    def test_CONTRACT_TDD_002_valid_accepted_exception_baseline(self):
        _mkrun(self.run, accepted=True)
        self.assertNoErrors(_verify(self.run))

    def test_CONTRACT_TDD_003_missing_required_evidence_category(self):
        manifest = _mkrun(self.run, accepted=True)
        del manifest["acceptedExceptions"][0]["evidence"][EVIDENCE_CATEGORIES[-1]]
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_004_missing_evidence_file(self):
        manifest = _mkrun(self.run, accepted=True)
        manifest["acceptedExceptions"][0]["evidence"][EVIDENCE_CATEGORIES[0]] = [
            "inputs/missing-evidence.md"
        ]
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_005_unhashed_evidence_path(self):
        manifest = _mkrun(self.run, accepted=True)
        rel = "inputs/unhashed-evidence.md"
        _write_bytes(self.run, rel, b"unhashed evidence\n")
        manifest["acceptedExceptions"][0]["evidence"][EVIDENCE_CATEGORIES[0]] = [rel]
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_006_disallowed_raw_status(self):
        manifest = _mkrun(self.run, accepted=True)
        manifest["acceptedExceptions"][0]["rawStatus"] = "KILLED"
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_007_disallowed_disposition(self):
        manifest = _mkrun(self.run, accepted=True)
        manifest["acceptedExceptions"][0]["disposition"] = "NOT_A_POLICY"
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_008_wrong_policy_field_name(self):
        manifest = _mkrun(self.run, accepted=True)
        manifest["adapter"]["acceptedDispositions"] = {
            "EXPECTED_MUTATION_LIVENESS": {
                "rawStatuses": ["TIMED_OUT"],
                "requiredEvidence": list(EVIDENCE_CATEGORIES),
            }
        }
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_009_unused_policy_empty_allowed_raw_statuses(self):
        manifest = _mkrun(self.run)
        manifest["adapter"]["acceptedDispositions"] = {
            "EXPECTED_MUTATION_LIVENESS": {
                "allowedRawStatuses": [],
                "requiredEvidence": list(EVIDENCE_CATEGORIES),
            }
        }
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_010_unused_policy_empty_required_evidence(self):
        manifest = _mkrun(self.run)
        manifest["adapter"]["acceptedDispositions"] = {
            "EXPECTED_MUTATION_LIVENESS": {
                "allowedRawStatuses": ["TIMED_OUT"],
                "requiredEvidence": [],
            }
        }
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_011_unused_policy_blank_value(self):
        manifest = _mkrun(self.run)
        manifest["adapter"]["acceptedDispositions"] = {
            "EXPECTED_MUTATION_LIVENESS": {
                "allowedRawStatuses": ["TIMED_OUT", "   "],
                "requiredEvidence": list(EVIDENCE_CATEGORIES),
            }
        }
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_012_unused_policy_duplicate_value(self):
        manifest = _mkrun(self.run)
        manifest["adapter"]["acceptedDispositions"] = {
            "EXPECTED_MUTATION_LIVENESS": {
                "allowedRawStatuses": ["TIMED_OUT"],
                "requiredEvidence": [
                    "repeatable-focused-rerun",
                    "repeatable-focused-rerun",
                ],
            }
        }
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_013_unused_policy_non_object(self):
        manifest = _mkrun(self.run)
        manifest["adapter"]["acceptedDispositions"] = {
            "EXPECTED_MUTATION_LIVENESS": "not-an-object"
        }
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_014_empty_inputs(self):
        manifest = _mkrun(self.run)
        manifest["inputs"] = {}
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_015_absolute_input_path(self):
        manifest = _mkrun(self.run)
        manifest["inputs"] = {"/inputs/abs.md": "0" * 64}
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_016_non_canonical_input_path(self):
        manifest = _mkrun(self.run)
        manifest["inputs"] = {"inputs/../contract.md": _sha(b"# contract\n")}
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_017_root_output_alias_input(self):
        manifest = _mkrun(self.run)
        manifest["inputs"] = {"contract.md": _sha(b"# contract\n")}
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_018_output_symlink_input(self):
        manifest = _mkrun(self.run)
        (self.run / "inputs" / "output-alias.md").symlink_to(Path("..") / "contract.md")
        manifest["inputs"]["inputs/output-alias.md"] = _sha(b"# contract\n")
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_019_symlinked_inputs_root(self):
        manifest = _mkrun(self.run)
        inputs_dir = self.run / "inputs"
        shutil.rmtree(inputs_dir)
        inputs_dir.symlink_to(self.run, target_is_directory=True)
        manifest["inputs"] = {"inputs/contract.md": _sha(b"# contract\n")}
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_020_input_hash_mismatch(self):
        manifest = _mkrun(self.run)
        manifest["inputs"]["inputs/sample.txt"] = "0" * 64
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_021_nested_symlink_input(self):
        manifest = _mkrun(self.run)
        (self.run / "inputs" / "nested.md").symlink_to(Path("mid.md"))
        (self.run / "inputs" / "mid.md").symlink_to(Path("..") / "contract.md")
        manifest["inputs"]["inputs/nested.md"] = _sha(b"# contract\n")
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_022_sha256_head_accepted(self):
        manifest = _mkrun(self.run)
        manifest["workspace"]["base"] = "a" * 64
        manifest["workspace"]["head"] = "b" * 64
        manifest["finalHead"] = "b" * 64
        _write_manifest(self.run, manifest)
        self.assertNoErrors(_verify(self.run))

    def test_CONTRACT_TDD_023_symbolic_ref_head(self):
        manifest = _mkrun(self.run)
        manifest["workspace"]["head"] = "HEAD"
        manifest["finalHead"] = "HEAD"
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_024_short_id_head(self):
        manifest = _mkrun(self.run)
        manifest["workspace"]["head"] = "a" * 39
        manifest["finalHead"] = "a" * 39
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_025_uppercase_id_head(self):
        manifest = _mkrun(self.run)
        manifest["workspace"]["head"] = "B" * 40
        manifest["finalHead"] = "B" * 40
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_026_missing_workspace_base(self):
        manifest = _mkrun(self.run)
        del manifest["workspace"]["base"]
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_027_other_length_head(self):
        manifest = _mkrun(self.run)
        manifest["workspace"]["head"] = "a" * 41
        manifest["finalHead"] = "a" * 41
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_028_mismatched_final_head(self):
        manifest = _mkrun(self.run)
        manifest["finalHead"] = "3" * 40
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))

    def test_CONTRACT_TDD_029_missing_workspace_dirty(self):
        manifest = _mkrun(self.run)
        del manifest["workspace"]["dirty"]
        _write_manifest(self.run, manifest)
        self.assertHasErrors(_verify(self.run))


if __name__ == "__main__":
    unittest.main()
