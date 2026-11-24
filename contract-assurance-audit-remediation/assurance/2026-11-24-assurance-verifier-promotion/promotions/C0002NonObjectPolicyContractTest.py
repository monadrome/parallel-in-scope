"""Promoted regression test for contract C-0002 (candidate CONTRACT-TDD-013).

Rejects an unused non-object accepted-disposition policy.
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

    def test_CONTRACT_TDD_013_C0002_unused_policy_non_object(self):
        policy = {"EXPECTED_MUTATION_LIVENESS": "not-an-object"}
        run = self._verify.make_run(None, policy, final_gate="PASS")
        errors = _fixture.verify_run.verify(run)
        self.assertTrue(errors, "expected one or more errors, got []")


if __name__ == "__main__":
    unittest.main()
