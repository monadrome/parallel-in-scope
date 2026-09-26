# Nullability Annotations

`parallel-in-scope` uses JSpecify annotations with compile-time enforcement by NullAway (via Error
Prone). Null misuse in the library's own sources fails `mvn compile`; downstream builds get precise
nullability information from the public API signatures.

## Design

All annotations come from a single source: `org.jspecify.annotations` (JSpecify). JSpecify's
`TYPE_USE` semantics also cover generic type arguments, so one annotation set serves public API,
callbacks, and internal implementation alike.

Every package declares `@NullMarked` in `package-info.java`: parameters, return values, fields,
and generic type arguments are non-null unless marked `@Nullable`. Only the exceptions need
explicit `@Nullable`.

## Compile-time enforcement

The build runs NullAway as an Error Prone plugin with `-Xep:NullAway:ERROR` and
`OnlyNullMarked=true`, so only `@NullMarked` code is checked. Error Prone requires JDK 21+ to run;
the build uses JDK 25 (LTS) while `release=8` keeps the produced bytecode at Java 8. Maven's
enforcer plugin fails fast on older JDKs.

Downstream consumers using NullAway, Kotlin, or IntelliJ read the same annotations from the
compiled signatures — the `jspecify` artifact is a compile-scope dependency, as JSpecify
recommends, and is also transitively provided by Guava.

## When to use `@Nullable`

Use it when a return value can be absent, when a public constructor explicitly accepts `null`, or
when a parameter has an optional value. Public examples include a nullable parent
`CancellationToken` and `TaskBatchResult.BatchReport.firstException()`. Package-private runtime
types use the same rule for their implementation state.

Do not add redundant annotations to non-null parameters covered by the package default or to
non-null return values guaranteed by the implementation.

## Annotation style

`@Nullable` is a TYPE_USE annotation and sits immediately before the type it qualifies:

```java
static @Nullable TaskExecutionContext current() { ... }

public @Nullable Throwable firstException() { ... }
```

This illustrates internal source style; `TaskExecutionContext` is not public API.

For a qualified type name, the annotation goes after the package prefix:

```java
java.time.@Nullable Duration closeGrace
```

## Dependencies

The `jspecify` artifact is a compile-scope dependency. It appears in public API signatures, so
downstream null-checkers must be able to read it; the jar itself is tiny and adds no runtime
behavior.
