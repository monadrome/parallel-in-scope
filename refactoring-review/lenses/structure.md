# Lens S: Structure

Local structure of the code the change added or modified: names, function shape, duplication
inside the change, unneeded generality, and dead code. Cross-file smells belong to lens `M`.

Tier by visibility unless a rule says otherwise: private or local `T0`, package-private `T1`,
public or protected `T2`.

### S01 Mysterious name
- Smell: a function, variable, field, or type whose name does not reveal what it does or holds.
- Move: Rename. If no honest name comes, report the murky responsibility instead.
- Suppress: names fixed by a JDK or third-party contract (`isShutdown`, `isSatisfied`); suffixes
  defined by the root `AGENTS.md` (`Scope`, `Context`, `Step`, `Id`) used as defined.
- mechanizable: no

### S02 Long function
- Smell: a method the change grew until one reading cannot hold its steps, or one that mixes
  levels of abstraction (protocol steps next to string formatting).
- Move: Extract Function for a named step; Split Phase when the method does two passes.
- Suppress: a method whose statement order carries an ordering proof kept beside the statements;
  extraction must keep that proof next to the statements it justifies.
- mechanizable: no

### S03 Duplicated code inside the change
- Smell: the same logic shape appears in more than one hunk of the change.
- Move: Extract Function and call it from each site; Slide Statements first when the copies differ
  only in order.
- Suppress: test code (lens `T` owns test duplication); two copies whose divergence is the point.
- mechanizable: no

### S04 Speculative generality
- Smell: a parameter, hook, overload, type parameter, or abstraction the change added that no
  caller in the change needs.
- Move: Remove Dead Code, Inline Function, Collapse Hierarchy, Remove Parameter.
- Suppress: an extension point named by a live contract under `design/`.
- mechanizable: no

### S05 Middle man
- Smell: a method or type added by the change that only forwards to another.
- Move: Inline Function or Remove Middle Man.
- Suppress: a forwarding layer that exists to keep a package-private kernel type out of a public
  signature (the root package's encapsulation boundary).
- mechanizable: no

### S06 Dead code
- Smell: code the change added or orphaned that nothing reaches: unused private members, branches
  whose condition is constant, parameters no caller varies.
- Move: Remove Dead Code. Default tier `T0` regardless of visibility when the code is private.
- Suppress: a kernel branch a comment keeps for a documented defensive reason.
- mechanizable: partly (unused private members are compiler and Error Prone territory)

### S07 Repeated conditional
- Smell: the same `switch` or `if` cascade over the same type or enum recurs in the change.
- Move: Replace Conditional with Polymorphism, or one shared lookup both sites use.
- Suppress: exhaustive `switch` over an enum where each site maps a different concern.
- mechanizable: no

### S08 Message chain
- Smell: navigation such as `a.b().c().d()` the caller should not depend on.
- Move: Hide Delegate on the first object.
- Suppress: fluent builder chains of the public `Step` API.
- mechanizable: no

### S09 Primitive standing in for a concept
- Smell: a primitive or string that carries a domain concept with its own rules.
- Move: Replace Primitive with Object.
- Suppress: `long` nanosecond values (the Java 8 idiom used throughout the kernel); `int` indexes.
- mechanizable: no
