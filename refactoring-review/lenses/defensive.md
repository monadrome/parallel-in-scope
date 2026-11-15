# Lens D: Defensive Programming

Checks, catches, fallbacks, guards, and switches that the change added for failures nothing can
cause. Coding models produce these habitually: OpenAI's
[Codex Prompting Guide](https://github.com/openai/openai-cookbook/blob/main/examples/gpt-5/codex_prompting_guide.ipynb)
tells GPT Codex integrators to forbid broad catches and success-shaped fallbacks, Anthropic's
[prompting best practices](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/claude-4-best-practices)
give Claude the same instruction, and OpenCode's repository-internal `rmslop` command removes
"extra defensive checks or try/catch blocks that are abnormal for that area of the codebase". The
GPT-family habits the evidence supports most strongly are broad catch-and-swallow, success-shaped
fallbacks, re-validation of trusted values, and compatibility shims nobody asked for; null-check and
type-guard sprawl are reported by users. The rules below are model-independent.

## The firing question

For each check, catch, fallback, guard, or switch the change added, name the caller, input, or
foreign code that makes it fire:

- **An external caller or foreign code** (public API, user callable or listener, executor, log
  handler, unannotated JDK or third-party API, reflection): a trust boundary. Keep it, in the root
  `AGENTS.md` Guava taxonomy form.
- **Our own code being wrong**: an internal invariant. Keep a `Verify` only when the invariant is
  non-obvious and cheap to check; otherwise delete the check and let NullAway, constructors, and
  tests carry it.
- **Something the platform rules out**: throw `AssertionError`.
- **Nothing**: dead code. Delete it.

Never report code that restores or propagates interruption (root `AGENTS.md` P1-P4). Removing a
check, catch, or fallback that something can reach changes behavior (R1): report it with its trigger
as a finding for code review, not as a refactoring, and give it tier `T2`.

### D01 Null check on a value the type system already rules out
- Smell: in a `@NullMarked` package, private or package-private code checks a parameter, field, or
  return value that is not `@Nullable` (`if (x == null) return;`, `checkNotNull(x)`,
  `x != null ? x : fallback`). NullAway (`OnlyNullMarked`) already rejects null at every compiled
  call site.
- Move: Remove Dead Code. When the value really can be null, annotate it `@Nullable` instead so
  callers see it.
- Suppress: public or protected entry points (callers may not run NullAway; the taxonomy requires
  `checkNotNull`); values from user code, unannotated JDK or third-party APIs (`Map.get`), or
  reflection; a check that runs before publishing to another thread, where a null would surface far
  from its cause.
- Tier: `T0` private; `T1` package-private.
- mechanizable: partly

### D02 Re-validation below the boundary that owns the value
- Smell: a precondition repeated in package-private code after the public entry already checked
  it, or an internal invariant written as `checkArgument`/`checkState`, which blames a caller for
  what would be our own bug.
- Move: keep the one check at the owning boundary and delete the copy; convert a non-obvious
  internal invariant to `Verify`. When the same check recurs, consider a type that makes the invalid
  value unrepresentable (`S09`).
- Suppress: a re-check after the value crossed a thread or a user callback; a `Verify` whose message
  states an invariant documented in `design/`.
- Tier: `T1` (the exception type is observable when the bug fires).
- mechanizable: no

### D03 Broad catch that swallows, logs and continues, or substitutes a value
- Smell: `catch (Exception | RuntimeException | Throwable e)` with an empty body, logging only, or a
  default `return`, around code that fails only through our own bug or a caller violation. Typical
  model output: a protective `try` around the whole method body.
- Move: remove the `try` and let the exception propagate; or narrow it to the one expected type and
  handle it where the caller can act; or translate it with the original as the cause.
- Suppress: a catch around foreign code (user callbacks, listeners, user-installed log handlers,
  `Executor.execute`) whose escape would skip a protocol step, when a comment beside it names the
  step (the existing pattern: "A user-installed log handler must not skip the terminal
  publication"); catch-and-rethrow after cleanup.
- Tier: `T1`; `T2` when the propagated exception reaches a public caller.
- mechanizable: partly (a broad or empty catch without an adjacent comment)

### D04 Success-shaped fallback that hides a broken state
- Smell: on a path where failure means a bug or a broken dependency, the code returns a plausible
  value: `Collections.emptyList()`, `Optional.empty()`, `0`, `false`, `getOrDefault` for a key that
  must exist, `orElse(default)`, or a `default` branch over a closed enum that returns instead of
  throwing.
- Move: fail fast: `Verify` for internal invariants, `checkState` for caller misuse,
  `AssertionError` for an impossible enum constant; drop the `default` branch so a new constant fails
  visibly.
- Suppress: a default the Javadoc contract promises; paths a contract marks best-effort.
- Tier: `T2` when the fallback is reachable (a possible defect for code review); `T1` when the
  detector shows it is unreachable.
- mechanizable: no

### D05 Defensive catch that breaks the interruption rules
- Smell: protective handling that loses or mis-owns the interrupt: `catch (Exception e)` around a
  blocking call that also swallows `InterruptedException`; `catch (InterruptedException e)` retry
  loops; wrapping without restoring the flag; `Thread.interrupted()` used to reset state in a method
  that is not the documented sole consumer of the signal; a read-only status method that checks the
  flag.
- Move: follow P1-P4 (read `design/interruption-contract.md` first): rethrow, or restore the flag and
  translate with the cause; inspect with `isInterrupted()`; catch `InterruptedException` on its own.
- Suppress: a catch that restores the flag or rethrows is required, never defensive; the documented
  uninterruptible waits of synchronous `Par.map` and group `runAll`; code covered by `C04`.
- Tier: `T1`.
- mechanizable: partly (a broad catch around a call that declares `InterruptedException`)

### D06 Compatibility shim or legacy path in 0.x
- Smell: the change keeps an old name, overload, or format alive beside the new one: a `@Deprecated`
  forwarding alias, a duplicate overload with the old parameter order, dual reading of an old shape,
  a parameter kept "for callers", or `// removed` and `// kept for compatibility` comments. The root
  `AGENTS.md` allows `0.x` breaks without compatibility shims.
- Move: Remove Dead Code; update every caller and record a public break in the changelog and
  migration notes.
- Suppress: a transition the user or a `design/` proposal explicitly requested; JDK-parity members
  in `queue/`; third-party mandated names.
- Tier: `T2` public or protected; `T0` or `T1` otherwise.
- mechanizable: partly (`@Deprecated` added in the diff)

### D07 Runtime switch for a hypothetical need
- Smell: a system property, environment read, static `enabled` flag, or configuration toggle the
  change added where only one value is used or tested (`Boolean.getBoolean("...legacyMode")` around
  a new path).
- Move: delete the switch and keep the chosen path. A real option is public surface and needs the
  `design/first-principles.md` evaluation.
- Suppress: switches named by a live contract under `design/`; test seams exercised with both values.
  Parameters and overloads belong to `S04`.
- Tier: `T2` when user-reachable; `T1` otherwise.
- mechanizable: partly (`System.getProperty`, `Boolean.getBoolean`, `System.getenv` in main code)

### D08 Type guard on a statically known type
- Smell: `instanceof` checks, guarded casts with a fallback, or `getClass()` comparisons where the
  declared type already settles the question; an `instanceof` cascade with an "unknown type" fallback
  over a closed set of package-private implementations.
- Move: delete the guard; replace the cascade with polymorphism (`S07`) or an exhaustive `switch`
  that throws `AssertionError` on the impossible branch.
- Suppress: classifying a `Throwable` (`Error`, `InterruptedException`, `CancellationException`,
  `ExecutionException` unwrapping), the library's legitimate use; values from user code or across a
  generic-erasure boundary.
- Tier: `T0` private; `T1` package-private.
- mechanizable: partly

### D09 Retry, sleep, or timeout around a deterministic failure
- Smell: a retry loop, `Thread.sleep` backoff, or added timeout around in-process code whose failure
  is a bug or a caller violation, not contention or I/O.
- Move: remove the retry and propagate the first failure. Real time-bounding uses the existing
  `CancellationToken` deadline mechanism, not a new timer.
- Suppress: CAS retry loops that are the concurrency mechanism itself (lens `C`); retries a contract
  documents.
- Tier: `T2` (timing and results change).
- mechanizable: partly

### D10 Defensive copy of a value already immutable or never shared
- Smell: `new ArrayList<>(list)`, `ImmutableList.copyOf` of an `ImmutableList`, or
  `Collections.unmodifiableList` over an immutable or method-local collection, in package-private
  code that never exposes or mutates it. This rule is a Java-specific extension; no source attributes
  the habit to a particular model.
- Move: Inline the original reference.
- Suppress: copies at public API boundaries (caller-mutable inputs, outputs a caller must not
  mutate); snapshots built for another thread (lens `C`).
- Tier: `T0` private; `T1` package-private.
- mechanizable: no
