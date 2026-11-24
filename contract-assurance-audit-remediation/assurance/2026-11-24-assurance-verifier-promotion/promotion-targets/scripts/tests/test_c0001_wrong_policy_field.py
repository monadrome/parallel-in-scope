"""Promoted regression test for contract C-0001 (candidate CONTRACT-TDD-008).

Rejects a policy using ``rawStatuses`` instead of ``allowedRawStatuses``.
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

    def test_CONTRACT_TDD_008_C0001_wrong_policy_field(self):
        policy = self._verify.mutation_policy()
        policy["EXPECTED_MUTATION_LIVENESS"].pop("allowedRawStatuses")
        policy["EXPECTED_MUTATION_LIVENESS"]["rawStatuses"] = ["TIMED_OUT"]
        run = self._verify.make_run(self._verify.exception(), policy)
        errors = _fixture.verify_run.verify(run)
        self.assertTrue(errors, "expected one or more errors, got []")


if __name__ == "__main__":
    unittest.main()
