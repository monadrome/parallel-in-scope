# Follow-up fixes after DeepSeek Pro full review

Review only the committed range `7b288f070823df2b955200db7b9eb3f41f67f24f..e4814ef939f7596316aab6c44cf3e12d2f112316`, read-only. Start from the settled full-review findings:

1. Non-pure repeated calls were false V5 self-comparisons.
2. `toBe`/`strictEqual` incorrectly treated allocated object/array/regex literals as identical references.
3. Whitespace normalization collapsed distinct string-literal contents.

Their fixes and one additional wildcard fix are in this range:
- `87be1a7c` records exact literal spans and normalizes only code outside literals; six before-failing subcases now pass.
- `8afa3129` resolves known pytest and pytest.mark wildcard runtime suppressions; five before-failing subcases now pass.
- `5aef14d5` limits self-comparisons to side-effect-free operands, distinguishes identity from deep equality for allocated JavaScript literals, and handles redundant outer parentheses. Negative cases failed before and pass after; pure and deep-equality noops remain detected.

The final `e4814ef9` refactor preserves these behaviors while comparing source spans directly, sorting literal spans, and removing duplicate operand scanning. Its detector findings and behavior-preservation verification are settled; check this refactor as an additional attack surface rather than re-raising its rejected structural suggestions.

Verify those fixes against their tests, then look beyond them for concrete introduced defects. Check literal-span offsets, side-effect detection, identity versus deep equality, wildcard scoping, and baseline subtraction. Do not re-report the already tracked ledger correction or GitHub enforcement. Return at most three findings, or an empty findings list promptly.
