"""Tests for scripts/check-java-conventions.py.

The fixture trees under fixtures/java_conventions/ are source roots: `clean` holds every
exemption the rules grant and no violation, `violations` holds violations of every rule.
Tests that need a base and a head pass both as --base-root/--head-root, so git is not used
except by the revision test, which builds a scratch repository.
"""

import contextlib
import importlib.util
import io
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
FIXTURES = Path(__file__).resolve().parent / "fixtures" / "java_conventions"


def load_script():
    spec = importlib.util.spec_from_file_location("check_java_conventions", SCRIPTS / "check-java-conventions.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


conventions = load_script()

HEADER = "package io.example;\n\nimport static com.google.common.base.Preconditions.checkArgument;\n\n"


def java_class(body):
    return HEADER + "public class Sample {\n" + body + "}\n"


def violations_in(text, path="io/example/Sample.java"):
    source = conventions.mask(text)
    return [v for check in conventions.CHECKS for v in check(path, source)]


def rules_in(text):
    return sorted(v.rule for v in violations_in(text))


def message_call(message):
    return java_class(f"    void run(int x) {{\n        checkArgument(x > 0, {message}, x);\n    }}\n")


class TreeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def tree(self, name, files):
        root = self.tmp / name
        for relative, text in files.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        root.mkdir(parents=True, exist_ok=True)
        return root

    def run_check(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = conventions.main([str(arg) for arg in argv])
        return code, out.getvalue(), err.getvalue()


class RuleTest(unittest.TestCase):
    def test_the_clean_fixture_has_no_violations(self):
        self.assertEqual(conventions.scan(FIXTURES / "clean"), [])

    def test_the_violations_fixture_trips_every_rule(self):
        found = conventions.scan(FIXTURES / "violations")
        by_rule = {}
        for violation in found:
            by_rule.setdefault(violation.rule, []).append(violation.message)
        self.assertEqual(sorted(by_rule), ["R1", "R2", "R3", "R4"])
        self.assertEqual(sorted(m.split("()")[0].split()[-1] for m in by_rule["R1"]),
                         ["getName", "getValue", "isSignaling"])
        self.assertEqual(len(by_rule["R2"]), 5, by_rule["R2"])
        self.assertEqual(sorted(m.split("'")[1] for m in by_rule["R3"]), ["Signaling", "canceled"])
        self.assertEqual(len(by_rule["R4"]), 1)

    def test_r1_flags_public_and_protected_zero_argument_accessors(self):
        for declaration in ("public String getName() {", "protected boolean isOpen() {",
                            "public <T> java.util.List<T> getItems() {", "public final @Deprecated int getCount() {"):
            with self.subTest(declaration):
                text = java_class(f"    {declaration}\n        return null;\n    }}\n")
                self.assertEqual(rules_in(text), ["R1"])

    def test_r1_exempts_override_other_visibility_parameters_and_bare_names(self):
        for declaration in ("@Override\n    public boolean isShutdown() {", "boolean isOpen() {",
                            "private String getName() {", "public boolean isPresent(String key) {",
                            "public String name() {", "public String getter() {"):
            with self.subTest(declaration):
                text = java_class(f"    {declaration}\n        return false;\n    }}\n")
                self.assertEqual(rules_in(text), [])

    def test_r1_reads_past_an_annotation_with_a_named_element(self):
        text = java_class('    @SuppressWarnings(value = "unchecked")\n'
                          '    public String getName() {\n        return "";\n    }\n')
        self.assertEqual(rules_in(text), ["R1"])

    def test_r1_ignores_calls_and_counts_implicitly_public_interface_methods(self):
        calls = java_class("    private final Object v = getValue();\n"
                           "    void run() {\n        Object x = getValue();\n        getValue();\n    }\n")
        self.assertEqual(rules_in(calls), [])
        self.assertEqual(rules_in(HEADER + "public interface Source {\n    String getName();\n}\n"), ["R1"])

    def test_r2_flags_a_trailing_period_and_an_uppercase_start(self):
        self.assertEqual(rules_in(message_call('"x must be positive, got %s."')), ["R2"])
        self.assertEqual(rules_in(message_call('"X must be positive, got %s"')), ["R2"])
        self.assertEqual(rules_in(message_call('"Invalid value %s"')), ["R2"])

    def test_r2_exempts_a_leading_code_identifier(self):
        for message in ('"ParRuntime is closed"', '"Par \'%s\' does not belong to it"',
                        '"TaskGroup.start was called twice for %s"', '"Par_2 is gone"'):
            with self.subTest(message):
                self.assertEqual(rules_in(message_call(message)), [])

    def test_r2_accepts_only_percent_s(self):
        self.assertEqual(rules_in(message_call('"x must be positive, got %s"')), [])
        for message in ('"x is %d"', '"x is %1$s"', '"x is %.2f"', '"50%% of %s"', '"50% of %s"'):
            with self.subTest(message):
                self.assertEqual(rules_in(message_call(message)), ["R2"])

    def test_r2_reads_qualified_and_multiline_calls_and_skips_non_literals(self):
        qualified = java_class('    void run(Object v) {\n        com.google.common.base.Verify.verifyNotNull(\n'
                               '                v,\n                "Value missing.");\n    }\n')
        self.assertEqual(len([v for v in violations_in(qualified) if v.rule == "R2"]), 2)
        self.assertEqual(rules_in(message_call("MESSAGE")), [])
        self.assertEqual(rules_in(message_call('"x " + MESSAGE')), [])
        self.assertEqual(rules_in(message_call('"x " + "is positive."')), ["R2"])
        other = java_class('    void run(Mock m) {\n        m.verify(m, "Not a precondition.");\n    }\n')
        self.assertEqual(rules_in(other), [])

    def test_r2_handles_a_generic_comma_before_the_message(self):
        text = java_class('    void run(Object v) {\n'
                          '        checkArgument(v instanceof java.util.Map<String, Integer>, "Bad value");\n'
                          '    }\n')
        self.assertEqual(rules_in(text), ["R2"])

    def test_r2_reports_the_message_line_not_the_preceding_argument(self):
        text = java_class('    void run(boolean ok) {\n        checkArgument(ok,\n'
                          '            "Bad value");\n    }\n')
        found = [violation for violation in violations_in(text) if violation.rule == "R2"]
        self.assertEqual([violation.line for violation in found], [8])

    def test_r3_flags_single_l_forms_in_any_case_and_keeps_doubled_l_forms(self):
        for word in ("canceled", "Canceling", "CANCELED", "onCanceled", "cancelable", "signaling", "labeled",
                     "cancelers"):
            with self.subTest(word):
                self.assertEqual(rules_in(java_class(f"    // {word}\n")), ["R3"])
        good = "cancelled cancelling canceller cancellable signalling labelled isCancelled CANCELLED"
        self.assertEqual(rules_in(java_class(f"    // {good}\n")), [])

    def test_r4_flags_require_non_null_in_code_only(self):
        self.assertEqual(rules_in(java_class("    Object v(Object o) {\n"
                                             "        return java.util.Objects.requireNonNull(o);\n    }\n")), ["R4"])
        self.assertEqual(rules_in(java_class('    /** Not Objects.requireNonNull. */\n    String s = "requireNonNull";\n')), [])


class BaselineTest(TreeTest):
    LEGACY = java_class("    public Object getLegacy() {\n        return java.util.Objects.requireNonNull(null);\n    }\n")

    def test_a_clean_tree_passes(self):
        code, out, err = self.run_check("--base-root", FIXTURES / "clean", "--head-root", FIXTURES / "clean")
        self.assertEqual((code, err), (0, ""))
        self.assertIn("no new violations", out)

    def test_new_violations_fail_with_path_line_and_rule(self):
        code, out, _ = self.run_check("--base-root", FIXTURES / "clean", "--head-root", FIXTURES / "violations")
        self.assertEqual(code, 1)
        self.assertIn("src/main/java/io/example/Violations.java:11: R4: ", out)
        self.assertIn("src/main/java/io/example/Violations.java:21: R1: accessor getValue()", out)

    def test_an_unchanged_violation_passes_even_when_its_lines_shift(self):
        base = self.tree("base", {"io/example/Sample.java": self.LEGACY})
        shifted = self.LEGACY.replace("public class Sample {\n", "public class Sample {\n\n    int added;\n\n")
        head = self.tree("head", {"io/example/Sample.java": shifted})
        code, _, err = self.run_check("--base-root", base, "--head-root", head)
        self.assertEqual((code, err), (0, ""))

    def test_a_duplicate_of_an_existing_violation_fails(self):
        base = self.tree("base", {"io/example/Sample.java": self.LEGACY})
        doubled = self.LEGACY.replace(
            "    }\n}", "    }\n\n    Object again() {\n        return java.util.Objects.requireNonNull(null);\n    }\n}")
        head = self.tree("head", {"io/example/Sample.java": doubled})
        code, out, _ = self.run_check("--base-root", base, "--head-root", head)
        self.assertEqual(code, 1)
        self.assertEqual(out.count(": R4: "), 2)
        self.assertNotIn(": R1: ", out)

    def test_moving_a_violation_to_another_file_counts_as_new(self):
        base = self.tree("base", {"io/example/Sample.java": self.LEGACY})
        head = self.tree("head", {"io/example/Moved.java": self.LEGACY})
        code, _, _ = self.run_check("--base-root", base, "--head-root", head)
        self.assertEqual(code, 1)

    def test_the_queue_package_is_out_of_scope(self):
        base = self.tree("base", {})
        head = self.tree("head", {"io/github/monadrome/parallelinscope/queue/Q.java": self.LEGACY})
        self.assertEqual(self.run_check("--base-root", base, "--head-root", head)[0], 0)

    def test_a_missing_base_or_head_root_exits_2(self):
        missing = self.tmp / "missing"
        for argv in (("--base-root", missing, "--head-root", FIXTURES / "clean"),
                     ("--base-root", FIXTURES / "clean", "--head-root", missing)):
            code, out, err = self.run_check(*argv)
            self.assertEqual((code, out), (2, ""))
            self.assertIn("is not a directory", err)

    def test_an_unreadable_source_exits_2(self):
        base = self.tree("base", {})
        (base / "Bad.java").write_bytes(b"\xff\xfe broken")
        code, _, err = self.run_check("--base-root", base, "--head-root", FIXTURES / "clean")
        self.assertEqual(code, 2)
        self.assertIn("cannot read", err)

    def test_an_option_shaped_base_is_rejected(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            conventions.parse_args(["--base=--output=/tmp/x"])
        self.assertEqual(raised.exception.code, 2)


class RevisionTest(TreeTest):
    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.repo, check=True, capture_output=True, text=True).stdout.strip()

    def setUp(self):
        super().setUp()
        self.repo = self.tmp / "repo"
        self.repo.mkdir()
        self.git("init", "-q")
        for key, value in (("user.email", "t@example.com"), ("user.name", "t"), ("commit.gpgsign", "false")):
            self.git("config", key, value)
        source = self.repo / "src/main/java/io/example/Sample.java"
        source.parent.mkdir(parents=True)
        source.write_text(BaselineTest.LEGACY, encoding="utf-8")
        self.git("add", "-A")
        self.git("commit", "-qm", "base")
        self.base = self.git("rev-parse", "HEAD")

    def test_the_base_revision_is_extracted_with_git(self):
        head = self.repo / "src/main/java"
        with mock.patch.object(conventions, "ROOT", self.repo):
            self.assertEqual(self.run_check("--base", self.base, "--head-root", head)[0], 0)
            (head / "io/example/New.java").write_text(java_class("    // canceled\n"), encoding="utf-8")
            code, out, _ = self.run_check("--base", self.base, "--head-root", head)
        self.assertEqual(code, 1)
        self.assertIn("New.java:6: R3: ", out)

    def test_an_unknown_revision_exits_2(self):
        with mock.patch.object(conventions, "ROOT", self.repo):
            code, _, err = self.run_check("--base", "no-such-revision", "--head-root", self.repo / "src/main/java")
        self.assertEqual(code, 2)
        self.assertIn("could not run", err)


if __name__ == "__main__":
    unittest.main()
