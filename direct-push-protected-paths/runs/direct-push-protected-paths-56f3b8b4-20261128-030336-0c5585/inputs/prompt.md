You are one independent reviewer in the llm-review-bench benchmark.

Review the change from 9cae3d5fe4ec9839098244594bea186b3e0ae6a2 to 56f3b8b4b158550c9e49d93e4a8ce894e670570a. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/direct-push-protected-paths/.review-benchmark/runs/direct-push-protected-paths-56f3b8b4-20261128-030336-0c5585/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/direct-push-protected-paths/.review-benchmark/runs/direct-push-protected-paths-56f3b8b4-20261128-030336-0c5585/inputs/context.md. Do not modify the snapshot. Do not read
benchmark gold labels, adjudication records, or other reviewers' output.

Return exactly one JSON object and nothing else (no Markdown fences, no prose):

{
  "summary": "one short paragraph describing the review",
  "findings": [
    {
      "file": "repository-relative/path.ext",
      "lineStart": 123,
      "lineEnd": 130,
      "title": "short defect title",
      "trigger": "concrete input, interleaving, or condition that reaches the defect",
      "consequence": "observable failure or contract violation",
      "evidence": "code path with file:line references",
      "severity": "P0|P1|P2|P3",
      "confidence": 0.0,
      "suggestedFix": "actionable fix"
    }
  ]
}

Rules:
- Report only defects introduced or materially exposed by this change.
- Every finding must have a concrete trigger and a user-observable consequence.
- Cite repository-relative files and the line range in the head snapshot.
- confidence is a number from 0 to 1.
- At most 20 findings. Return {"summary":"...","findings":[]} when you find
  nothing; do not return an empty response or prose.
- Do not include shell commands, patches, or hidden reasoning outside the JSON object.

## Shared context (verbatim, immutable)

# Review context: route protected-path changes through pull requests

Range: 9cae3d5f..56f3b8b4, one commit, `docs: route protected-path changes through pull requests`.

Background. `verification/verification-integrity.md` has a "Protected infrastructure" section. Changes to
those paths need code-owner review through `.github/CODEOWNERS`. The `verification-integrity` job in
`.github/workflows/ci.yml` runs only on pull requests. The project-delivery workflow allows a direct
fast-forward push to `dev/v0.5.0`, which skips that job and code-owner review. An agent on that route could
add a `REVIEWED_EXCEPTIONS` entry to `scripts/check-verification-integrity.py` and push it. A reviewer
rated this high severity. This commit adds the rule the reviewer asked for. The maintainer approved adding
a rule in this session, so do not re-open whether a rule should exist. Review its wording and completeness.

The change. One bullet, "Protected paths", added under "Delivery And CI" in
`.agents/skills/project-delivery/references/workflow.md`, directly after the "Direct development delivery"
bullet. The commit body carries MUST and MUST NOT lines for the workflow contract, plus an
`assurance: SKIPPED` line.

Attack surfaces. Read the diff and each file named below. Do not edit anything.

1. Trigger completeness. Compare the bullet's trigger with the Protected infrastructure section of
   `verification/verification-integrity.md` and with `.github/CODEOWNERS`. Does any path that code-owner
   review protects escape the rule? Is the relative link and its anchor correct?
2. Self-approval. Can an agent satisfy "the maintainer's approval in the current session" without a
   maintainer? Is the requirement checkable? Does quoting the approval in the commit body close the gap the
   finding describes?
3. Consistency. Read `.agents/skills/project-delivery/SKILL.md` ("Authority And Stops"),
   `.agents/skills/project-delivery/references/workflow.md` (the direct-delivery and named-branch bullets),
   and root `AGENTS.md` ("Permissions" and "Verification And Completion"). Does the bullet contradict any of
   them? Does it restate a fact that should be a pointer (single source of truth)?
4. Gotchas. The bullet says the integrity job runs only on pull requests, and that a `REVIEWED_EXCEPTIONS`
   entry counts as a protected change because the script is protected. Verify both against
   `.github/workflows/ci.yml` and `scripts/check-verification-integrity.py`.
5. Commit body. Does it list every MUST and MUST NOT the change adds, as the workflow contract requires?
   Is the `assurance: SKIPPED` reason sound under `.agents/skills/project-delivery/references/assurance.md`
   for a documentation-only change to a delivery reference?
6. Writing. Does the bullet state the rule once, front-load its trigger, avoid line numbers and commit
   hashes, and avoid restating the protected list?

For each finding, give the file, the heading or rule text, a severity (blocking, high, medium, or low), the
evidence, and the smallest fix. If there are no findings, say so.
