# Independent Review Procedure

## Independent Review

Every non-empty change requires an independent review by someone other than the
implementer before delivery. This includes documentation, tests, scripts, build
configuration, instruction files, and generated or checked-in metadata. The review
must read the changed files from the commit diff and remain read-only. The reviewer
runs on a different model than the implementer, for lightweight and full reviews
alike; a different harness alone does not satisfy this. Launching the review agent
is part of the prescribed delivery route. When no different model is available for
the reviewer seat, say so and mark the review accordingly instead of silently
running a same-model review.

- A mechanical or documentation-only change uses a lightweight review of the diff,
  referenced paths, cited commands, and route classification. It does not require
  the full adversarial review unless another route also applies.
- A public API, documented contract, concurrency-sensitive, security-sensitive, or
  otherwise high-risk change uses the full independent review below.
- A reviewer that cannot read the changed files, times out, or fails is `blocked`,
  not clean. Rerun it once after addressing the cause, then record the final status
  and evidence in the delivery record.
- Before accepting a review, compare its input manifest with `git diff --name-only`
  for the reviewed range. A review that omits documentation, scripts, skills,
  instruction files, tests, or ledger metadata requires a separate lightweight
  read-only review for those paths; a Java-only refactoring pass is supplementary,
  not coverage for omitted files.

## Adversarial Review

For public API, documented-contract, concurrency-sensitive, security-sensitive, or
otherwise high-risk changes, run the full independent review with its own budget
after the initial commit. Use the commit diff as the review baseline so review and
fixes can continue in later sessions:

- Use two reviewers so the implementer and reviewers cover three distinct model
  types; different harnesses alone do not satisfy this. Run them through
  `/multi-review` (`.agents/skills/multi-review/SKILL.md`) on the commit range,
  which keeps reviewers read-only on snapshots. Codex and a Magpie-routed reviewer
  are permitted when each run pins its concrete model. When no reviewer CLI can
  run, use fresh-context agents on the required models in a clone without a
  remote. If two distinct reviewer models are unavailable, record the review as
  blocked rather than reducing coverage. Reviewers must not edit files. Name
  attack surfaces: interleavings, contract versus implementation, test quality,
  Java 8, and generics.
- When refactoring-review ran, review the range from its base, including its
  `refactor:` commits. Read its report as settled findings, and add behavior
  preservation of each refactor commit and each applied `T1` item as attack
  surfaces.
- A review counts only when its output or transcript shows the reviewer read
  the changed files. A timeout, a failed run, or a reviewer that cannot read
  the files is no review: rerun it once, then record the review as blocked,
  never as clean.
- Diff each contract MUST/MUST NOT named by the change against the
  implementation line by line; catching drift is a post-commit review duty,
  not a periodic audit.
- Address confirmed findings in verified follow-up commits. Later rounds review
  those commit diffs, first reading settled findings as the baseline, then
  looking beyond them. Use a fresh seat when context is nearly full or the
  reviewer starts agreeing with itself. Stop when a round confirms no new
  blocking finding (a defect, a contract violation, or an ineffective test) or
  after three rounds, and record what remains open with its disposition.
- Reproduce every finding against the working tree; retain, downgrade, or reject
  it explicitly. Record findings, dispositions (including rejections), and each
  fix's actual effect in the change's `design/` document, or in the fix commit
  message when no `design/` document covers the change. The review workspace or
  run directory is the audit trail; delivery commits its audit records (raw
  logs, agent output, and the nested snapshot excluded) to the `llm-ledger`
  branch before removing
  the worktree (Checkout Isolation in [workflow](workflow.md)), so the evidence
  outlives the cleanup.
- Reverse-verify every regression test: temporarily revert only the fix, observe
  the new test fail, then restore the fix.
- For executable production changes (including refactors), mutation-relevant
  test/helper changes, toolchain or build/PIT configuration changes, or
  investigation of a PIT result, read [PIT verification](../../../../verification/pit.md)
  before selecting scope or reporting completion. It owns applicability,
  commands, scope expansion, freshness, dispositions, and acceptance criteria.
  PIT needs no permission and writes build outputs under `target/`.
- PIT is a core-library gate by default. Do not include `demo/`, `integrations/`,
  or `verification/maven-central-consumer/` in a mutation run unless the user
  explicitly requests a broader scope; validate those standalone consumers
  through their own tests and the runtime-consumer matrix, and record PIT as
  `skipped` for a consumer-only change.
- When `contract-first-assurance` is used, keep mutation-runner evidence and
  assurance acceptance separate. Preserve every raw status, including
  `TIMED_OUT`, in XML and reports. A timeout may contribute to a `pass` assurance
  gate only when the adapter permits an accepted disposition and the run records
  repeatable focused evidence, passing unmutated tests, complete logs, a
  source-level liveness explanation, and maintainer or independent-review
  approval. Report this as `PASS WITH ACCEPTED EXCEPTIONS`; unresolved,
  environment-related, or incomplete timeouts remain `inconclusive`.

Historical examples and results already live in
`design/group-one-shot-api-refactor-codex.md` section 9; consult them when
investigating review or test blind spots.
