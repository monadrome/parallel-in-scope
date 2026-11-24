# Contract gaps

- No missing cancellation, failure-attribution, lifecycle, context, executor, queue, or concurrency requirement was identified; the change does not alter those library semantics.
- The execution checker cannot independently prove who produced an XML report. The clean CI and promotion commands supply the required provenance boundary.
- Agent invisibility is deliberately limited to default context routing. It does not provide filesystem permissions or secrecy.
- JDK 11 consumer evidence must come from an actual JDK 11 runtime; another JDK is not a substitute.

## Candidate-derived results

- CONTRACT-TDD-001 confirms non-contract helper acceptance; CONTRACT-TDD-008 confirms malformed canonical XML rejection; CONTRACT-TDD-009 confirms a passing testcase cannot hide skipped, failed, or errored peers. All three were reverse-verified.
- The new-coverage candidates are Python CLI fixtures. Promotion to the Java-only hidden module is blocked without creating a target or archive; no candidate was force-promoted.
- Six other candidates duplicate existing behavior-level evidence. No candidate failed, was unstable, or exposed a production defect.
