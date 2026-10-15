import subprocess
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

from issue_agent import agents, landing, pipeline, schemas, triage
from issue_agent.github import GitHub
from issue_agent.issues import Issue
from issue_agent.proc import CommandError


class MergeTest(unittest.TestCase):
    def run_with(self, pr_state):
        failure = CommandError(["gh", "pr", "merge"], subprocess.CompletedProcess([], 1, "", "already merged"))
        gh = types.SimpleNamespace(merge_pr=mock.Mock(side_effect=failure), pr_state=mock.Mock(return_value=pr_state),
                                   pr_base=mock.Mock(return_value="dev/v0.3.0"))
        return types.SimpleNamespace(gh=gh, pr=12, pushed_sha="abc", issue=types.SimpleNamespace(number=7),
                                     config=types.SimpleNamespace(base_branch="dev/v0.3.0"),
                                     report={"title": "fix: x y z", "summary": "s", "completes_issue": True})

    def test_a_concurrent_maintainer_merge_counts_as_merged(self):
        self.assertEqual(landing.merge(self.run_with(("MERGED", "feedbeef"))), "feedbeef")

    def test_other_merge_failures_propagate(self):
        with self.assertRaises(CommandError):
            landing.merge(self.run_with(("OPEN", "")))

    def test_a_retargeted_pr_is_never_merged(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = types.SimpleNamespace(repo="o/r", base_branch="dev/v0.3.0", state_dir=Path(tmp), gh_bin="gh")
            gh = GitHub(config)
            gh._gh = mock.Mock(return_value=subprocess.CompletedProcess([], 0, "main\n", ""))
            with self.assertRaises(RuntimeError):
                gh.merge_pr(12, "abc", "fix: x (#12)", "body")
            self.assertEqual(gh._gh.call_count, 1)

    def test_a_retarget_during_the_merge_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = types.SimpleNamespace(repo="o/r", base_branch="dev/v0.3.0", state_dir=Path(tmp), gh_bin="gh")
            gh = GitHub(config)
            replies = ["dev/v0.3.0\n", "", "main\n"]
            gh._gh = mock.Mock(side_effect=[subprocess.CompletedProcess([], 0, out, "") for out in replies])
            with self.assertRaises(RuntimeError):
                gh.merge_pr(12, "abc", "fix: x (#12)", "body")


class HoldTest(unittest.TestCase):
    def test_a_held_change_becomes_a_local_patch_and_a_decision(self):
        from issue_agent import gates

        run = types.SimpleNamespace(config=types.SimpleNamespace(gate_rounds=2), directory=Path("/tmp/none"), clone=Path("/x"),
                                    base_sha="b", run_id="r1", gates=[gates.Gate("hold", "hold", "workflow changes: .github/workflows/ci.yml")])
        with mock.patch.object(pipeline, "check_gates", return_value=[]), \
                mock.patch.object(pipeline.workspace, "export_patch", return_value=Path("/tmp/none/held.patch")) as export:
            outcome = pipeline.local_gates(run)
        self.assertEqual(outcome.state, "needs-decision")
        export.assert_called_once()
        self.assertIn("held.patch", outcome.reason)

    def test_without_a_hold_the_gates_pass_through(self):
        run = types.SimpleNamespace(config=types.SimpleNamespace(gate_rounds=2), gates=[])
        with mock.patch.object(pipeline, "check_gates", return_value=[]):
            self.assertIsNone(pipeline.local_gates(run))


class ClaimGuardTest(unittest.TestCase):
    def test_merge_is_skipped_when_the_claim_was_lost(self):
        gh = types.SimpleNamespace(holds_claim=mock.Mock(return_value=False), merge_pr=mock.Mock())
        run = types.SimpleNamespace(gh=gh, human_paths=[], unresolved=[], gates=[], config=types.SimpleNamespace(base_branch="dev/v0.3.0"),
                                    clone=Path("/x"), base_sha="b", pushed_sha="p", deadline=0.0,
                                    issue=types.SimpleNamespace(number=7), claim_sha="c")
        with mock.patch.object(landing.workspace, "rebase_onto_base", return_value="b"), mock.patch.object(landing, "head", return_value="p"):
            outcome = landing.land(run)
        self.assertEqual(outcome.state, "blocked")
        gh.merge_pr.assert_not_called()

    def test_merge_with_an_unconfirmable_base_needs_a_decision(self):
        failure = CommandError(["gh"], subprocess.CompletedProcess([], 1, "", "x"))
        gh = types.SimpleNamespace(merge_pr=mock.Mock(side_effect=failure), pr_state=mock.Mock(return_value=("MERGED", "feedbeef")),
                                   pr_base=mock.Mock(side_effect=failure))
        run = types.SimpleNamespace(gh=gh, pr=12, pushed_sha="abc", issue=types.SimpleNamespace(number=7),
                                    config=types.SimpleNamespace(base_branch="dev/v0.3.0"),
                                    report={"title": "fix: x y z", "summary": "s", "completes_issue": True})
        self.assertEqual(landing.merge(run).state, "needs-decision")

    def test_release_only_deletes_a_claim_that_is_still_ours(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = types.SimpleNamespace(repo="o/r", base_branch="dev/v0.3.0", state_dir=Path(tmp), gh_bin="gh")
            gh = GitHub(config)
            gh._gh = mock.Mock(return_value=subprocess.CompletedProcess([], 0, "someone-elses-commit\n", ""))
            self.assertFalse(gh.release_claim(7, "our-commit"))
            self.assertEqual(gh._gh.call_count, 1)
            gh._gh = mock.Mock(return_value=subprocess.CompletedProcess([], 0, "our-commit\n", ""))
            self.assertTrue(gh.release_claim(7, "our-commit"))
            self.assertEqual(gh._gh.call_count, 2)

    def test_claim_reads_tell_an_absent_claim_from_an_unreadable_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = types.SimpleNamespace(repo="o/r", base_branch="dev/v0.3.0", state_dir=Path(tmp), gh_bin="gh")
            gh = GitHub(config)
            gh._gh = mock.Mock(return_value=subprocess.CompletedProcess([], 1, "", "gh: Not Found (HTTP 404)"))
            self.assertEqual(gh.claim_sha(7), "")
            self.assertFalse(gh.holds_claim(7, "x"))
            gh._gh = mock.Mock(return_value=subprocess.CompletedProcess([], 1, "", "HTTP 502: Bad Gateway"))
            with self.assertRaises(CommandError):
                gh.holds_claim(7, "x")

    def test_an_overdue_run_stops(self):
        self.assertIsNone(pipeline.overdue(types.SimpleNamespace(deadline=0.0)))
        self.assertEqual(pipeline.overdue(types.SimpleNamespace(deadline=1.0)).state, "blocked")


class BudgetTest(unittest.TestCase):
    def run_with(self, spent, total=10.0):
        return types.SimpleNamespace(spent=spent, config=types.SimpleNamespace(run_budget_usd=total))

    def test_calls_are_clamped_to_the_remaining_run_budget(self):
        self.assertEqual(pipeline.call_budget(self.run_with(4.0), 100.0), 6.0)
        self.assertEqual(pipeline.call_budget(self.run_with(0.0), 3.0), 3.0)
        self.assertIsNone(pipeline.over_budget(self.run_with(8.5), 100.0))
        self.assertIsNotNone(pipeline.over_budget(self.run_with(9.5), 100.0))
        self.assertIsNotNone(pipeline.over_budget(self.run_with(12.0), 100.0))


class BudgetCallSiteTest(unittest.TestCase):
    def run_for(self, spent):
        config = types.SimpleNamespace(run_budget_usd=10.0, implement_budget_usd=100.0, fix_budget_usd=40.0,
                                       implement_timeout_min=1, fix_timeout_min=1, review_timeout_min=1,
                                       java_version="25", base_branch="dev/v0.3.0")
        issue = Issue(7, "t", "b", "m", "OWNER", False, [], [])
        gh = types.SimpleNamespace(login="m")
        return pipeline.Run(config, gh, issue, "r1", Path("/tmp/none"), "auto/issue-7-t-r1", "/jdk", "other", spent=spent)

    def budget_passed(self, step, spent):
        result = agents.AgentResult(True, {"status": "done", "title": "fix: a b c"}, 0.0, "s")
        with mock.patch.object(pipeline.agents, "claude", return_value=result) as claude:
            stop = step(self.run_for(spent))
        return (claude.call_args[0][5] if claude.called else None), stop

    def test_implement_and_fix_are_capped_by_the_remaining_run_budget(self):
        self.assertEqual(self.budget_passed(pipeline.implement, 4.0)[0], 6.0)
        self.assertEqual(self.budget_passed(lambda run: pipeline.fix(run, "CI", "d"), 7.5)[0], 2.5)

    def test_no_call_starts_with_less_than_a_dollar_left(self):
        budget, stop = self.budget_passed(pipeline.implement, 9.5)
        self.assertIsNone(budget)
        self.assertEqual(stop.state, "blocked")


class ReviewRetryTest(unittest.TestCase):
    def test_an_unparseable_review_is_retried_once_with_a_strict_instruction(self):
        run = BudgetCallSiteTest().run_for(0.0)
        run.report = {"summary": "s", "contract_items": []}
        replies = [agents.AgentResult(False, {}, error="prose"),
                   agents.AgentResult(True, {"verdict": "clean", "findings": [], "notes": ""})]
        with mock.patch.object(landing, "git", return_value="1 file changed"), \
                mock.patch.object(landing.agents, "codex_review", side_effect=replies) as review:
            verdict = landing.independent_review(run)
        self.assertEqual(verdict["verdict"], "clean")
        self.assertEqual(review.call_count, 2)
        self.assertIn("could not be parsed", review.call_args_list[1][0][1])

    def test_two_unparseable_reviews_are_no_verdict(self):
        run = BudgetCallSiteTest().run_for(0.0)
        run.report = {"summary": "s", "contract_items": []}
        with mock.patch.object(landing, "git", return_value="x"), \
                mock.patch.object(landing.agents, "codex_review", return_value=agents.AgentResult(False, {}, error="prose")):
            self.assertIsNone(landing.independent_review(run))


class TriageTickTest(unittest.TestCase):
    def test_tick_budget_stops_triage_and_maintainer_issues_go_first(self):
        external = Issue(3, "t", "b", "x", "NONE", False, [], [])
        trusted = Issue(9, "t", "b", "m", "OWNER", False, [], [])
        later = Issue(10, "t", "b", "m", "OWNER", False, [], [])
        gh = types.SimpleNamespace(open_issues=lambda: [external, trusted, later])
        config = types.SimpleNamespace(triage_tick_budget_usd=5.0, triage_budget_usd=4.0)
        seen = []

        def fake(config, gh, issue, base, dry_run, force, budget):
            seen.append((issue.number, budget))
            return "ok", 4.5

        with mock.patch.object(triage, "triage_issue", side_effect=fake):
            spent = triage.triage_all(config, gh, Path("."), dry_run=True)
        self.assertEqual((seen, spent), ([(9, 4.0)], 4.5))


class ReviewParsingTest(unittest.TestCase):
    def test_fenced_or_prefixed_json_is_recovered(self):
        self.assertEqual(agents.parse_json_object('```json\n{"verdict": "clean"}\n```'), {"verdict": "clean"})
        self.assertEqual(agents.parse_json_object('note: {"a": {"b": 1}} end'), {"a": {"b": 1}})
        self.assertIsNone(agents.parse_json_object("no json here"))
        self.assertIsNone(agents.parse_json_object("[1, 2]"))

    def test_unknown_severities_and_malformed_findings_block(self):
        review = schemas.normalize_review({"verdict": "clean", "findings": [{"severity": "critical", "summary": "x"}, "loose"]})
        self.assertEqual([f["severity"] for f in review["findings"]], ["major", "major"])
        self.assertEqual(len(schemas.blocking_findings(review)), 2)
        self.assertIsNone(schemas.normalize_review({"verdict": "lgtm"}))
        self.assertIsNone(schemas.normalize_review(["not", "a", "dict"]))

    def test_a_verdict_without_a_findings_list_is_no_verdict(self):
        self.assertIsNone(schemas.normalize_review({"verdict": "clean"}))
        self.assertIsNone(schemas.normalize_review({"verdict": "clean", "findings": None}))
        self.assertEqual(schemas.normalize_review({"verdict": "clean", "findings": []})["findings"], [])


if __name__ == "__main__":
    unittest.main()
