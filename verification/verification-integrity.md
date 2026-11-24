# Verification integrity gate

`scripts/check-verification-integrity.py` fails common ways of weakening the
repository's own verification while leaving routine test maintenance possible.
It complements test execution: a green run is not useful evidence when the
change also removed the tests or their assertions.

## Scope and rules

The gate scans Java tests under every `src/test` directory, Python
`test_*.py` files under `scripts`, and TypeScript `*.test.ts` files under
`tools/llm-review-bench/test`.

| Rule | Violation |
|---|---|
| V1 test-file deletion | A base file containing recognized test cases no longer exists in the head. A unique, byte-identical whole-file move is aligned by content hash and passes. |
| V2 test-case deletion | An existing Java test method, Python test function, or TypeScript test title is removed or renamed. Deleting a whole file reports V1 instead of one V2 per case. |
| V3 new test suppression | The head adds Java `@Disabled` variants/`@Ignore` or a constant-failing assumption; Python skip/expected-failure/xfail decorators, marks, or runtime skip calls; or TypeScript `skip`, `todo`, `only`, x-prefixed, test-options, or runtime test-context selection. Existing occurrences are grandfathered by baseline subtraction. |
| V4 assertion reduction | An existing test case has fewer assertions than in the base. Changing an assertion while keeping the count is allowed. |
| V5 constant assertion | The head adds a constant-true/false or self-comparing assertion, including self-comparisons with trailing message or delta arguments. Existing occurrences are grandfathered by baseline subtraction. |
| V6 owner-protection loss | A required `.github/CODEOWNERS` entry is absent or no longer contains `@monadrome`, a later matching rule removes that owner's effective coverage of a protected file (including an ownerless rule), or the file reaches GitHub's 3 MB loading limit. This rule has no exception mechanism. |

V3 resolves pytest aliases only while the imported name is not shadowed by a
function-local binding, and retains every wildcard import source.

V5 ignores self-comparison text containing calls (including type arguments),
assignments, construction, updates, or other potentially side-effecting
expressions. Calls inside template substitutions count as side effects; Java
class literals do not. JavaScript string delimiters do not make otherwise
identical literal contents distinct. JavaScript identity matchers do not treat
whole newly allocated objects, arrays, or regular expressions as the same
reference, including literals with a TypeScript type/non-null suffix; member
expressions remain comparable, and deep-equality matchers still reject
structural self-comparisons. Literal contents, including internal whitespace,
remain significant when comparing operands. A trailing TypeScript argument
comma does not add an operand.

Java assertions are `assertXxx`, `fail`, and Mockito `verify` calls. Python uses
the AST to count `assert` statements, `assertXxx` calls, and `pytest.raises` or
`pytest.warns`. TypeScript counts `assert` and `expect` calls. Counts are
compared within the same test case; assertions in separately defined helper
methods are not attributed to their callers.

V3 and V5 use the same baseline-subtraction model as the Java convention gate:
both trees are scanned, and a `(path, rule, normalized source)` key fails when
the head holds more occurrences than the base. Hunk line numbers are irrelevant,
so an unchanged suppression remains accepted when surrounding lines move.

## Protected infrastructure

Additions, modifications, and deletions under these paths print a `PROTECTED`
line in CI and require code-owner review through `.github/CODEOWNERS`:

- the CODEOWNERS file itself and every `.github/workflows/` file;
- every top-level `scripts/check-*.py` gate, all of `scripts/tests/`, and every
  `scripts/**/test_*.py` test;
- this contract.

Infrastructure changes do not fail merely because they changed; that would make
legitimate gate maintenance impossible. GitHub must enforce code-owner review,
and the `verification-integrity` pull-request job must be a required check in
branch protection. Those server-side settings are outside this repository file.
In particular, a workflow can otherwise remove or edit its own job, and a
checker can edit its own implementation; CODEOWNERS and required-check settings
are the trust boundary for those attacks.

The required CODEOWNERS patterns and owner are duplicated in the checker so its
initial introduction can be validated and later removal of an entry fails V6.
The checker also evaluates the last matching rule for every protected file,
because CODEOWNERS gives that rule precedence; an ownerless match removes
coverage just as surely as a match naming only another owner. GitHub-unsupported
negation and character-range rules are skipped the same way GitHub skips them.
Changing the repository owner requires a reviewed update to both sources.

## Intentional weakening and exceptions

Deleting obsolete verification, renaming a test, reducing assertions through a
stronger shared abstraction, or adding a justified platform-specific skip needs
maintainer review. Add an exact `(path, rule, key)` tuple to
`REVIEWED_EXCEPTIONS` in `scripts/check-verification-integrity.py` as part of
that reviewed change. Keys are the path for V1, the test-case name for V2/V4,
and whitespace-normalized source for V3/V5. The script is CODEOWNERS-protected;
an exception is therefore an explicit review input rather than an inline
self-approval in the test being changed. V6 cannot be excepted. Remove stale
exceptions in a later protected change.

## Usage

```bash
python3 scripts/check-verification-integrity.py                    # base: merge base of origin/dev/v0.5.0 and HEAD
python3 scripts/check-verification-integrity.py --base <rev>       # base repository at <rev>, via git archive
python3 scripts/check-verification-integrity.py --base-root <dir> --head-root <dir>  # two directories, no git
```

The head defaults to the working tree, so uncommitted edits are checked.
Findings use `path:line: V<n>: message`; file-level findings omit the line.
Protected changes are informational lines beginning with `PROTECTED`.

Exit status: 0 no integrity violation, 1 integrity violations, 2 the check could
not run (unknown revision, missing root, unreadable source, invalid Python, or a
recognized Java or TypeScript test whose declaration cannot be parsed).
The unit tests are `scripts/tests/test_check_verification_integrity.py`.

CI runs the gate in the `verification-integrity` job of
`.github/workflows/ci.yml` on pull requests, based on the merge base of `HEAD`
and `origin/$GITHUB_BASE_REF`. Push builds do not invent a second base policy.
The development-line reference is kept current by
`scripts/check-project-baseline.py`.

## Limitations

This is a heuristic anti-weakening gate, not a semantic-equivalence proof. A
change can replace an assertion with a different but weaker assertion of the
same count, move assertions into an uncalled helper, use an unrecognized custom
test annotation, or generate cases dynamically without being detected. Normal
review and mutation testing remain necessary. Test renames and broad assertion
refactors can also require an explicit exception even when they improve the
suite; this is deliberate friction at the verification trust boundary.

## Rejected alternatives

- Failing every checker or workflow edit was rejected because it would prevent
  legitimate maintenance. Protected-change reporting plus CODEOWNERS review
  separates modification from approval.
- Diff-hunk matching was rejected because line shifts and file moves create
  false findings. Two-tree scanning and content-hash move alignment provide a
  stable baseline instead.
- A single repository-wide assertion total was rejected because adding weak
  assertions could offset deleting strong ones. V4 compares each existing test
  case independently.
- Full behavioral comparison of base and head tests was rejected as outside a
  dependency-free source gate; the documented V4/V5 heuristics cover common
  tampering without claiming complete detection.
