import datetime
import json
import subprocess
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

from issue_agent import labels, outcomes, pipeline, work
from issue_agent.github import parse_claim
from issue_agent.issues import Comment, Issue, render_marker
from issue_agent.proc import CommandError

RUNNER = "maintainer"
ME = "machine-me"
CONFIG = types.SimpleNamespace(base_branch="dev/v0.3.0", repo="o/r", notify_cmd="", stale_claim_hours=24,
                               runs_dir=Path("/nonexistent/issue-agent-runs"))


def gh_failure():
    return CommandError(["gh"], subprocess.CompletedProcess([], 1, "", "github is down"))
NOW = datetime.datetime(2026, 10, 10, 12, 0, tzinfo=datetime.timezone.utc)
FRESH, OLD = "2026-10-10T11:00:00Z", "2026-10-08T11:00:00Z"


class FakeGitHub:
    login = RUNNER

    def __init__(self, issue_labels=(), marker=None, pr_state=("OPEN", ""), claims=None, claim_free=True):
        self.labels = set(issue_labels)
        self.marker = marker
        self.pr_state_value = pr_state
        self.claim_map = dict(claims or {})
        self.claim_free = claim_free
        self.claim_owned = True
        self.fail_comment = False
        self.fail_release = set()
        self.pr_error = None
        self.calls = []

    def holds_claim(self, number, sha):
        if self.claim_owned == "error":
            raise gh_failure()
        return self.claim_owned

    def issue(self, number):
        comments = [Comment("c1", 1, RUNNER, "OWNER", False, "run " + render_marker("run", self.marker))] if self.marker else []
        return Issue(number, "t", "b", RUNNER, "OWNER", False, sorted(self.labels), comments)

    def upsert_comment(self, number, body, existing=None):
        if self.fail_comment:
            raise RuntimeError("github is down")
        self.calls.append(("comment", body))

    def edit_labels(self, number, add=(), remove=()):
        if add or remove:
            self.calls.append(("labels", sorted(add), sorted(remove)))
        self.labels = (self.labels | set(add)) - set(remove)

    def close_issue(self, number, reason, comment):
        self.calls.append(("close", reason, comment))

    def pr_to_draft(self, pr):
        self.calls.append(("draft", pr))

    def release_claim(self, number, sha):
        if number in self.fail_release:
            raise gh_failure()
        self.calls.append(("release", number, sha))
        return True

    def create_claim(self, number, tree, note):
        self.calls.append(("claim", number))
        return "claim-sha" if self.claim_free else None

    def claims(self):
        return self.claim_map

    def claim_ref(self, number):
        return f"tags/issue-agent-claim/issue-{number}"

    def pr_state(self, pr):
        if self.pr_error is not None:
            raise self.pr_error
        return self.pr_state_value


def kinds(gh):
    return [call[0] for call in gh.calls]


RELEASED = ("release", 7, "claim-sha")


def mine(run="r1", created=FRESH):
    return {7: {"machine": ME, "run": run, "host": "h", "created": created, "sha": "claim-sha"}}


def theirs(created):
    return {7: {"machine": "machine-other", "run": "r9", "host": "other", "created": created, "sha": "claim-sha"}}


class FinishTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.tmp.cleanup()

    def finish(self, gh, state, completes=True):
        run = types.SimpleNamespace(issue=gh.issue(7), report={"completes_issue": completes}, pr=12, run_id="r1",
                                    spent=1.0, gates=[], branch="auto/issue-7-x-r1", claim_sha="claim-sha",
                                    directory=Path(self.tmp.name))
        with mock.patch.object(outcomes, "machine_id", return_value=ME):
            outcomes.finish(CONFIG, gh, run, pipeline.Outcome(state, "r", "abc123def456789"))

    def test_merged_and_complete_writes_the_marker_before_closing_then_releases(self):
        gh = FakeGitHub([labels.WORKING])
        self.finish(gh, "merged")
        self.assertEqual(kinds(gh), ["comment", "close", "labels", "release"])
        self.assertIn('"state":"merged"', gh.calls[0][1])
        self.assertIn('"machine":"machine-me"', gh.calls[0][1])
        self.assertEqual(gh.labels, set())

    def test_merged_but_incomplete_stays_open_for_a_decision(self):
        gh = FakeGitHub([labels.WORKING])
        self.finish(gh, "merged", completes=False)
        self.assertNotIn("close", kinds(gh))
        self.assertEqual(gh.labels, {labels.NEEDS_DECISION})

    def test_blocked_run_drafts_the_pr_and_review_does_not(self):
        blocked, review = FakeGitHub([labels.WORKING]), FakeGitHub([labels.WORKING])
        self.finish(blocked, "blocked")
        self.finish(review, "review")
        self.assertIn(("draft", 12), blocked.calls)
        self.assertEqual(blocked.labels, {labels.BLOCKED})
        self.assertNotIn("draft", kinds(review))
        self.assertEqual(review.labels, {labels.REVIEW})

    def test_a_run_that_lost_its_claim_leaves_the_successor_alone(self):
        gh = FakeGitHub([labels.WORKING])
        gh.claim_owned = False
        with mock.patch.object(outcomes, "notify") as notify:
            self.finish(gh, "blocked")
        self.assertEqual(gh.calls, [])
        notify.assert_called_once()

    def test_a_github_failure_keeps_the_claim_for_recovery(self):
        gh = FakeGitHub([labels.WORKING])
        gh.fail_comment = True
        with self.assertRaises(RuntimeError):
            self.finish(gh, "blocked")
        self.assertNotIn("release", kinds(gh))
        recorded = json.loads((Path(self.tmp.name) / "outcome.json").read_text())
        self.assertEqual((recorded["state"], recorded["issue"], recorded["machine"]), ("blocked", 7, ME))

    def test_unknown_ownership_still_records_the_outcome(self):
        gh = FakeGitHub([labels.WORKING])
        gh.claim_owned = "error"
        self.finish(gh, "review")
        self.assertEqual(gh.labels, {labels.REVIEW})
        self.assertIn("comment", kinds(gh))

    def test_a_failed_release_is_left_for_the_next_tick(self):
        gh = FakeGitHub([labels.WORKING])
        gh.fail_release = {7}
        self.finish(gh, "review")
        self.assertEqual(gh.labels, {labels.REVIEW})


class HousekeepingTest(unittest.TestCase):
    def sweep(self, gh):
        with mock.patch.object(outcomes, "machine_id", return_value=ME):
            return outcomes.housekeeping(CONFIG, gh, [gh.issue(7)], now=NOW)

    def test_my_claim_with_a_final_marker_replays_the_labels_and_releases(self):
        gh = FakeGitHub([labels.WORKING], marker={"state": "review", "run": "r1", "pr": 12}, claims=mine())
        self.assertTrue(self.sweep(gh))
        self.assertEqual(gh.labels, {labels.REVIEW})
        self.assertNotIn("comment", kinds(gh))
        self.assertEqual(gh.calls[-1], RELEASED)

    def test_my_claim_mid_run_becomes_blocked(self):
        gh = FakeGitHub([labels.WORKING], marker={"state": "claimed", "run": "r1"}, claims=mine())
        self.sweep(gh)
        self.assertEqual(gh.labels, {labels.BLOCKED})
        self.assertIn('"state":"blocked"', [c for c in gh.calls if c[0] == "comment"][0][1])
        self.assertIn(RELEASED, gh.calls)

    def test_my_claim_without_its_marker_is_only_released(self):
        for marker in (None, {"state": "blocked", "run": "older-run"}):
            gh = FakeGitHub([], marker=marker, claims=mine())
            self.assertTrue(self.sweep(gh))
            self.assertEqual(gh.calls, [RELEASED], marker)

    def test_my_claim_after_a_complete_finish_is_only_released(self):
        gh = FakeGitHub([labels.BLOCKED], marker={"state": "blocked", "run": "r1"}, claims=mine())
        self.sweep(gh)
        self.assertEqual(gh.calls, [RELEASED])

    def test_interrupted_run_whose_pr_merged_is_partial_not_blocked(self):
        gh = FakeGitHub([labels.WORKING], marker={"state": "pr-open", "run": "r1", "pr": 12}, claims=mine(), pr_state=("MERGED", "feedbeef"))
        self.sweep(gh)
        self.assertEqual(gh.labels, {labels.NEEDS_DECISION})
        self.assertNotIn("close", kinds(gh))
        self.assertIn('"state":"partial"', [c for c in gh.calls if c[0] == "comment"][0][1])

    def test_interrupted_run_with_an_open_pr_is_blocked_and_drafted(self):
        gh = FakeGitHub([labels.WORKING], marker={"state": "pr-open", "run": "r1", "pr": 12}, claims=mine())
        self.assertTrue(self.sweep(gh))
        self.assertEqual(gh.labels, {labels.BLOCKED})
        self.assertIn(("draft", 12), gh.calls)
        self.assertIn(RELEASED, gh.calls)

    def test_an_unreadable_pr_is_retried_before_the_run_is_given_up(self):
        gh = FakeGitHub([labels.WORKING], marker={"state": "pr-open", "run": "r1", "pr": 12}, claims=mine())
        gh.pr_error = gh_failure()
        self.assertTrue(self.sweep(gh))
        self.assertEqual(gh.labels, {labels.WORKING})
        self.assertNotIn("release", kinds(gh))
        self.assertIn('"attempts":1', [c for c in gh.calls if c[0] == "comment"][0][1])
        exhausted = FakeGitHub([labels.WORKING], marker={"state": "pr-open", "run": "r1", "pr": 12, "attempts": 2}, claims=mine())
        exhausted.pr_error = gh_failure()
        self.sweep(exhausted)
        self.assertEqual(exhausted.labels, {labels.BLOCKED})
        self.assertNotIn("draft", kinds(exhausted))
        self.assertIn(RELEASED, exhausted.calls)

    def test_a_recorded_outcome_is_replayed_after_finish_failed(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = types.SimpleNamespace(**{**vars(CONFIG), "runs_dir": Path(tmp)})
            (Path(tmp) / "r1").mkdir()
            record = {"issue": 7, "machine": ME, "state": "merged", "details": ["d"], "data": {"pr": 12, "sha": "feedbeef", "completes": True}}
            (Path(tmp) / "r1" / "outcome.json").write_text(json.dumps(record))
            gh = FakeGitHub([labels.WORKING], marker={"state": "pr-open", "run": "r1", "pr": 12}, claims=mine())
            with mock.patch.object(outcomes, "machine_id", return_value=ME):
                outcomes.housekeeping(config, gh, [gh.issue(7)], now=NOW)
            self.assertIn("close", kinds(gh))
            self.assertIn('"state":"merged"', [c for c in gh.calls if c[0] == "comment"][0][1])
            self.assertIn(RELEASED, gh.calls)

    def replay(self, record, marker):
        with tempfile.TemporaryDirectory() as tmp:
            config = types.SimpleNamespace(**{**vars(CONFIG), "runs_dir": Path(tmp)})
            (Path(tmp) / "r1").mkdir()
            (Path(tmp) / "r1" / "outcome.json").write_text(json.dumps(record))
            gh = FakeGitHub([labels.WORKING], marker=marker, claims=mine())
            with mock.patch.object(outcomes, "machine_id", return_value=ME):
                outcomes.housekeeping(config, gh, [gh.issue(7)], now=NOW)
        return gh

    def test_a_replayed_blocked_outcome_drafts_its_pr(self):
        record = {"issue": 7, "machine": ME, "state": "blocked", "details": ["CI still fails"], "data": {"pr": 12}}
        gh = self.replay(record, {"state": "pr-open", "run": "r1", "pr": 12})
        self.assertEqual(gh.labels, {labels.BLOCKED})
        self.assertIn(("draft", 12), gh.calls)

    def test_a_final_blocked_marker_with_a_pr_is_drafted_on_replay(self):
        gh = FakeGitHub([labels.WORKING], marker={"state": "blocked", "run": "r1", "pr": 12}, claims=mine())
        self.sweep(gh)
        self.assertEqual(gh.labels, {labels.BLOCKED})
        self.assertIn(("draft", 12), gh.calls)

    def test_a_record_of_another_issue_or_machine_is_ignored(self):
        for foreign in ({"issue": 99, "machine": ME}, {"issue": 7, "machine": "machine-other"}, {}):
            record = {**foreign, "state": "merged", "details": ["d"], "data": {"pr": 12, "sha": "feedbeef", "completes": True}}
            gh = self.replay(record, {"state": "pr-open", "run": "r1", "pr": 12})
            self.assertNotIn("close", kinds(gh), foreign)
            self.assertEqual(gh.labels, {labels.BLOCKED}, foreign)
        unfinished = {"issue": 7, "machine": ME, "state": "claimed", "details": ["d"], "data": {}}
        gh = self.replay(unfinished, {"state": "claimed", "run": "r1"})
        self.assertEqual(gh.labels, {labels.BLOCKED})

    def test_one_failing_claim_does_not_stop_the_sweep(self):
        claims = {7: dict(mine()[7]), 8: dict(mine()[7], sha="other-sha")}
        gh = FakeGitHub([], claims=claims)
        gh.fail_release = {7}
        with mock.patch.object(outcomes, "machine_id", return_value=ME):
            outcomes.housekeeping(CONFIG, gh, [], now=NOW)
        self.assertIn(("release", 8, "other-sha"), gh.calls)

    def test_claim_from_the_future_is_reported_not_trusted(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = types.SimpleNamespace(**{**vars(CONFIG), "state_dir": Path(tmp)})
            future = str(int(NOW.timestamp()) + 100 * 3600)
            gh = FakeGitHub([labels.WORKING], claims={7: {"machine": "machine-other", "run": "r9", "created_epoch": future, "sha": "x"}})
            with mock.patch.object(outcomes, "machine_id", return_value=ME), mock.patch.object(outcomes, "notify") as notify:
                outcomes.housekeeping(config, gh, [gh.issue(7)], now=NOW)
            notify.assert_called_once()
            self.assertNotIn("release", kinds(gh))

    def test_unreadable_claim_from_elsewhere_is_reported_once_and_kept(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = types.SimpleNamespace(**{**vars(CONFIG), "state_dir": Path(tmp)})
            gh = FakeGitHub([labels.WORKING], claims={7: {"created": "", "sha": "x"}})
            with mock.patch.object(outcomes, "machine_id", return_value=ME), mock.patch.object(outcomes, "notify") as notify:
                outcomes.housekeeping(config, gh, [gh.issue(7)], now=NOW)
                outcomes.housekeeping(config, gh, [gh.issue(7)], now=NOW)
            self.assertEqual(notify.call_count, 1)
            self.assertNotIn("release", kinds(gh))

    def test_another_machines_fresh_claim_is_left_alone(self):
        gh = FakeGitHub([labels.WORKING], marker={"state": "claimed", "run": "r9", "machine": "machine-other"}, claims=theirs(FRESH))
        self.assertFalse(self.sweep(gh))
        self.assertEqual(gh.calls, [])

    def test_another_machines_expired_claim_is_blocked_and_released(self):
        gh = FakeGitHub([labels.WORKING], marker={"state": "claimed", "run": "r9", "machine": "machine-other"}, claims=theirs(OLD))
        self.assertTrue(self.sweep(gh))
        self.assertEqual(gh.labels, {labels.BLOCKED})
        self.assertIn(RELEASED, gh.calls)

    def test_an_expired_claim_with_an_open_pr_drafts_it_and_keeps_the_pr_in_the_marker(self):
        marker = {"state": "pr-open", "run": "r9", "machine": "machine-other", "pr": 12}
        gh = FakeGitHub([labels.WORKING], marker=marker, claims=theirs(OLD))
        self.assertTrue(self.sweep(gh))
        self.assertEqual(gh.labels, {labels.BLOCKED})
        self.assertIn(("draft", 12), gh.calls)
        self.assertIn('"pr":12', [c for c in gh.calls if c[0] == "comment"][0][1])
        self.assertIn(RELEASED, gh.calls)

    def test_an_expired_claim_with_an_unreadable_pr_waits(self):
        marker = {"state": "pr-open", "run": "r9", "machine": "machine-other", "pr": 12}
        gh = FakeGitHub([labels.WORKING], marker=marker, claims=theirs(OLD))
        gh.pr_error = gh_failure()
        self.sweep(gh)
        self.assertEqual(gh.labels, {labels.WORKING})
        self.assertNotIn("release", kinds(gh))

    def test_working_label_left_by_this_machine_without_a_claim_recovers(self):
        gh = FakeGitHub([labels.WORKING], marker={"state": "review", "run": "r1", "machine": ME, "pr": 12})
        self.assertTrue(self.sweep(gh))
        self.assertEqual(gh.labels, {labels.REVIEW})

    def test_review_pr_outcomes(self):
        marker = {"state": "review", "run": "r1", "machine": ME, "pr": 12, "completes": True}
        merged = FakeGitHub([labels.REVIEW], marker=marker, pr_state=("MERGED", "feedbeef"))
        closed = FakeGitHub([labels.REVIEW], marker=marker, pr_state=("CLOSED", ""))
        still_open = FakeGitHub([labels.REVIEW], marker=marker)
        self.assertTrue(self.sweep(merged))
        self.assertIn("close", kinds(merged))
        self.assertEqual(merged.labels, set())
        self.sweep(closed)
        self.assertEqual(closed.labels, {labels.BLOCKED})
        self.assertFalse(self.sweep(still_open))
        self.assertEqual(still_open.calls, [])


class ClaimRecordTest(unittest.TestCase):
    def test_claim_note_round_trips_and_ages(self):
        note = outcomes.claim_note("m1", "20261010120000", now=NOW.timestamp() - 7200)
        claim = parse_claim(note, "")
        self.assertEqual((claim["machine"], claim["run"]), ("m1", "20261010120000"))
        self.assertAlmostEqual(outcomes.claim_age_hours(claim, NOW), 2.0)

    def test_claim_age_falls_back_to_any_timezone_aware_author_date(self):
        self.assertAlmostEqual(outcomes.claim_age_hours({"created": FRESH}, NOW), 1.0)
        self.assertAlmostEqual(outcomes.claim_age_hours({"created": "2026-10-10T19:00:00+08:00"}, NOW), 1.0)
        for unreadable in ("garbage", "2026-10-10T11:00:00", ""):
            self.assertIsNone(outcomes.claim_age_hours({"created": unreadable}, NOW), unreadable)


class RefusalTest(unittest.TestCase):
    def issue(self, association="OWNER", issue_labels=(), comments=(), state="OPEN"):
        notes = [Comment(f"c{i}", i, login, assoc, False, body) for i, (login, assoc, body) in enumerate(comments)]
        return Issue(7, "t", "b", "someone", association, False, list(issue_labels), notes, state=state)

    def test_external_issue_needs_a_maintainer_comment(self):
        self.assertIsNotNone(work.refusal(self.issue("NONE"), RUNNER))
        self.assertIsNone(work.refusal(self.issue("NONE", comments=[("helper", "MEMBER", "Do exactly X.")]), RUNNER))

    def test_vetoes_and_closed_issues_are_refused(self):
        for veto in labels.VETOES:
            self.assertIsNotNone(work.refusal(self.issue(issue_labels=[veto]), RUNNER), veto)
        self.assertIsNotNone(work.refusal(self.issue(state="CLOSED"), RUNNER))
        self.assertIsNone(work.refusal(self.issue(), RUNNER))


class ClaimTest(unittest.TestCase):
    def test_run_ids_are_unique_within_a_second(self):
        self.assertNotEqual(work.new_run_id(), work.new_run_id())

    def test_a_held_claim_stops_the_run_before_any_label_or_comment(self):
        gh = FakeGitHub(claim_free=False)
        config = types.SimpleNamespace(base_clone="/nonexistent", java_version="25", stale_claim_hours=24)
        with mock.patch.object(work.config_module, "resolve_java_home", return_value="/jdk"), \
                mock.patch.object(work, "git", return_value="tree"), \
                mock.patch.object(work, "machine_id", return_value=ME), \
                mock.patch.object(work.landing, "execute") as execute:
            self.assertIsNone(work.work_issue(config, gh, gh.issue(7)))
        execute.assert_not_called()
        self.assertEqual(gh.calls, [("claim", 7)])

    def test_a_failure_while_recording_does_not_crash_the_tick(self):
        gh = FakeGitHub()
        config = types.SimpleNamespace(base_clone="/nonexistent", java_version="25", stale_claim_hours=24,
                                       runs_dir=Path("/nonexistent/runs"), base_branch="dev/v0.3.0")
        with mock.patch.object(work.config_module, "resolve_java_home", return_value="/jdk"), \
                mock.patch.object(work, "git", return_value="tree"), \
                mock.patch.object(work, "machine_id", return_value=ME), \
                mock.patch.object(work, "post_run"), \
                mock.patch.object(work.landing, "execute", return_value=pipeline.Outcome("review", "r")), \
                mock.patch.object(work, "finish", side_effect=RuntimeError("github is down")):
            self.assertEqual(work.work_issue(config, gh, gh.issue(7)).state, "review")


if __name__ == "__main__":
    unittest.main()
