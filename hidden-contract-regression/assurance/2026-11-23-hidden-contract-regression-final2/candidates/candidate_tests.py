"""Sealed isolated candidates for C-102 and C-103.

The candidates create only temporary Java and Surefire XML fixtures, then invoke the
sanitized public checker command supplied through ASSURANCE_REPO.
"""

from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


class ContractTestCheckerCandidates(unittest.TestCase):
    """Contract-test checker candidates constructed without repository inspection."""

    def _run_checker(
        self, test_root: Path, reports: Path | None = None
    ) -> subprocess.CompletedProcess[str]:
        repository = os.environ.get("ASSURANCE_REPO")
        if not repository:
            self.fail("ASSURANCE_REPO must identify the verification checkout")

        command = [
            "python3",
            str(Path(repository) / "scripts" / "check-contract-tests.py"),
            "--test-root",
            str(test_root),
        ]
        if reports is not None:
            command.extend(("--reports", str(reports)))
        return subprocess.run(command, capture_output=True, check=False, text=True)

    @staticmethod
    def _diagnostics(completed: subprocess.CompletedProcess[str]) -> str:
        return f"stdout:\n{completed.stdout}\nstderr:\n{completed.stderr}"

    def _assert_metadata_rejected(
        self, completed: subprocess.CompletedProcess[str]
    ) -> None:
        self.assertEqual(1, completed.returncode, self._diagnostics(completed))
        self.assertIn(
            "ERROR:", completed.stdout + completed.stderr, self._diagnostics(completed)
        )

    def _assert_execution_rejected(
        self, completed: subprocess.CompletedProcess[str]
    ) -> None:
        self.assertNotEqual(0, completed.returncode, self._diagnostics(completed))

    @staticmethod
    def _write_source_text(root: Path, relative_path: str, source: str) -> None:
        path = root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")

    def _write_contract_source(
        self,
        root: Path,
        package: str,
        class_name: str,
        contract_id: str,
        *,
        extra_imports: tuple[str, ...] = (),
        body: str = "",
        helper_class: bool = False,
    ) -> None:
        package_directory = root / Path(*package.split(".")) if package else root
        package_directory.mkdir(parents=True, exist_ok=True)

        lines: list[str] = []
        if package:
            lines.append(f"package {package};")
        lines.extend(("import org.junit.jupiter.api.Tag;", *extra_imports))
        lines.extend((f'@Tag("{contract_id}")', f"class {class_name} {{"))
        if body:
            lines.append(body)
        lines.append("}")
        if helper_class:
            lines.extend(("", "class NonContractHelper {}"))

        (package_directory / f"{class_name}.java").write_text(
            "\n".join(lines) + "\n", encoding="utf-8"
        )

    @staticmethod
    def _report_xml(
        testcases: tuple[tuple[str, str, str | None], ...]
    ) -> str:
        lines = ["<testsuite>"]
        for classname, name, outcome in testcases:
            if outcome is None:
                lines.append(f'  <testcase classname="{classname}" name="{name}" />')
            else:
                lines.extend(
                    (
                        f'  <testcase classname="{classname}" name="{name}">',
                        f"    <{outcome}/>",
                        "  </testcase>",
                    )
                )
        lines.append("</testsuite>")
        return "\n".join(lines) + "\n"

    @staticmethod
    def _write_report(reports: Path, filename: str, xml: str) -> None:
        reports.mkdir(parents=True, exist_ok=True)
        (reports / filename).write_text(xml, encoding="utf-8")

    def test_contract_tdd_001_metadata_accepts_one_matching_class(self) -> None:
        """CONTRACT-TDD-001
        Preconditions: A temporary test root contains one correctly named contract
        class, its immediately preceding unique tag, and a non-contract helper.
        Operation: Invoke the public checker with --test-root only.
        Observation point: The checker process return code.
        Assertion: Valid C-102 metadata exits with code 0 and permits the helper.
        Uncertainty: This candidate does not infer whether the checker parses Java
        beyond the metadata requirements in the frozen contract.
        """
        with tempfile.TemporaryDirectory() as temporary_directory:
            test_root = Path(temporary_directory) / "test-root"
            self._write_contract_source(
                test_root,
                "metadata.valid",
                "C0101MetadataContractTest",
                "C-0101",
                helper_class=True,
            )

            completed = self._run_checker(test_root)

        self.assertEqual(0, completed.returncode, self._diagnostics(completed))

    def test_contract_tdd_002_metadata_rejects_invalid_or_empty_suites(self) -> None:
        """CONTRACT-TDD-002
        Preconditions: Each temporary root is either empty or has malformed C-102
        metadata: a displaced tag, a mismatched filename, repeated tags, duplicate
        contract IDs, or more than one contract class in one Java source.
        Operation: Invoke the public checker with --test-root only for each root.
        Observation point: The checker return code and diagnostic stream.
        Assertion: Each invalid suite exits 1 and includes an ERROR: diagnostic.
        Uncertainty: The candidate checks rejection, not the checker-specific wording
        or ordering of individual diagnostics.
        """
        cases = (
            ("empty-suite", ()),
            (
                "tag-not-immediately-above-class",
                (
                    (
                        "metadata/C0102PlacementContractTest.java",
                        "package metadata;\n"
                        "import org.junit.jupiter.api.Tag;\n"
                        '@Tag("C-0102")\n'
                        "@Deprecated\n"
                        "class C0102PlacementContractTest {\n"
                        "}\n",
                    ),
                ),
            ),
            (
                "filename-mismatch",
                (
                    (
                        "metadata/NotTheContractClass.java",
                        "package metadata;\n"
                        "import org.junit.jupiter.api.Tag;\n"
                        '@Tag("C-0103")\n'
                        "class C0103FilenameContractTest {\n"
                        "}\n",
                    ),
                ),
            ),
            (
                "repeated-tag",
                (
                    (
                        "metadata/C0104RepeatedTagContractTest.java",
                        "package metadata;\n"
                        "import org.junit.jupiter.api.Tag;\n"
                        '@Tag("C-0104")\n'
                        '@Tag("C-0104")\n'
                        "class C0104RepeatedTagContractTest {\n"
                        "}\n",
                    ),
                ),
            ),
            (
                "duplicate-contract-id",
                (
                    (
                        "metadata/C0105FirstContractTest.java",
                        "package metadata;\n"
                        "import org.junit.jupiter.api.Tag;\n"
                        '@Tag("C-0105")\n'
                        "class C0105FirstContractTest {\n"
                        "}\n",
                    ),
                    (
                        "metadata/C0105SecondContractTest.java",
                        "package metadata;\n"
                        "import org.junit.jupiter.api.Tag;\n"
                        '@Tag("C-0105")\n'
                        "class C0105SecondContractTest {\n"
                        "}\n",
                    ),
                ),
            ),
            (
                "multiple-contract-classes-in-one-source",
                (
                    (
                        "metadata/C0106FirstContractTest.java",
                        "package metadata;\n"
                        "import org.junit.jupiter.api.Tag;\n"
                        '@Tag("C-0106")\n'
                        "class C0106FirstContractTest {\n"
                        "}\n"
                        '@Tag("C-0107")\n'
                        "class C0107SecondContractTest {\n"
                        "}\n",
                    ),
                ),
            ),
        )

        for case_name, sources in cases:
            with self.subTest(case=case_name), tempfile.TemporaryDirectory() as temporary_directory:
                test_root = Path(temporary_directory) / "test-root"
                test_root.mkdir()
                for relative_path, source in sources:
                    self._write_source_text(test_root, relative_path, source)

                self._assert_metadata_rejected(self._run_checker(test_root))

    def test_contract_tdd_003_execution_accepts_canonical_exact_fqcn_evidence(self) -> None:
        """CONTRACT-TDD-003
        Preconditions: One valid packaged contract class has a canonical class-specific
        XML report containing one testcase with its exact FQCN and a non-empty name.
        Operation: Invoke the public checker with --test-root and --reports.
        Observation point: The checker process return code.
        Assertion: The complete valid C-103 evidence set exits with code 0.
        Uncertainty: The fixture models the sanitized Surefire XML shape only and
        makes no provenance claim about an arbitrary caller-supplied report.
        """
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            test_root = temporary_path / "test-root"
            reports = temporary_path / "reports"
            package = "execution.exact"
            class_name = "C0201ExactEvidenceContractTest"
            fqcn = f"{package}.{class_name}"
            self._write_contract_source(test_root, package, class_name, "C-0201")
            self._write_report(
                reports,
                f"TEST-{fqcn}.xml",
                self._report_xml(((fqcn, "contract", None),)),
            )

            completed = self._run_checker(test_root, reports)

        self.assertEqual(0, completed.returncode, self._diagnostics(completed))

    def test_contract_tdd_004_execution_rejects_same_name_from_another_package(self) -> None:
        """CONTRACT-TDD-004
        Preconditions: A canonical report filename belongs to one packaged contract
        class, but its testcase uses the same simple class name in another package.
        Operation: Invoke the public checker with --test-root and --reports.
        Observation point: The checker process return code.
        Assertion: Cross-package evidence fails because testcase classname must equal
        the declared source class's exact FQCN.
        Uncertainty: This candidate does not require a particular failure diagnostic.
        """
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            test_root = temporary_path / "test-root"
            reports = temporary_path / "reports"
            package = "execution.source"
            class_name = "C0202CrossPackageContractTest"
            fqcn = f"{package}.{class_name}"
            self._write_contract_source(test_root, package, class_name, "C-0202")
            self._write_report(
                reports,
                f"TEST-{fqcn}.xml",
                self._report_xml(
                    ((f"execution.other.{class_name}", "contract", None),)
                ),
            )

            completed = self._run_checker(test_root, reports)

        self._assert_execution_rejected(completed)

    def test_contract_tdd_005_execution_rejects_aggregate_report(self) -> None:
        """CONTRACT-TDD-005
        Preconditions: A valid contract class has exact-FQCN testcase evidence only
        in an aggregate-style report whose filename is not TEST-<FQCN>.xml.
        Operation: Invoke the public checker with --test-root and --reports.
        Observation point: The checker process return code.
        Assertion: Aggregate or differently named reports cannot satisfy C-103.
        Uncertainty: The candidate verifies filename-based rejection and does not
        assume a particular aggregate-report schema beyond a testcase element.
        """
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            test_root = temporary_path / "test-root"
            reports = temporary_path / "reports"
            package = "execution.aggregate"
            class_name = "C0203AggregateContractTest"
            fqcn = f"{package}.{class_name}"
            self._write_contract_source(test_root, package, class_name, "C-0203")
            self._write_report(
                reports,
                "TEST-aggregate.xml",
                self._report_xml(((fqcn, "contract", None),)),
            )

            completed = self._run_checker(test_root, reports)

        self._assert_execution_rejected(completed)

    def test_contract_tdd_006_execution_rejects_empty_testcase_names(self) -> None:
        """CONTRACT-TDD-006
        Preconditions: A canonical report contains one valid matching testcase and a
        second matching testcase whose name attribute is empty.
        Operation: Invoke the public checker with --test-root and --reports.
        Observation point: The checker process return code.
        Assertion: C-103 fails because every exact-FQCN testcase needs a non-empty
        name, even when another matching testcase is valid.
        Uncertainty: The candidate does not constrain whitespace-only name handling,
        which the frozen contract does not define.
        """
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            test_root = temporary_path / "test-root"
            reports = temporary_path / "reports"
            package = "execution.emptyname"
            class_name = "C0204EmptyNameContractTest"
            fqcn = f"{package}.{class_name}"
            self._write_contract_source(test_root, package, class_name, "C-0204")
            self._write_report(
                reports,
                f"TEST-{fqcn}.xml",
                self._report_xml(((fqcn, "passing", None), (fqcn, "", None))),
            )

            completed = self._run_checker(test_root, reports)

        self._assert_execution_rejected(completed)

    def test_contract_tdd_007_execution_rejects_missing_report_despite_lexical_test(self) -> None:
        """CONTRACT-TDD-007
        Preconditions: A valid source includes a lexical @Test method, while its
        existing reports directory contains no canonical XML report.
        Operation: Run metadata-only checking, then run the public checker with
        --reports against the same temporary source fixture.
        Observation point: The metadata and execution checker return codes.
        Assertion: Metadata passes, but execution evidence fails; a lexical @Test is
        not proof of execution and a missing report cannot pass C-103.
        Uncertainty: This candidate checks only the public checker behavior, not the
        provenance of a report produced outside the prescribed clean run.
        """
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            test_root = temporary_path / "test-root"
            reports = temporary_path / "reports"
            reports.mkdir()
            self._write_contract_source(
                test_root,
                "execution.missing",
                "C0205MissingReportContractTest",
                "C-0205",
                extra_imports=("import org.junit.jupiter.api.Test;",),
                body="  @Test\n  void lexicalTest() {}",
            )

            metadata_completed = self._run_checker(test_root)
            execution_completed = self._run_checker(test_root, reports)

        self.assertEqual(
            0, metadata_completed.returncode, self._diagnostics(metadata_completed)
        )
        self._assert_execution_rejected(execution_completed)

    def test_contract_tdd_008_execution_rejects_malformed_canonical_report(self) -> None:
        """CONTRACT-TDD-008
        Preconditions: A valid contract class has its required canonical report file,
        but that XML file is malformed.
        Operation: Invoke the public checker with --test-root and --reports.
        Observation point: The checker process return code.
        Assertion: Malformed XML fails rather than silently passing C-103.
        Uncertainty: This candidate does not prescribe parser implementation details
        or the exact parse-error diagnostic.
        """
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            test_root = temporary_path / "test-root"
            reports = temporary_path / "reports"
            package = "execution.malformed"
            class_name = "C0206MalformedReportContractTest"
            fqcn = f"{package}.{class_name}"
            self._write_contract_source(test_root, package, class_name, "C-0206")
            self._write_report(
                reports,
                f"TEST-{fqcn}.xml",
                '<testsuite><testcase classname="broken" name="contract"></testsuite>\n',
            )

            completed = self._run_checker(test_root, reports)

        self._assert_execution_rejected(completed)

    def test_contract_tdd_009_execution_rejects_direct_nonpassing_outcomes(self) -> None:
        """CONTRACT-TDD-009
        Preconditions: Each canonical report has a passing exact-FQCN testcase and
        another matching testcase with a direct skipped, failure, or error child.
        Operation: Invoke the public checker with --test-root and --reports for each
        outcome in independent temporary fixtures.
        Observation point: The checker process return code.
        Assertion: Every direct nonpassing outcome rejects C-103, even when a passing
        testcase is also present.
        Uncertainty: The candidate is limited to the three direct child outcome names
        stated by the frozen contract.
        """
        for outcome in ("skipped", "failure", "error"):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as temporary_directory:
                temporary_path = Path(temporary_directory)
                test_root = temporary_path / "test-root"
                reports = temporary_path / "reports"
                package = "execution.outcome"
                class_name = "C0207OutcomeContractTest"
                fqcn = f"{package}.{class_name}"
                self._write_contract_source(test_root, package, class_name, "C-0207")
                self._write_report(
                    reports,
                    f"TEST-{fqcn}.xml",
                    self._report_xml(
                        ((fqcn, "passing", None), (fqcn, outcome, outcome))
                    ),
                )

                self._assert_execution_rejected(self._run_checker(test_root, reports))


# SEALED: generated from contract.md, api.md, and commands.md only.
