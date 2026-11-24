"""Promoted regression test for contract C-0004 (candidate CONTRACT-TDD-021).

Rejects a nested input symlink chain resolving to a root output.
"""
import hashlib
import unittest
from pathlib import Path

try:
    from tests import test_verify_run as _fixture
except ImportError:
    import test_verify_run as _fixture


class ContractVerifierTest(unittest.TestCase):
    def setUp(self):
        self._verify = _fixture.VerifyRunTest()
        self.addCleanup(self._verify.doCleanups)

    def test_CONTRACT_TDD_021_C0004_nested_input_symlink(self):
        run = self._verify.make_run(None, {}, final_gate="PASS")
        alias = run / "inputs" / "nested.md"
        alias.symlink_to(Path("mid.md"))
        (run / "inputs" / "mid.md").symlink_to(Path("..") / "contract.md")
        digest = hashlib.sha256(alias.read_bytes()).hexdigest()
        self._verify.update_manifest(run, inputs={"inputs/nested.md": digest})
        errors = _fixture.verify_run.verify(run)
        self.assertTrue(errors, "expected one or more errors, got []")


if __name__ == "__main__":
    unittest.main()
