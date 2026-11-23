import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-contract-tests.py"
spec = importlib.util.spec_from_file_location("check_contract_tests", SCRIPT)
check_contract_tests = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_contract_tests)


class CheckContractTestsTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)

    def write_test(self, name, contract_id, tag=None):
        tag = contract_id if tag is None else tag
        (self.root / name).write_text(
            f'import org.junit.jupiter.api.Tag;\n@Tag("C-{tag}")\nclass C{contract_id}SubjectContractTest {{\n@Test\nvoid contract() {{}}\n}}\n',
            encoding="utf-8",
        )

    def test_accepts_matching_unique_contract_ids(self):
        self.write_test("C0001SubjectContractTest.java", "0001")
        self.write_test("C0002SubjectContractTest.java", "0002")
        self.assertEqual(check_contract_tests.verify(self.root), [])

    def test_rejects_mismatched_tag(self):
        self.write_test("C0001SubjectContractTest.java", "0001", tag="0002")
        errors = check_contract_tests.verify(self.root)
        self.assertTrue(any("immediately above the class" in error for error in errors))
        self.assertTrue(any("exactly one contract tag C-0001" in error for error in errors))

    def test_rejects_missing_tag(self):
        (self.root / "C0001SubjectContractTest.java").write_text(
            "class C0001SubjectContractTest {\n@Test\nvoid contract() {}\n}\n",
            encoding="utf-8",
        )
        errors = check_contract_tests.verify(self.root)
        self.assertTrue(any("immediately above the class" in error for error in errors))

    def test_rejects_a_class_without_tests(self):
        (self.root / "C0001SubjectContractTest.java").write_text(
            'import org.junit.jupiter.api.Tag;\n@Tag("C-0001")\nclass C0001SubjectContractTest {}\n',
            encoding="utf-8",
        )
        errors = check_contract_tests.verify(self.root)
        self.assertTrue(any("at least one @Test method" in error for error in errors))

    def test_rejects_duplicate_contract_ids(self):
        self.write_test("C0001SubjectContractTest.java", "0001")
        self.write_test("Other.java", "0001")
        errors = check_contract_tests.verify(self.root)
        self.assertTrue(any("already used" in error for error in errors))

    def test_rejects_an_empty_suite(self):
        errors = check_contract_tests.verify(self.root)
        self.assertTrue(any("no contract regression tests" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
