# Lens V: Repository Conventions

Conventions from the root `AGENTS.md` Key Conventions section that a reviewer applies by
judgement to Java code the change touched. That section is the source; this lens only states how
a finding is formed. Most rules here are candidates for deterministic checks (R5); a rule moves out
of this file once its check exists.

### V01 Precondition outside the Guava taxonomy
- Smell: a touched runtime check that does not follow the taxonomy (`checkArgument`, `checkState`,
  `checkNotNull` for caller violations; `Verify` for internal invariants; `AssertionError` for
  platform impossibilities), such as `Objects.requireNonNull` in touched code.
- Move: migrate the touched check; leave untouched ones (the convention migrates on touch).
- Tier: `T0` when the exception type and message stay identical; otherwise `T1`.
- mechanizable: partly

### V02 Accessor naming
- Smell: a new `getX()` or `isX()` accessor outside JDK or third-party mandated names.
- Move: Rename to the bare `x()` form.
- Tier: `T0` private, `T1` package-private, `T2` public (changelog and migration entries required).
- mechanizable: yes

### V03 Exception message shape
- Smell: a new message that is capitalized, ends with a period, uses a placeholder other than `%s`,
  or omits the offending value or the actionable alternative.
- Move: rewrite the message.
- Tier: `T1` (messages are observable behavior; tests may assert them).
- mechanizable: partly

### V04 Comment in the wrong layer
- Smell: a maintainer algorithm in Javadoc, a contract only in a `/* */` comment, a cross-method
  invariant restated locally instead of pointing at its `design/` source, or an `@implSpec` used for
  a private algorithm. The layers are defined by `adr/0012-contract-and-implementation-comment-layers.md`.
- Move: move the text to its layer; never delete a valid distinct claim while moving it.
- Tier: `T0`.
- mechanizable: no

### V05 Declaration without its contract
- Smell: a new declaration whose Javadoc omits what a reader relies on: nullability, exceptions,
  concurrency, cancellation, or lifecycle.
- Move: complete the Javadoc.
- Tier: `T0`.
- mechanizable: no

### V06 Nullness annotation misuse
- Smell: `@Nullable` on a local variable, or a nullable value without `@Nullable` in TYPE_USE
  position.
- Move: remove or move the annotation.
- Tier: `T0`.
- mechanizable: yes

### V07 Spelling
- Smell: British spelling outside the doubled-`l` family (`cancelled`, `cancelling`, `canceller`,
  `cancellable`, `signalling`, `labelled`), or that family spelled with one `l`.
- Move: Rename.
- Tier: `T0` non-public; `T2` public (changelog and migration entries required).
- mechanizable: yes
