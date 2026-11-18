# Backlog

Changes planned before a specific release. Landed items are removed rather
than struck through; the changelog and Git history keep the record. Open
design questions live in `design/proposal/`, not here. Entries cite the
retired inventory ids once for traceability.

## Before the v0.5.0 release

- At the release merge, replace `blob/dev/v0.5.0/` with `blob/main/` in the
  7 source links of `docs/en/index.md` and `docs/zh/index.md` (find them
  with `grep -rn "blob/dev/v0.5.0" docs/`). All are live navigation to
  GitHub-only demo and integrations content; none are dated notes, so no
  per-link judgment is needed. Flipping earlier 404s the integrations
  links (no `integrations/` tree on `main` yet) and points the demo links
  at pre-v0.5.0 content (was O-18).

## Maintenance and verification (no release target)

Each entry is a verification or maintenance task, not an open design question;
entries harvested from reclassified proposals cite the retired proposal id.

- Re-verify the bare-vs-TTL-wrapper identity probe against the current
  runtime-level executor wait graph: a same-pool NO_ISSUE report reflects
  the identity contract, not the absence of starvation. Any change to the
  resource-identity projection is a separate contract decision (was P0021).
- Clarify borrowed-executor shutdown ordering (runtime close, awaitQuiescence,
  then pool shutdown) in user-facing docs before evaluating any fast
  stable-shutdown diagnosis; a shutdown preflight is not race elimination
  (was P0021).
- Measure TTL capture cost, preparation retention, simultaneous timeout
  expiry, blocked JUL handlers, and cancellation-dispatch latency before
  writing performance numbers about full preparation or the sliding window;
  the window is not a global request or memory bound (was P0021).
- Review task-group-cancellation and task-group-lifecycle for stale claims:
  removed listener/direct-CANCELLED observation wording, the resolve-signature
  enumeration, and conflation of execution-exit confirmation with observation
  publication; keep cancellation and observation single-sourced (was P0022).
- Review the over-strong "failure only means user/submission failure" wording
  in task-group-api-and-options and explain nested checked (batch) versus
  unchecked (group) exception reads with concrete consuming code; do not add
  a throwing-callback family to unify the surface (was P0022).
- Teach in the user guide that an empty batch `failedTaskResult()` for a pure
  timeout does not mean all tasks succeeded, and that `FAIL_FAST` covers both
  ran-then-cancelled and never-submitted elements, so the outcome alone cannot
  reconstruct execution history (was P0022).
- Verify CHANGELOG entries for 0.3 development items misplaced in the 0.2
  section against their introducing commits, de-duplicate, and re-home them;
  published wording typos stay (was P0022).
- Decide whether `design/runner-interrupt-explainer.html` stays as reachable
  teaching material or is deleted (was P0022).
- Co-source compilable documentation examples with the demo/consumer where a
  narrow sample shows real value, instead of a full-surface API cache
  (was P0022).
- Re-check user-guide wording that names the removed executor tags and the
  package-private `SubmissionException`; user docs must not present
  package-private types as user-facing API (was P0022).
- Handle the zh philosophy int-code narrative, the frontmatter and duplicate
  graveyard overlap, and the docs bilingual length differences by content
  value (was P0022).
- Verify jar identity in the CI Java 8 job across the JDK 25 install and the
  JDK 8 switch (SHA-256 output and cache-restore order); the historical
  cache-overwrite explanation needs current runs, not old logs (was P0023).
- Make the release path reuse the documentation, format, and real Java 8
  consumer results, and add a post-release isolated-resolution smoke proving
  Central serves the deployed artifact; record actual status, version, and
  source revision — a successful deploy summary is not download evidence
  (was P0023).
- Keep consumer sources aligned with the API generation they verify, so
  `scripts/verify-maven-central.sh` can check old stable coordinates without
  compiling mismatched sources (was P0023).
- Pin GitHub Actions revisions, align the POM SCM tag with source identity,
  and decide Automatic-Module-Name only when a real JPMS downstream exists;
  reproducibility claims need two clean rebuilds compared (was P0023).
- Measure before choosing any of: a shared name index for
  `TaskGroupResult`/`GroupValues` lookups, cached `terminationKindCounts`,
  member-value double-fold elimination, or derived projections for frequent
  graph queries. No caching or index without current-version measurements;
  linear lookup in small groups is not a proven regression (was P0027).
- Re-check `GroupDraftContractTest` narration, banners, and stale
  `IO_BOUND`/`rejectEnqueue` rationale against the current context (was P0029).
- Review ownership of the `CancellationTokenTest` wait helper and the
  convergence/`CheckpointsOutcome` helper documentation (was P0029).
- Rule on `TaskFutureTest` placeholder negative assertions against the real
  emitter and neighboring behavior assertions: prove they detect regressions
  or record the deletion reason; the historical audits disagree, so neither
  is authoritative (was P0029).
- Verify and clean the `MustBeClosedChecker` test usages, then evaluate
  raising the check from WARN (was P0030).
- If `RestrictedApi` is proposed, first name the test allowlist and the
  production delegation call paths (was P0030).
- Package-level `CheckReturnValue` needs fresh real-hit evidence over the
  current class/interface-level rules (was P0030).
- Argue the `fullyLock` narrow-suppression refactor separately from the
  wrong-lock protocol fix (was P0030).
- Decide the annotation use for `submissionExecutorIsAdapter` and
  `TaskExecutionContext` timing queries, and the ignore policy for
  `awaitQuiescence`/`awaitExecutionExit` boolean results (was P0030).
- Replace the derived `VALID_EN = VALID.replace(...)` fixture in
  `scripts/tests/test_check_proposal.py` with a literal English fixture, or
  assert the replacement happened, so the English-heading case cannot
  silently pin the Chinese fixture (was refactoring-review R7/T2).
- Re-run the internal-comment burn-down on the core sources before the 1.0
  release and after any large refactor of `core/src/main/java`: renames and
  restructures change what is derivable from code, so deleted claims can
  become load-bearing and kept ones derivable. The adjudication baseline
  through 2026-11 is `verification/comment-burn-down-2026-11.md`; comments
  added or modified since are unadjudicated.
