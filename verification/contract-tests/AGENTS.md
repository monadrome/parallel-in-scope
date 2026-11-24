# Hidden Contract Regression Tests

Applies to `verification/contract-tests/`. Follow the root delivery and review routes for changes.

## Visibility

Implementation and production-fixing agents keep this suite unread by default. Assurance promotion,
independent review, and hidden-suite maintenance may read it. This is a context-isolation boundary,
not access control: the suite is tracked so CI and independent verifiers can reproduce it.

When this suite fails, a hidden-suite maintainer gives the implementation agent a contract-level
report containing the `C-<id>`, public preconditions, and observable expected and actual results.
The implementation agent works from that report and the owning contract rather than the test source.

## Module conventions

The `contract-first-assurance` promotion phase owns eligibility and audit artifacts for additions.
Assign the next unused four-digit contract ID. Name the class `Cnnnn<Subject>ContractTest`, put
`@Tag("C-nnnn")` immediately above it, and keep the two IDs equal. Contract IDs are unique across
classes; one class may contain several tests for its contract. Every class must have at least one
passing, non-skipped testcase in the Surefire XML produced by the explicit suite run. Tests exercise
public behavior only.

Keep this module out of the default reactor and core test sources. Run its checks explicitly:

```bash
python3 scripts/check-contract-tests.py
mvn -Pcontract-tests -pl verification/contract-tests spotless:apply
mvn -Pcontract-tests -pl verification/contract-tests -am clean test
python3 scripts/check-contract-tests.py --reports verification/contract-tests/target/surefire-reports
```

Promoting every candidate was rejected because duplicate, unstable, and wrong-assumption candidates
would turn assurance output into noise. Hiding through untracked or encrypted files was rejected
because independent verification and CI could not reproduce the suite from the development line.
