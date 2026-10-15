import types
import unittest

from issue_agent import labels, policy


def issue(number, issue_labels, ready=False, trusted=True, current=True):
    return types.SimpleNamespace(
        number=number, labels=issue_labels, ready_authorized=ready, trusted_author=trusted, triage_current=current)


class ReconcileTest(unittest.TestCase):
    desired = {"type": "bug", "priority": "priority/p1", "route": labels.ELIGIBLE}

    def test_fresh_issue_gets_every_family(self):
        add, remove, applied, suppressed = labels.reconcile([], self.desired, {})
        self.assertEqual(add, {"bug", "priority/p1", labels.ELIGIBLE})
        self.assertEqual(remove, set())
        self.assertEqual(applied, self.desired)
        self.assertEqual(suppressed, set())

    def test_human_choice_in_a_family_is_left_alone(self):
        add, remove, applied, _ = labels.reconcile(["enhancement", "priority/p0"], self.desired, {})
        self.assertEqual(add, {labels.ELIGIBLE})
        self.assertNotIn("type", applied)
        self.assertNotIn("priority", applied)

    def test_owned_label_follows_a_changed_verdict(self):
        previous = {"priority": "priority/p2"}
        add, remove, applied, _ = labels.reconcile(["priority/p2"], self.desired, previous)
        self.assertIn("priority/p1", add)
        self.assertIn("priority/p2", remove)
        self.assertEqual(applied["priority"], "priority/p1")

    def test_label_a_human_removed_is_never_reapplied(self):
        previous = {"route": labels.NEEDS_DECISION}
        desired = dict(self.desired, route=labels.NEEDS_DECISION)
        add, remove, applied, suppressed = labels.reconcile([], desired, previous)
        self.assertNotIn(labels.NEEDS_DECISION, add)
        self.assertIn(labels.NEEDS_DECISION, suppressed)
        add, _, _, _ = labels.reconcile([], desired, applied, suppressed)
        self.assertNotIn(labels.NEEDS_DECISION, add)

    def test_human_veto_next_to_owned_eligible_hands_the_family_over(self):
        previous = {"route": labels.ELIGIBLE}
        add, remove, applied, _ = labels.reconcile([labels.ELIGIBLE, labels.NEEDS_DECISION], self.desired, previous)
        self.assertEqual((add & labels.FAMILIES["route"], remove), (set(), set()))
        self.assertNotIn("route", applied)


class VerdictTest(unittest.TestCase):
    raw = {"type": "bug", "priority": "p0", "route": "agent", "reason": "direction", "size": "s",
           "summary": " fix it ", "plan": "steps", "areas": "Par", "duplicate_of": 0}

    def test_agent_route_clears_the_reason(self):
        verdict = policy.validate_verdict(self.raw, trusted=True)
        self.assertEqual((verdict.route, verdict.reason, verdict.summary), ("agent", "none", "fix it"))

    def test_untrusted_author_always_routes_to_a_human_without_free_text(self):
        verdict = policy.validate_verdict(self.raw, trusted=False)
        self.assertEqual((verdict.route, verdict.reason), ("human", "external"))
        self.assertEqual((verdict.summary, verdict.plan, verdict.areas), ("", "", ""))
        self.assertEqual(verdict.priority, "p0")

    def test_out_of_enum_values_fall_back_safely(self):
        verdict = policy.validate_verdict({"type": "exploit", "route": "merge-now", "duplicate_of": True}, trusted=True)
        self.assertEqual((verdict.type, verdict.route, verdict.reason, verdict.priority), ("question", "human", "direction", "p2"))
        self.assertEqual(verdict.duplicate_of, 0)


class SelectionTest(unittest.TestCase):
    def test_vetoes_exclude_even_authorized_issues(self):
        for veto in labels.VETOES:
            picked = policy.select_candidates([issue(1, [labels.READY, veto], ready=True)], {"p0", "p1"})
            self.assertEqual(picked, [], veto)

    def test_ready_needs_a_privileged_actor(self):
        self.assertEqual(policy.select_candidates([issue(1, [labels.READY], ready=False)], {"p0"}), [])

    def test_automatic_pick_needs_trust_priority_and_current_triage(self):
        base = [labels.ELIGIBLE, "priority/p1"]
        self.assertEqual(len(policy.select_candidates([issue(1, base)], {"p0", "p1"})), 1)
        self.assertEqual(policy.select_candidates([issue(1, base, trusted=False)], {"p0", "p1"}), [])
        self.assertEqual(policy.select_candidates([issue(1, base, current=False)], {"p0", "p1"}), [])
        self.assertEqual(policy.select_candidates([issue(1, base)], set()), [])

    def test_order_is_priority_then_authorization_then_age(self):
        issues = [
            issue(5, [labels.ELIGIBLE, "priority/p1"]),
            issue(9, [labels.READY, "priority/p1"], ready=True),
            issue(7, [labels.READY, "priority/p0"], ready=True),
            issue(3, [labels.READY], ready=True),
        ]
        order = [c.number for c in policy.select_candidates(issues, {"p0", "p1"})]
        self.assertEqual(order, [7, 9, 5, 3])


class PathPolicyTest(unittest.TestCase):
    def test_secrets_and_adr_edits_are_forbidden(self):
        forbidden, _ = policy.classify_paths([("A", ".env"), ("A", "keys/release.asc"), ("M", "adr/0006-queues-ship-with-core.md")])
        self.assertEqual(len(forbidden), 3)

    def test_new_adr_deletions_and_build_files_need_a_human(self):
        changes = [("A", "adr/0009-new.md"), ("D", "docs/old.md"), ("M", "pom.xml"), ("M", ".github/workflows/ci.yml"),
                   ("M", "scripts/issue_agent/policy.py"), ("R100", "src/a.java")]
        forbidden, human = policy.classify_paths(changes)
        self.assertEqual(forbidden, [])
        self.assertEqual(len(human), 6)

    def test_gate_scripts_and_the_runner_contract_need_a_human(self):
        _, human = policy.classify_paths([("M", "scripts/check-local-links.py"), ("M", "design/issue-automation.md")])
        self.assertEqual(human, ["M scripts/check-local-links.py", "M design/issue-automation.md"])
        self.assertEqual(policy.classify_paths([("M", "design/sliding-window-refill.md")]), ([], []))

    def test_agent_instructions_need_a_human(self):
        _, human = policy.classify_paths([("M", "AGENTS.md"), ("M", "design/AGENTS.md"), ("A", "src/x/CLAUDE.md")])
        self.assertEqual(len(human), 3)

    def test_ordinary_source_changes_pass(self):
        self.assertEqual(policy.classify_paths([("M", "src/main/java/x/Par.java"), ("A", "docs/en/guide.md")]), ([], []))


class NamingTest(unittest.TestCase):
    def test_titles(self):
        self.assertTrue(policy.valid_title("fix: keep cancelled entries out of the snapshot"))
        self.assertTrue(policy.valid_title("ci(api): flag public api removals"))
        self.assertFalse(policy.valid_title("Fix: Capitalized summary"))
        self.assertFalse(policy.valid_title("update stuff"))

    def test_newest_dev_line_ignores_lookalikes(self):
        heads = ["dev/v0.2.0", "dev/v0.3.0", "dev/v0.10.0-clean", "dev/v0.9.1", "dev/linqh"]
        self.assertEqual(policy.newest_dev_line(heads), "dev/v0.9.1")
        self.assertIsNone(policy.newest_dev_line(["main"]))

    def test_branch_name_is_safe_for_any_title(self):
        self.assertEqual(policy.branch_name(48, "Track `public` API!! surface; changes", "20261010"),
                         "auto/issue-48-track-public-api-surface-20261010")
        self.assertTrue(policy.branch_name(1, "$(rm -rf /)", "x").startswith("auto/issue-1-rm-rf-"))


if __name__ == "__main__":
    unittest.main()
