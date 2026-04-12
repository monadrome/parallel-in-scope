## MODIFIED Requirements

### Requirement: Package layout
The project SHALL organize all classes into the following 3 packages under the base `io.github.huatalk.vformation`:
- root package — API facade, cancellation, context, livelock, execution engine classes
- `queue` — Scheduling queue classes
- `spi` — Extension point interfaces

Each package SHALL contain a `package-info.java` file with `@javax.annotation.ParametersAreNonnullByDefault` annotation.

#### Scenario: All classes are in their designated packages
- **WHEN** the project compiles successfully
- **THEN** each class resides in its assigned package as defined by the class-to-package mapping

#### Scenario: Each package has package-info.java
- **WHEN** the source directory is listed
- **THEN** each of the 3 packages SHALL contain a `package-info.java` file
