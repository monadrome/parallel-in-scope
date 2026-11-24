"""Promoted regression test for contract C-0003 (candidate CONTRACT-TDD-015).

Rejects an absolute frozen-input path.
"""
import unittest

try:
    from tests import test_verify_run as _fixture
except ImportError:
    import test_verify_run as _fixture


class ContractVerifierTest(unittest.TestCase):
    def setUp(self):
        self._verify = _fixture.VerifyRunTest()
        self.addCleanup(self._verify.doCleanups)

    def test_CONTRACT_TDD_015_C0003_absolute_input_path(self):
        run = self._verify.make_run(None, {}, final_gate="PASS")
        self._verify.update_manifest(run, inputs={"/inputs/abs.md": "0" * 64})
        errors = _fixture.verify_run.verify(run)
        self.assertTrue(errors, "expected one or more errors, got []")


if __name__ == "__main__":
    unittest.main()
