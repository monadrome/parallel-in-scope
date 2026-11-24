# Verification-integrity final review through equality semantics

Read the complete `06efaae43c0f343efc6e2bb6c289b907b218b9be..e4814ef939f7596316aab6c44cf3e12d2f112316` diff and every changed file read-only. This remediates the original assurance skip rejection, verification-gate bypasses and false positives, and adds the maintainer-directed three-model review rule.

Settled findings from earlier review and assurance:
1. GitHub required-check and code-owner enforcement is tracked separately; do not re-report missing server settings in this repository diff.
2. Imported pytest skip/xfail runtime aliases and renamed decorators bypassed V3; alias resolution and before-failing regressions were added.
3. Java and TypeScript comments bypassed self-comparison detection; comment-free comparison views and regressions were added.
4. TypeScript nonliteral expect self-comparisons bypassed V5; `toBe`, `toEqual`, and `toStrictEqual` are covered.
5. Distinct string/regex literals with different internal whitespace were false V5 positives. `87be1a7c` records literal spans and normalizes only code outside them; six subcases failed before and pass after the fix.
6. Wildcard pytest runtime suppressions bypassed V3. `8afa3129` resolves known names imported from pytest and pytest.mark; five subcases failed before and pass after the fix.
7. Repeated non-pure calls were false self-comparisons, and `toBe`/`strictEqual` wrongly treated allocated object/array/regex literals as identities. `5aef14d5` restricts comparisons to side-effect-free operands, separates identity from deep equality, and handles redundant outer parentheses. Negative cases failed before and pass after; pure and deep-equality noops remain detected.

Refactoring R3 and R6 were withdrawn and their net diff is zero. The final refactoring detector produced four findings: literal spans are now sorted and only one normalized operand is checked in `e4814ef9`; scanner extraction and test-helper extraction were rejected for the documented growth and V4-attribution reasons. Its verification passed with no oracle edits and growth ratio 0.2055. The stale ledger assurance note is scheduled for correction after the required final assurance run; do not treat that known metadata correction as a new code defect.

Attack surfaces: decorator/runtime/wildcard alias correctness; literal/comment/regex offsets and exact contents; side-effect detection; identity versus deep equality; expect-chain parsing; V3/V4/V5 baseline subtraction; three-model review contract consistency; test quality and behavior preservation. Verify the settled fixes and look beyond them for concrete new defects. Return at most five findings with triggers and observable consequences, or an empty findings list promptly.
