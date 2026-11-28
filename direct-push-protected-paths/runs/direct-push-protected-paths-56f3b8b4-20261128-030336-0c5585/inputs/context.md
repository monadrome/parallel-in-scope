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
