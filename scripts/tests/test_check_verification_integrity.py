"""Tests for scripts/check-verification-integrity.py."""

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


def load_script():
    spec = importlib.util.spec_from_file_location(
        "check_verification_integrity", SCRIPTS / "check-verification-integrity.py"
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


integrity = load_script()

CODEOWNERS = """/.github/CODEOWNERS @monadrome
/.github/workflows/ @monadrome
/scripts/check-*.py @monadrome
/scripts/tests/ @monadrome
/scripts/**/test_*.py @monadrome
/verification/verification-integrity.md @monadrome
"""
JAVA_PATH = "core/src/test/java/io/example/SampleTest.java"
PYTHON_PATH = "scripts/tests/test_sample.py"
ISSUE_AGENT_PYTHON_PATH = "scripts/issue_agent/tests/test_sample.py"
TS_PATH = "tools/llm-review-bench/test/sample.test.ts"
JAVA_TEST = """class SampleTest {
    @Test
    void works() {
        assertEquals(2, value);
        assertTrue(value);
    }
}
"""
PYTHON_TEST = """import unittest

class SampleTest(unittest.TestCase):
    def test_works(self):
        self.assertEqual(2, value)
        self.assertTrue(value)
"""
TS_TEST = """test('works', () => {
  assert.equal(value, 2);
  assert.ok(value);
});
"""


class TreeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def tree(self, name, files, codeowners=CODEOWNERS):
        root = self.tmp / name
        for relative, text in files.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        if codeowners is not None:
            path = root / integrity.CODEOWNERS_PATH
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(codeowners, encoding="utf-8")
        root.mkdir(parents=True, exist_ok=True)
        return root

    def run_check(self, base, head, *extra):
        out, err = io.StringIO(), io.StringIO()
        argv = ["--base-root", str(base), "--head-root", str(head), *map(str, extra)]
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = integrity.main(argv)
        return code, out.getvalue(), err.getvalue()


class JavaIntegrityTest(TreeTest):
    def test_an_unchanged_tree_passes(self):
        base = self.tree("base", {JAVA_PATH: JAVA_TEST})
        head = self.tree("head", {JAVA_PATH: JAVA_TEST})
        code, out, err = self.run_check(base, head)
        self.assertEqual((code, err), (0, ""))
        self.assertIn("no new violations", out)

    def test_deleting_a_test_file_fails(self):
        base = self.tree("base", {JAVA_PATH: JAVA_TEST})
        head = self.tree("head", {})
        code, out, _ = self.run_check(base, head)
        self.assertEqual(code, 1)
        self.assertIn(f"{JAVA_PATH}: V1: test file with 1 test case(s) was deleted", out)

    def test_an_exact_whole_file_move_passes(self):
        moved = "core/src/test/java/io/example/MovedTest.java"
        base = self.tree("base", {JAVA_PATH: JAVA_TEST})
        head = self.tree("head", {moved: JAVA_TEST})
        self.assertEqual(self.run_check(base, head)[0], 0)

    def test_deleting_or_renaming_one_test_case_fails(self):
        two_tests = JAVA_TEST.replace(
            "\n}\n", "    @Test\n    void other() {\n        assertNotNull(value);\n    }\n}\n"
        )
        for head_text in (JAVA_TEST, two_tests.replace("void other()", "void renamed()")):
            with self.subTest(head_text=head_text):
                base = self.tree("base", {JAVA_PATH: two_tests})
                head = self.tree("head", {JAVA_PATH: head_text})
                code, out, _ = self.run_check(base, head)
                self.assertEqual(code, 1)
                self.assertIn(": V2: test case 'other' was removed or renamed (1 -> 0)", out)

    def test_assertion_count_cannot_decrease_but_equal_count_edits_pass(self):
        weakened = JAVA_TEST.replace("        assertTrue(value);\n", "")
        changed = JAVA_TEST.replace("assertTrue(value)", "assertNotNull(value)")
        for head_text, expected in ((weakened, 1), (changed, 0)):
            with self.subTest(expected=expected):
                base = self.tree("base", {JAVA_PATH: JAVA_TEST})
                head = self.tree("head", {JAVA_PATH: head_text})
                code, out, _ = self.run_check(base, head)
                self.assertEqual(code, expected)
                if expected:
                    self.assertIn(": V4: test case 'works' assertion count decreased (2 -> 1)", out)

    def test_new_disabled_annotations_fail_and_existing_ones_are_grandfathered(self):
        disabled = JAVA_TEST.replace("    @Test\n", "    @Disabled\n    @Test\n")
        base = self.tree("base", {JAVA_PATH: disabled})
        head = self.tree("head", {JAVA_PATH: disabled.replace("void works()", "@Disabled\n    void works()")})
        code, out, _ = self.run_check(base, head)
        self.assertEqual(code, 1)
        self.assertIn(": V3: new @Disabled or @Ignore test annotation", out)
        self.assertEqual(self.run_check(base, self.tree("same", {JAVA_PATH: disabled}))[0], 0)

    def test_fully_qualified_ignore_fails(self):
        base = self.tree("base", {JAVA_PATH: JAVA_TEST})
        head = self.tree("head", {JAVA_PATH: JAVA_TEST.replace("@Test", "@org.junit.Ignore\n    @Test")})
        code, out, _ = self.run_check(base, head)
        self.assertEqual(code, 1)
        self.assertIn(": V3:", out)

    def test_disabled_annotation_variants_fail(self):
        for annotation in ("@DisabledOnOs(OS.WINDOWS)", "@DisabledIfSystemProperty(named = \"x\", matches = \"y\")"):
            with self.subTest(annotation=annotation):
                base = self.tree("base", {JAVA_PATH: JAVA_TEST})
                head = self.tree("head", {JAVA_PATH: JAVA_TEST.replace("@Test", f"{annotation}\n    @Test")})
                code, out, _ = self.run_check(base, head)
                self.assertEqual(code, 1)
                self.assertIn(": V3:", out)

    def test_constant_failing_assumption_fails(self):
        base = self.tree("base", {JAVA_PATH: JAVA_TEST})
        weakened = JAVA_TEST.replace("assertTrue(value)", "assertNotNull(value); assumeTrue(false)")
        head = self.tree("head", {JAVA_PATH: weakened})
        code, out, _ = self.run_check(base, head)
        self.assertEqual(code, 1)
        self.assertIn(": V3: new constant-failing test assumption", out)
        self.assertNotIn(": V4:", out)

    def test_disable_text_in_comments_and_strings_is_ignored(self):
        decorated = JAVA_TEST.replace(
            "    @Test\n",
            "    // @Disabled\n    @Test\n",
        ).replace("assertTrue(value);", 'assertTrue(value); String text = "@Ignore";')
        base = self.tree("base", {JAVA_PATH: JAVA_TEST})
        head = self.tree("head", {JAVA_PATH: decorated})
        self.assertEqual(self.run_check(base, head)[0], 0)

    def test_new_constant_and_self_comparing_assertions_fail(self):
        for replacement in ("assertTrue(true)", "assertEquals(value, value)", "assertThat(true).isTrue()"):
            with self.subTest(replacement=replacement):
                base = self.tree("base", {JAVA_PATH: JAVA_TEST})
                head = self.tree("head", {JAVA_PATH: JAVA_TEST.replace("assertTrue(value)", replacement)})
                code, out, _ = self.run_check(base, head)
                self.assertEqual(code, 1)
                self.assertIn(": V5:", out)

    def test_existing_constant_assertions_and_different_string_literals_pass(self):
        constant = JAVA_TEST.replace("assertTrue(value)", "assertTrue(true)")
        base = self.tree("base", {JAVA_PATH: constant})
        same = self.tree("same", {JAVA_PATH: constant})
        self.assertEqual(self.run_check(base, same)[0], 0)
        different = JAVA_TEST.replace("assertEquals(2, value)", 'assertEquals("expected", "actual")')
        head = self.tree("head", {JAVA_PATH: different})
        self.assertEqual(self.run_check(self.tree("different-base", {JAVA_PATH: JAVA_TEST}), head)[0], 0)

    def test_parameterized_test_annotations_and_arguments_are_parsed(self):
        parameterized = JAVA_TEST.replace("@Test", '@ParameterizedTest(name = "{0}")\n    @MethodSource("values")')
        base = self.tree("base", {JAVA_PATH: parameterized})
        head = self.tree("head", {})
        code, out, _ = self.run_check(base, head)
        self.assertEqual(code, 1)
        self.assertIn("1 test case(s)", out)

    def test_unparseable_java_test_fails_closed(self):
        base = self.tree("base", {})
        head = self.tree("head", {JAVA_PATH: "class SampleTest {\n    @Test\n    void broken() {\n"})
        code, out, err = self.run_check(base, head)
        self.assertEqual((code, out), (2, ""))
        self.assertIn("cannot parse Java test method", err)

    def test_test_meta_annotation_is_not_a_method_parse_failure(self):
        source = "@Test\n@interface MyTest {\n}\n"
        base = self.tree("base", {JAVA_PATH: source})
        head = self.tree("head", {JAVA_PATH: source})
        self.assertEqual(self.run_check(base, head)[0], 0)

    def test_a_reviewed_exact_exception_permits_intentional_deletion(self):
        base = self.tree("base", {JAVA_PATH: JAVA_TEST})
        head = self.tree("head", {})
        exception = (JAVA_PATH, "V1", JAVA_PATH)
        with mock.patch.object(integrity, "REVIEWED_EXCEPTIONS", frozenset({exception})):
            self.assertEqual(self.run_check(base, head)[0], 0)


class PythonIntegrityTest(TreeTest):
    def test_deleting_a_python_test_file_fails(self):
        for path in (PYTHON_PATH, ISSUE_AGENT_PYTHON_PATH):
            with self.subTest(path=path):
                base = self.tree("base", {path: PYTHON_TEST})
                head = self.tree("head", {})
                code, out, _ = self.run_check(base, head)
                self.assertEqual(code, 1)
                self.assertIn(f"{path}: V1:", out)

    def test_python_skip_decorators_fail(self):
        for decorator in ("@unittest.skip('reason')", "@pytest.mark.skipif(True, reason='reason')", "@unittest.expectedFailure"):
            with self.subTest(decorator=decorator):
                base = self.tree("base", {PYTHON_PATH: PYTHON_TEST})
                head = self.tree("head", {PYTHON_PATH: PYTHON_TEST.replace("    def test_works", f"    {decorator}\n    def test_works")})
                code, out, _ = self.run_check(base, head)
                self.assertEqual(code, 1)
                self.assertIn(": V3:", out)

    def test_runtime_python_skips_fail(self):
        for call in ("self.skipTest('reason')", "pytest.skip('reason')", "pytest.importorskip('missing')"):
            with self.subTest(call=call):
                base = self.tree("base", {PYTHON_PATH: PYTHON_TEST})
                weakened = PYTHON_TEST.replace("self.assertTrue(value)", f"self.assertNotNull(value)\n        {call}")
                head = self.tree("head", {PYTHON_PATH: weakened})
                code, out, _ = self.run_check(base, head)
                self.assertEqual(code, 1)
                self.assertIn(": V3: new runtime test skip or xfail", out)
                self.assertNotIn(": V4:", out)

    def test_parametrized_pytest_skip_mark_fails(self):
        base = self.tree("base", {PYTHON_PATH: PYTHON_TEST})
        decorated = PYTHON_TEST.replace(
            "    def test_works",
            "    @pytest.mark.parametrize('value', [1], marks=pytest.mark.skip('reason'))\n    def test_works",
        )
        head = self.tree("head", {PYTHON_PATH: decorated})
        code, out, _ = self.run_check(base, head)
        self.assertEqual(code, 1)
        self.assertIn(": V3: new runtime test skip or xfail", out)

    def test_python_assertion_reduction_and_noops_fail(self):
        weakened = PYTHON_TEST.replace("        self.assertTrue(value)\n", "")
        noop = PYTHON_TEST.replace("self.assertTrue(value)", "self.assertTrue(True)")
        equal = PYTHON_TEST.replace("self.assertTrue(value)", "self.assertEqual(value, value)")
        for head_text, rule in ((weakened, "V4"), (noop, "V5"), (equal, "V5")):
            with self.subTest(rule=rule, head_text=head_text):
                base = self.tree("base", {PYTHON_PATH: PYTHON_TEST})
                head = self.tree("head", {PYTHON_PATH: head_text})
                code, out, _ = self.run_check(base, head)
                self.assertEqual(code, 1)
                self.assertIn(f": {rule}:", out)

    def test_bare_assert_true_fails(self):
        source = "def test_works():\n    assert value\n"
        base = self.tree("base", {PYTHON_PATH: source})
        head = self.tree("head", {PYTHON_PATH: source.replace("assert value", "assert True")})
        code, out, _ = self.run_check(base, head)
        self.assertEqual(code, 1)
        self.assertIn(": V5:", out)

    def test_invalid_python_exits_2(self):
        base = self.tree("base", {})
        head = self.tree("head", {PYTHON_PATH: "def broken(:\n"})
        code, out, err = self.run_check(base, head)
        self.assertEqual((code, out), (2, ""))
        self.assertIn("cannot parse", err)


class TypeScriptIntegrityTest(TreeTest):
    def test_deleting_a_typescript_test_fails(self):
        base = self.tree("base", {TS_PATH: TS_TEST})
        head = self.tree("head", {})
        code, out, _ = self.run_check(base, head)
        self.assertEqual(code, 1)
        self.assertIn(f"{TS_PATH}: V1:", out)

    def test_typescript_skip_and_only_fail_without_reporting_case_deletion(self):
        for selection in ("test.skip", "test.only"):
            with self.subTest(selection=selection):
                base = self.tree("base", {TS_PATH: TS_TEST})
                head = self.tree("head", {TS_PATH: TS_TEST.replace("test(", f"{selection}(")})
                code, out, _ = self.run_check(base, head)
                self.assertEqual(code, 1)
                self.assertIn(": V3:", out)
                self.assertNotIn(": V2:", out)

    def test_typescript_regex_literals_keep_call_delimiters(self):
        source = TS_TEST.replace(
            "assert.ok(value)",
            r"assert.match(value, /^refs\/llm\/(?:done)\//); assert.notOk(value)",
        )
        parsed = integrity.scan_typescript(TS_PATH, source, "digest")
        self.assertEqual([(test.name, test.assertions) for test in parsed.tests], [("works", 3)])

    def test_typescript_options_suppression_fails(self):
        base = self.tree("base", {TS_PATH: TS_TEST})
        suppressed = TS_TEST.replace("test('works', () =>", "test('works', { skip: true }, () =>")
        head = self.tree("head", {TS_PATH: suppressed})
        code, out, _ = self.run_check(base, head)
        self.assertEqual(code, 1)
        self.assertIn(": V3: new test options suppression", out)
        self.assertNotIn(": V4:", out)

    def test_unparseable_typescript_test_fails_closed(self):
        base = self.tree("base", {})
        head = self.tree("head", {TS_PATH: "test('broken', () => {\n"})
        code, out, err = self.run_check(base, head)
        self.assertEqual((code, out), (2, ""))
        self.assertIn("cannot parse TypeScript test", err)

    def test_runtime_typescript_skip_fails(self):
        base = self.tree("base", {TS_PATH: TS_TEST})
        weakened = TS_TEST.replace("assert.ok(value)", "assert.notOk(value);\n  t.skip()")
        head = self.tree("head", {TS_PATH: weakened})
        code, out, _ = self.run_check(base, head)
        self.assertEqual(code, 1)
        self.assertIn(": V3: new runtime test skip, todo, or exclusive selection", out)
        self.assertNotIn(": V4:", out)

    def test_typescript_assertion_reduction_and_noops_fail(self):
        weakened = TS_TEST.replace("  assert.ok(value);\n", "")
        noop = TS_TEST.replace("assert.ok(value)", "assert.ok(true)")
        equal = TS_TEST.replace("assert.ok(value)", "assert.strictEqual(value, value)")
        expect = TS_TEST.replace("assert.ok(value)", "expect(true).toBe(true)")
        for head_text, rule in ((weakened, "V4"), (noop, "V5"), (equal, "V5"), (expect, "V5")):
            with self.subTest(rule=rule, head_text=head_text):
                base = self.tree("base", {TS_PATH: TS_TEST})
                head = self.tree("head", {TS_PATH: head_text})
                code, out, _ = self.run_check(base, head)
                self.assertEqual(code, 1)
                self.assertIn(f": {rule}:", out)


class InfrastructureTest(TreeTest):
    def test_protected_add_modify_delete_are_reported_without_failing(self):
        script = "scripts/check-example.py"
        workflow = ".github/workflows/example.yml"
        base = self.tree("base", {script: "BASE = True\n", workflow: "name: old\n"})
        head = self.tree("head", {script: "BASE = False\n", "scripts/check-new.py": "NEW = True\n"})
        code, out, err = self.run_check(base, head)
        self.assertEqual((code, err), (0, ""))
        self.assertIn(f"PROTECTED {script}: modified; CODEOWNERS review required", out)
        self.assertIn(f"PROTECTED {workflow}: deleted; CODEOWNERS review required", out)
        self.assertIn("PROTECTED scripts/check-new.py: added; CODEOWNERS review required", out)

    def test_missing_or_weakened_codeowners_entries_fail(self):
        base = self.tree("base", {})
        for codeowners in (None, CODEOWNERS.replace("/.github/workflows/ @monadrome\n", ""), CODEOWNERS.replace("/scripts/check-*.py @monadrome", "/scripts/check-*.py @someone-else")):
            with self.subTest(codeowners=codeowners):
                head = self.tree("head", {}, codeowners=codeowners)
                code, out, _ = self.run_check(base, head)
                self.assertEqual(code, 1)
                self.assertIn(".github/CODEOWNERS: V6:", out)

    def test_later_codeowners_overrides_cannot_remove_repository_owner(self):
        workflow = ".github/workflows/ci.yml"
        for override in ("/.github/workflows/ci.yml @someone-else\n", "/.github/workflows/ci.yml\n"):
            with self.subTest(override=override):
                base = self.tree("base", {workflow: "name: CI\n"})
                head = self.tree("head", {workflow: "name: CI\n"}, codeowners=CODEOWNERS + override)
                code, out, _ = self.run_check(base, head)
                self.assertEqual(code, 1)
                self.assertIn("effective CODEOWNERS coverage", out)

    def test_later_codeowners_override_can_retain_repository_owner(self):
        workflow = ".github/workflows/ci.yml"
        base = self.tree("base", {workflow: "name: CI\n"})
        head = self.tree(
            "head",
            {workflow: "name: CI\n"},
            codeowners=CODEOWNERS + "/.github/workflows/ci.yml @monadrome\n",
        )
        self.assertEqual(self.run_check(base, head)[0], 0)

    def test_codeowners_at_github_size_limit_fails(self):
        base = self.tree("base", {})
        head = self.tree("head", {}, codeowners=CODEOWNERS + " " * 3_000_000)
        code, out, _ = self.run_check(base, head)
        self.assertEqual(code, 1)
        self.assertIn("3 MB loading limit", out)

    def test_codeowners_violations_cannot_use_reviewed_exceptions(self):
        base = self.tree("base", {})
        head = self.tree("head", {}, codeowners=None)
        exception = (integrity.CODEOWNERS_PATH, "V6", integrity.CODEOWNERS_PATH)
        with mock.patch.object(integrity, "REVIEWED_EXCEPTIONS", frozenset({exception})):
            code, out, _ = self.run_check(base, head)
        self.assertEqual(code, 1)
        self.assertIn(": V6:", out)

    def test_missing_roots_exit_2(self):
        root = self.tree("root", {})
        for base, head in ((self.tmp / "missing", root), (root, self.tmp / "missing")):
            code, out, err = self.run_check(base, head)
            self.assertEqual((code, out), (2, ""))
            self.assertIn("is not a directory", err)

    def test_an_option_shaped_base_is_rejected(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            integrity.parse_args(["--base=--output=/tmp/x"])
        self.assertEqual(raised.exception.code, 2)


class RevisionTest(TreeTest):
    def setUp(self):
        super().setUp()
        self.repo = self.tree("repo", {JAVA_PATH: JAVA_TEST})
        self.git("init", "-q")
        for key, value in (("user.email", "t@example.com"), ("user.name", "t"), ("commit.gpgsign", "false")):
            self.git("config", key, value)
        self.git("add", "-A")
        self.git("commit", "-qm", "base")
        self.base = self.git("rev-parse", "HEAD")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.repo, check=True, capture_output=True, text=True).stdout.strip()

    def test_the_base_revision_is_extracted_with_git(self):
        with mock.patch.object(integrity, "ROOT", self.repo):
            self.assertEqual(self.run_git_check()[0], 0)
            path = self.repo / JAVA_PATH
            path.write_text(JAVA_TEST.replace("@Test", "@Disabled\n    @Test"), encoding="utf-8")
            code, out, _ = self.run_git_check()
        self.assertEqual(code, 1)
        self.assertIn(": V3:", out)

    def run_git_check(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = integrity.main(["--base", self.base, "--head-root", str(self.repo)])
        return code, out.getvalue(), err.getvalue()

    def test_an_unknown_revision_exits_2(self):
        with mock.patch.object(integrity, "ROOT", self.repo):
            out, err = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = integrity.main(["--base", "no-such-revision", "--head-root", str(self.repo)])
        self.assertEqual((code, out.getvalue()), (2, ""))
        self.assertIn("could not run", err.getvalue())


if __name__ == "__main__":
    unittest.main()
