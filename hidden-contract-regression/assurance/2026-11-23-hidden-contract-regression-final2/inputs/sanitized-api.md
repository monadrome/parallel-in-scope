# Sanitized public test API

Use Python 3.12 standard-library `unittest`, `subprocess`, `tempfile`, `pathlib`, and XML/string helpers. Tests execute in a real verification checkout whose path is supplied by the `ASSURANCE_REPO` environment variable. Do not read repository source, tests, Git metadata, reports, or hidden directories. Invoke only these public commands:

- `python3 <ASSURANCE_REPO>/scripts/check-contract-tests.py --test-root <fixture>`
- `python3 <ASSURANCE_REPO>/scripts/check-contract-tests.py --test-root <fixture> --reports <reports>`

Metadata fixture Java sources are plain text. A valid source with a package is:

```java
package example;
import org.junit.jupiter.api.Tag;
@Tag("C-0001")
class C0001SubjectContractTest {
}
```

Surefire fixtures use the canonical class-specific filename `TEST-<FQCN>.xml`, such as `TEST-example.C0001SubjectContractTest.xml`, under the reports directory:

```xml
<testsuite>
  <testcase classname="example.C0001SubjectContractTest" name="contract" />
</testsuite>
```

The classname must exactly match package plus class. A testcase outcome is a direct child named `skipped`, `failure`, or `error`.

Write one sealed `candidate_tests.py` plus `generation-notes.md` in the current directory's `candidates/` directory. Give every test method a stable `CONTRACT-TDD-nnn` ID in its name or docstring. For each candidate record preconditions, operation, observation point, assertion, and uncertainty in its docstring. Do not modify any input and do not create production code.
