You are one independent reviewer in the llm-review-bench benchmark.

Review the change from 56f3b8b4b158550c9e49d93e4a8ce894e670570a to 91e824808d96eaf0967021dce23e1c954df0d012. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/direct-push-protected-paths/.review-benchmark/runs/direct-push-protected-paths-91e82480-20261128-031847-a31c66/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/direct-push-protected-paths/.review-benchmark/runs/direct-push-protected-paths-91e82480-20261128-031847-a31c66/inputs/context.md. Do not modify the snapshot. Do not read
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

# Review context, round 2: route protected-path changes through pull requests

Range for this round: 56f3b8b4..91e82480, one follow-up commit on top of the reviewed commit
`docs: route protected-path changes through pull requests`. Read the whole "Protected paths" bullet in
`.agents/skills/project-delivery/references/workflow.md` at HEAD, not only the changed lines.

Settled round-1 findings. Treat each as fixed in this range; reject a claim that the fix is wrong only with
evidence from the files.

- The maintainer-approval exception let an agent push a protected change, because the agent writes the
  commit body that quotes the approval (one P1 and one P2 from two reviewers). Fixed: the exception is
  removed. Protected paths go through a pull request, and only the maintainer pushes them directly.
- The Named branch bullet forbids the separate branch a pull request needs (P2). Fixed: the protected-path
  bullet authorizes that branch and the pull request.
- The bullet restated the integrity job's pull-request-only trigger (P3). Fixed: the bullet no longer states
  it; the rule rests on code-owner review.

Attack surfaces for this round. Read each file named.

1. Completeness of the bypass closure. With the exception gone, list every route by which an agent could
   land a change to a protected path without a pull request. Check `.agents/skills/project-delivery/SKILL.md`
   ("Authority And Stops" and the delivery steps), `.agents/skills/project-delivery/references/workflow.md`
   (every delivery bullet), and `design/issue-automation.md` (the unattended runner's human-merge list and
   its auto-merge rule). Report any route that remains open.
2. Consistency of protected lists. The bullet points at the Protected infrastructure list in
   `verification/verification-integrity.md`. Compare that list with the runner's human-merge list in
   `design/issue-automation.md`. Report any protected path the runner could merge unattended.
3. Link and anchor. Confirm that `../../../../verification/verification-integrity.md#protected-infrastructure`
   resolves from `.agents/skills/project-delivery/references/`.
4. Commit body. Each commit must list the MUST and MUST NOT lines it adds or changes. Check that the
   follow-up commit does so, and that its assurance reason fits `.agents/skills/project-delivery/references/assurance.md`.
5. Writing. The bullet should state the rule once, front-load its trigger, avoid restating facts that
   another document owns, and avoid line numbers and commit hashes.

For each finding, give the file, the heading or rule text, a severity (blocking, high, medium, or low), the
evidence, and the smallest fix. A protected-path gap that lies outside this bullet is still a finding; say
whether this change should fix it or a separate change should. If there are no findings, say so.
Do not edit files.
