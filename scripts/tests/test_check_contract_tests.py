import importlib.util
import tempfile
import unittest
import xml.etree.ElementTree as ET
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

    def write_test(self, name, contract_id, tag=None, package="example"):
        tag = contract_id if tag is None else tag
        package_declaration = "" if package is None else f"package {package};\n"
        (self.root / name).write_text(
            f'{package_declaration}import org.junit.jupiter.api.Tag;\n@Tag("C-{tag}")\nclass C{contract_id}SubjectContractTest {{\n@Test\nvoid contract() {{}}\n}}\n',
            encoding="utf-8",
        )

    def write_reports(self, testcases):
        reports = self.root / "surefire-reports"
        reports.mkdir(exist_ok=True)
        suite = ET.Element("testsuite")
        for class_name, method_name, outcome in testcases:
            testcase = ET.SubElement(
                suite,
                "testcase",
                classname=f"example.{class_name}",
                name=method_name,
            )
            if outcome is not None:
                ET.SubElement(testcase, outcome)
        ET.ElementTree(suite).write(reports / "TEST-contracts.xml", encoding="utf-8", xml_declaration=True)
        return reports

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

    def test_metadata_does_not_depend_on_lexical_test_annotations(self):
        (self.root / "C0001SubjectContractTest.java").write_text(
            'import org.junit.jupiter.api.Tag;\n@Tag("C-0001")\nclass C0001SubjectContractTest {}\n',
            encoding="utf-8",
        )
        self.assertEqual(check_contract_tests.verify(self.root), [])

    def test_execution_check_accepts_a_passing_testcase(self):
        self.write_test("C0001SubjectContractTest.java", "0001")
        reports = self.write_reports([("C0001SubjectContractTest", "contract", None)])
        self.assertEqual(check_contract_tests.verify(self.root, reports), [])

    def test_execution_check_rejects_the_same_simple_name_in_another_package(self):
        self.write_test("C0001SubjectContractTest.java", "0001", package="expected")
        reports = self.write_reports([("C0001SubjectContractTest", "contract", None)])
        errors = check_contract_tests.verify(self.root, reports)
        self.assertTrue(any("no executed testcase for expected.C0001SubjectContractTest" in error for error in errors))

    def test_execution_check_rejects_an_empty_method_name(self):
        self.write_test("C0001SubjectContractTest.java", "0001")
        reports = self.write_reports([("C0001SubjectContractTest", "", None)])
        errors = check_contract_tests.verify(self.root, reports)
        self.assertTrue(any("testcase method name must be non-empty" in error for error in errors))

    def test_execution_check_rejects_a_commented_test_annotation_without_execution(self):
        (self.root / "C0001SubjectContractTest.java").write_text(
            'import org.junit.jupiter.api.Tag;\n@Tag("C-0001")\nclass C0001SubjectContractTest {\n// @Test\n}\n',
            encoding="utf-8",
        )
        reports = self.write_reports([("C0002OtherContractTest", "contract", None)])
        errors = check_contract_tests.verify(self.root, reports)
        self.assertTrue(any("no executed testcase" in error for error in errors))

    def test_execution_check_rejects_skipped_failed_and_error_testcases(self):
        for outcome in ("skipped", "failure", "error"):
            with self.subTest(outcome=outcome):
                self.write_test("C0001SubjectContractTest.java", "0001")
                reports = self.write_reports([("C0001SubjectContractTest", "contract", outcome)])
                errors = check_contract_tests.verify(self.root, reports)
                self.assertTrue(any(f"testcase contract has {outcome}" in error for error in errors))

    def test_execution_check_rejects_a_missing_report(self):
        self.write_test("C0001SubjectContractTest.java", "0001")
        errors = check_contract_tests.verify(self.root, self.root / "missing")
        self.assertTrue(any("no Surefire XML reports" in error for error in errors))

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
