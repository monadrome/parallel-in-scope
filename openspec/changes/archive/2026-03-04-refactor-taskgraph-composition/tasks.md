## 1. Refactor TaskGraph class declaration

- [x] 1.1 Remove `extends TransmittableThreadLocal<TaskGraph.Data>` from the `TaskGraph` class declaration
- [x] 1.2 Add `final` modifier to the `TaskGraph` class
- [x] 1.3 Replace `private static final TaskGraph TTL = new TaskGraph()` with a composed `TransmittableThreadLocal<Data>` anonymous inner class that overrides `initialValue()` (returns `null`) and `copy(Data)` (returns `parentValue`)
- [x] 1.4 Remove the overridden `initialValue()` and `copy()` instance methods from `TaskGraph` (they move into the anonymous class)

## 2. Update Javadoc

- [x] 2.1 Update the class-level Javadoc to describe composition instead of inheritance (remove "Extends TransmittableThreadLocal" phrasing)

## 3. Verification

- [x] 3.1 Run `mvn clean compile` to verify compilation succeeds
- [x] 3.2 Run `mvn test` to verify all existing tests pass without modification
