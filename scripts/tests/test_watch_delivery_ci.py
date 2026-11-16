"""Tests for scripts/watch-delivery-ci.py.

All gh calls are faked; no test touches the network, git, or the real repository.
"""

import importlib.util
import io
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / "watch-delivery-ci.py"


def load_script():
    spec = importlib.util.spec_from_file_location("watch_delivery_ci", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def runs_payload(*runs):
    return {
        "workflow_runs": [
            {
                "id": rid,
                "name": name,
                "status": status,
                "conclusion": conclusion,
                "head_sha": "a" * 40,
            }
            for rid, name, status, conclusion in runs
        ]
    }


COMPLETED_SUCCESS = runs_payload(
    (1, "CI", "completed", "success"),
    (2, "CodeQL", "completed", "success"),
    (3, "Snapshot", "completed", "success"),
)


class CollectRunsTest(unittest.TestCase):
    def test_maps_payload_fields(self):
        mod = load_script()
        runs = mod.collect_runs(COMPLETED_SUCCESS)
        self.assertEqual([1, 2, 3], [run.database_id for run in runs])
        self.assertEqual("CI", runs[0].name)
        self.assertEqual("completed", runs[0].status)
        self.assertEqual("success", runs[0].conclusion)

    def test_missing_conclusion_maps_to_empty(self):
        mod = load_script()
        runs = mod.collect_runs(runs_payload((1, "CI", "in_progress", None)))
        self.assertEqual("", runs[0].conclusion)


class DecideTest(unittest.TestCase):
    def test_all_success_is_zero(self):
        mod = load_script()
        code, _ = mod.decide(mod.collect_runs(COMPLETED_SUCCESS))
        self.assertEqual(0, code)

    def test_any_non_success_is_one_and_named(self):
        mod = load_script()
        payload = runs_payload(
            (1, "CI", "completed", "failure"), (2, "CodeQL", "completed", "success")
        )
        code, lines = mod.decide(mod.collect_runs(payload))
        self.assertEqual(1, code)
        self.assertIn("CI: failure", lines)

    def test_skipped_and_neutral_count_as_passed(self):
        mod = load_script()
        payload = runs_payload(
            (1, "CI", "completed", "success"), (2, "docs", "completed", "skipped")
        )
        code, _ = mod.decide(mod.collect_runs(payload))
        self.assertEqual(0, code)

    def test_cancelled_counts_as_failed(self):
        mod = load_script()
        payload = runs_payload((1, "CI", "completed", "cancelled"))
        code, _ = mod.decide(mod.collect_runs(payload))
        self.assertEqual(1, code)


class WaitForRunsTest(unittest.TestCase):
    def test_retries_until_runs_materialize(self):
        mod = load_script()
        responses = iter([{"workflow_runs": []}, {"workflow_runs": []}, COMPLETED_SUCCESS])
        gh = lambda args: next(responses)
        runs = mod.wait_for_runs(gh, "o/r", "a" * 40, lambda *_: None)
        self.assertEqual(3, len(runs))

    def test_empty_after_grace_returns_empty(self):
        mod = load_script()
        gh = lambda args: {"workflow_runs": []}
        self.assertEqual([], mod.wait_for_runs(gh, "o/r", "a" * 40, lambda *_: None))


class BranchTipTest(unittest.TestCase):
    def test_reads_commit_sha(self):
        mod = load_script()
        gh = lambda args: {"commit": {"sha": "b" * 40}}
        self.assertEqual("b" * 40, mod.branch_tip(gh, "o/r", "dev/v0.5.0"))

    def test_missing_commit_returns_none(self):
        mod = load_script()
        gh = lambda args: {}
        self.assertIsNone(mod.branch_tip(gh, "o/r", "dev/v0.5.0"))


class FailedJobNamesTest(unittest.TestCase):
    def test_lists_only_unsuccessful_jobs(self):
        mod = load_script()
        payload = {
            "jobs": [
                {"name": "build (25)", "conclusion": "success"},
                {"name": "runtime-consumer (17)", "conclusion": "failure"},
                {"name": "docs", "conclusion": "skipped"},
            ]
        }
        gh = lambda args: payload
        self.assertEqual(
            ["runtime-consumer (17)"], mod.failed_job_names(gh, "o/r", 1)
        )


class ResolveRepoTest(unittest.TestCase):
    def test_reads_name_with_owner_from_json_object(self):
        mod = load_script()
        gh = lambda args: {"nameWithOwner": "monadrome/parallel-in-scope"}
        self.assertEqual("monadrome/parallel-in-scope", mod.resolve_repo(gh))

    def test_missing_key_raises_gh_error(self):
        mod = load_script()
        with self.assertRaises(mod.GhError):
            mod.resolve_repo(lambda args: {})


class MainTest(unittest.TestCase):
    SHA = "a" * 40

    def run_main(self, mod, gh):
        buffer = io.StringIO()
        with mock.patch.object(mod, "resolve_sha", return_value=self.SHA), mock.patch.object(
            mod, "resolve_repo", return_value="o/r"
        ), redirect_stdout(buffer):
            code = mod.main([self.SHA, "--interval-seconds", "0"], gh=gh, sleep=lambda *_: None)
        return code, buffer.getvalue()

    def test_green_delivery(self):
        mod = load_script()
        gh = lambda args: COMPLETED_SUCCESS
        code, output = self.run_main(mod, gh)
        self.assertEqual(0, code)
        self.assertIn("delivery checks green", output)

    def test_failed_delivery_lists_failed_jobs(self):
        mod = load_script()
        failed = runs_payload((1, "CI", "completed", "failure"))
        jobs = {"jobs": [{"name": "runtime-consumer (17)", "conclusion": "failure"}]}
        gh = lambda args: jobs if "/jobs" in args[1] else failed
        code, output = self.run_main(mod, gh)
        self.assertEqual(1, code)
        self.assertIn("runtime-consumer (17)", output)

    def test_no_runs_is_exit_three(self):
        mod = load_script()
        gh = lambda args: {"workflow_runs": []}
        code, output = self.run_main(mod, gh)
        self.assertEqual(3, code)
        self.assertIn("no workflow run", output)

    def test_newer_delivery_hint_is_printed(self):
        mod = load_script()

        def gh(args):
            if "/branches/" in args[1]:
                return {"commit": {"sha": "b" * 40}}
            return COMPLETED_SUCCESS

        code, output = self.run_main(mod, gh)
        self.assertEqual(0, code)
        self.assertIn("has moved to bbbbbbb", output)

    def test_no_hint_when_branch_still_at_our_sha(self):
        mod = load_script()

        def gh(args):
            if "/branches/" in args[1]:
                return {"commit": {"sha": self.SHA}}
            return COMPLETED_SUCCESS

        code, output = self.run_main(mod, gh)
        self.assertEqual(0, code)
        self.assertNotIn("has moved", output)

    def test_timeout_is_exit_two(self):
        mod = load_script()
        in_progress = runs_payload((1, "CI", "in_progress", None))
        gh = lambda args: in_progress
        buffer = io.StringIO()
        with mock.patch.object(mod, "resolve_sha", return_value=self.SHA), mock.patch.object(
            mod, "resolve_repo", return_value="o/r"
        ), redirect_stdout(buffer):
            code = mod.main(
                [self.SHA, "--timeout-minutes", "0"], gh=gh, sleep=lambda *_: None
            )
        self.assertEqual(2, code)
        self.assertIn("timed out", buffer.getvalue())


if __name__ == "__main__":
    unittest.main()
