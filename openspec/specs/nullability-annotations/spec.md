## ADDED Requirements

### Requirement: Hybrid annotation strategy
The project SHALL use a hybrid nullability annotation strategy:
- Public API classes and SPI interfaces SHALL use `javax.annotation.Nullable` and `javax.annotation.Nonnull` from JSR-305
- Internal implementation classes SHALL use `org.checkerframework.checker.nullness.qual.Nullable` and `org.checkerframework.checker.nullness.qual.NonNull` from Checker Framework

Public API classes: `Par`, `ParOptions`, `AsyncBatchResult`, `ParConfig`, `Checkpoints`, `TaskType`, `CancellationToken`, `CancellationTokenState`
SPI interfaces: `TaskListener`, `ExecutorResolver`, `LivelockListener`, `PurgeStrategy`
Internal classes: all other classes not listed above

#### Scenario: Public API class uses JSR-305 annotation
- **WHEN** a nullable parameter or return value exists in `Par.java`
- **THEN** it SHALL be annotated with `@javax.annotation.Nullable`

#### Scenario: SPI interface uses JSR-305 annotation
- **WHEN** a nullable parameter or return value exists in `ExecutorResolver.java`
- **THEN** it SHALL be annotated with `@javax.annotation.Nullable`

#### Scenario: Internal class uses Checker Framework annotation
- **WHEN** a nullable parameter or return value exists in `ScopedCallable.java`
- **THEN** it SHALL be annotated with `@org.checkerframework.checker.nullness.qual.Nullable`

### Requirement: Package-level default NonNull via ParametersAreNonnullByDefault
Every package SHALL have a `package-info.java` file annotated with `@javax.annotation.ParametersAreNonnullByDefault`, establishing that all method parameters are non-null by default.

#### Scenario: Root package has default NonNull
- **WHEN** `package-info.java` in `io.github.huatalk.vformation` is read
- **THEN** it SHALL contain `@ParametersAreNonnullByDefault` annotation

#### Scenario: Queue sub-package has default NonNull
- **WHEN** `package-info.java` in `io.github.huatalk.vformation.queue` is read
- **THEN** it SHALL contain `@ParametersAreNonnullByDefault` annotation

#### Scenario: SPI sub-package has default NonNull
- **WHEN** `package-info.java` in `io.github.huatalk.vformation.spi` is read
- **THEN** it SHALL contain `@ParametersAreNonnullByDefault` annotation

### Requirement: Explicit dependency declaration
The `pom.xml` SHALL explicitly declare `com.google.code.findbugs:jsr305` and `org.checkerframework:checker-qual` as `provided` scope dependencies.

#### Scenario: JSR-305 declared as provided
- **WHEN** `pom.xml` is read
- **THEN** it SHALL contain a dependency entry for `com.google.code.findbugs:jsr305` with `<scope>provided</scope>`

#### Scenario: Checker-qual declared as provided
- **WHEN** `pom.xml` is read
- **THEN** it SHALL contain a dependency entry for `org.checkerframework:checker-qual` with `<scope>provided</scope>`

### Requirement: Nullable annotation on nullable return values
Every method whose return value can be `null` (as documented by Javadoc or runtime checks) SHALL be annotated with `@Nullable` using the appropriate annotation source per the hybrid strategy.

#### Scenario: ParConfig.getExecutorResolver returns nullable
- **WHEN** `ParConfig.getExecutorResolver()` is inspected
- **THEN** its return type SHALL be annotated with `@javax.annotation.Nullable`

#### Scenario: ScopedCallable.current returns nullable
- **WHEN** `ScopedCallable.current()` is inspected
- **THEN** its return type SHALL be annotated with `@org.checkerframework.checker.nullness.qual.Nullable`

#### Scenario: AsyncBatchResult.BatchReport.getFirstException returns nullable
- **WHEN** `BatchReport.getFirstException()` is inspected
- **THEN** its return type SHALL be annotated with `@javax.annotation.Nullable`

### Requirement: Nullable annotation on nullable parameters
Every method parameter that explicitly accepts `null` (handled gracefully, not throwing NPE) SHALL be annotated with `@Nullable` using the appropriate annotation source per the hybrid strategy.

#### Scenario: Par.map list parameter accepts null
- **WHEN** `Par.map(String, List, Function, ParOptions)` is inspected
- **THEN** the `list` parameter SHALL be annotated with `@javax.annotation.Nullable`

#### Scenario: CancellationToken parent parameter accepts null
- **WHEN** `CancellationToken(CancellationToken parent)` constructor is inspected
- **THEN** the `parent` parameter SHALL be annotated with `@org.checkerframework.checker.nullness.qual.Nullable`

### Requirement: No annotation on non-null parameters
Method parameters that are non-null by contract (enforced by `Objects.requireNonNull()` or implicit) SHALL NOT have explicit `@Nonnull` or `@NonNull` annotations, relying on the package-level `@ParametersAreNonnullByDefault` instead.

#### Scenario: Par constructor config parameter has no explicit annotation
- **WHEN** `Par(ParConfig config)` is inspected
- **THEN** the `config` parameter SHALL NOT have any explicit nullability annotation (covered by package default)

### Requirement: Documentation update
`CLAUDE.md` SHALL include a Nullability Conventions section documenting the hybrid strategy, package-level default, and annotation source rules. A wiki document SHALL also be created explaining the rationale and usage guidelines.

#### Scenario: CLAUDE.md contains nullability section
- **WHEN** `CLAUDE.md` is read
- **THEN** it SHALL contain a section titled "Nullability Conventions" or equivalent

#### Scenario: Wiki document exists
- **WHEN** the `doc/` or `wiki/` directory is listed
- **THEN** it SHALL contain a document about nullability annotations
