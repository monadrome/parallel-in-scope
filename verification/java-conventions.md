# Java convention gate

`scripts/check-java-conventions.py` fails a change that introduces a violation
of a Java convention already written in the "Key Conventions" section of the
root `AGENTS.md`, which stays the authoritative wording. Before this gate,
review alone enforced these rules. The gate only reports violations a change
adds: existing code is migrated when it is touched, as `AGENTS.md` requires,
not by a sweep.

## Scope

- Library sources only: `*.java` under `src/main/java`.
- The `queue` package (`io/github/monadrome/parallelinscope/queue/`) is
  excluded. Its JDK-port parity is governed by
  `src/main/java/io/github/monadrome/parallelinscope/queue/AGENTS.md`.
- Tests and the demo are out of scope for this first gate.

## Rules

| Rule | Violation | Exemption |
|---|---|---|
| R1 accessor naming | A public or protected zero-argument method named `get[A-Z]...` or `is[A-Z]...` declared in a class, enum, or interface body (interface methods count as public unless `private`) | The method carries `@Override`, which marks a JDK or third-party contract such as `ExecutorService.isShutdown()` |
| R2 exception message style | A string-literal message passed to `checkArgument`, `checkState`, `checkNotNull`, `checkElementIndex`, `checkPositionIndex`, `verify`, or `verifyNotNull` (bare, or qualified by `Preconditions.`/`Verify.`) that ends with `.`, starts with an uppercase letter, or contains a `%` that does not begin `%s` | A leading code identifier may keep its casing (below) |
| R3 doubled-l spelling | `canceled`, `canceling`, `canceler(s)`, `cancelable`, `signaling`, `labeled` in lowercase, capitalized, or uppercase form, as a word or a camelCase part (`onCanceled`), in code, comments, or strings | None; the gate sees only added occurrences |
| R4 null checks | `requireNonNull` in code (comments and strings are ignored) | None; use Guava `checkNotNull` |

R1 rationale: accessors use the bare `x()` form; JDK or third-party contracts
keep their mandated names, and such implementations carry `@Override`.
Anonymous class bodies and method bodies are not scanned for declarations.
Annotation arguments are ignored when deciding whether a declaration is a
field initializer, so `@SuppressWarnings(value = "unchecked") public T getX()`
is still reported.

R2 rationale: messages are lowercase fragments without a trailing period, and
Guava's `Strings.lenientFormat` recognizes only the two-character `%s`. Every
other `%` sequence, `%%` included, is printed verbatim, so `"50%% of %s"`
renders as `50%% of ...` and `%d` or `%1$s` are never substituted. The check
applies to arguments made only of `+`-joined string literals; a message held
in a variable or built from non-literal parts is skipped. For
`checkElementIndex`/`checkPositionIndex` the checked argument is the
description (third argument). Whether a message interpolates the offending
value or names an actionable alternative is not mechanically checkable and is
left to review. A comma inside `Identifier<...>` is treated as type-argument
nesting, so `Map<String, Integer>` does not shift the message position; an
unspaced comparison such as `a<b, c>d` is indistinguishable from a type
argument list and merges the two arguments, which skips the message.

Leading code identifier: the message may start uppercase when its first token
(`[A-Za-z_$][\w.$]*`) looks like code, meaning it contains an uppercase letter
after its first character or a `.`, `$`, `_`, or digit (`ParRuntime is
closed`, `TaskGroup.start was called twice`), or when the token is followed by
optional whitespace and a quote (`Par '%s' does not belong to ...`). A
capitalized English word such as `Invalid value` is still a violation.

R3 rationale: the project uses the doubled-l family listed in `AGENTS.md`
(`cancelled`, `cancelling`, `canceller`, `cancellable`, `signalling`,
`labelled`). Third-party names and dated records keep their original
spellings; they already exist in the base, so baseline subtraction passes
them, and no exemption list is kept.

R4 rationale: `AGENTS.md` prefers `Preconditions.checkNotNull` over
`Objects.requireNonNull`.

## Baseline subtraction

The gate scans two trees, the base and the head, with the same rules. Each
violation has a key of relative path, rule, and normalized source text:

- R1: the declaration from the end of the preceding member to the closing
  parenthesis, comments removed;
- R2: the call name, the message argument text, and the problem found;
- R3: the misspelled word and its source line;
- R4: the source line.

A key fails when the head holds it more times than the base, and then every
head occurrence of that key is reported, because text cannot tell which copy
is new. Hunk line numbers are not used, so an unchanged violation passes when
surrounding lines move. Editing a line or declaration that carries an existing
violation changes its key and reports it: touching it is when the convention
asks for migration. Moving a violation to another file also reports it.

## Usage

```bash
python3 scripts/check-java-conventions.py                    # base: merge base of origin/dev/v0.4.0 and HEAD
python3 scripts/check-java-conventions.py --base <rev>       # base: src/main/java at <rev>, via git archive
python3 scripts/check-java-conventions.py --base-root <dir> --head-root <dir>  # two directories, no git
```

The head defaults to the working tree's `src/main/java`, so uncommitted edits
are checked. Output lines are `src/main/java/<path>:<line>: <rule>: <message>`.

Exit status: 0 no new violations, 1 new violations, 2 the check could not run
(unknown revision, missing source root, unreadable or non-UTF-8 source).

CI runs the gate in the `docs` job of `.github/workflows/ci.yml` on pull
requests only, with the merge base of `HEAD` and `origin/$GITHUB_BASE_REF` as
the base, the same base the API-surface check uses. Push builds do not run it:
a push has no pull-request base, and a second base policy is not worth adding.
The unit tests are `scripts/tests/test_check_java_conventions.py`.

## Limitations

The rules are text-level heuristics over source with comments and literal
contents blanked, not a parse of Java. They can miss a declaration split by
unusual constructs (for example braces inside annotation arguments before a
member) and can misread a method named like a precondition that is not one.
A pass means no new pattern match, not a proof that the conventions hold.
Report a false positive or negative by fixing the script and adding a test
case.

## Rejected alternatives

- Local-variable `@Nullable` detection. Without a parser the script cannot
  reliably tell locals from fields, parameters, and type-use positions, and
  NullAway already enforces local nullness at compile time.
- Inline-comment rules and comment-count or comment-length gates. The section
  "Comment gates rejected (2026-10-09)" of
  [`design/decision-log.md`](../design/decision-log.md) rejects them.
- A repository-wide scan. Existing violations (for example the
  `Objects.requireNonNull` calls already in the library) are migrated when
  touched, not by a gate that would force a sweep.
