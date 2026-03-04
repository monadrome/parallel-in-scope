## 1. Remove forEach from Par

- [x] 1.1 Delete the `forEach` method from `Par.java` (lines 95-106)
- [x] 1.2 Remove the `Consumer` import if no longer used

## 2. Migrate Tests

- [x] 2.1 Migrate `ParTest.testParForEach_basic()` to use `par.map()` with a function returning `null`
- [x] 2.2 Migrate `ParTest.testParForEach_empty()` to use `par.map()`
- [x] 2.3 Migrate `ParTest.testParallelism_limit()` to use `par.map()`
- [x] 2.4 Migrate `ParTest.testTaskListener_invoked()` to use `par.map()`
- [x] 2.5 Migrate `ParTest.testParForEach_nullInput()` to use `par.map()`
- [x] 2.6 Migrate `ExecutorRegistryTest.testParForEachWithExecutorName()` to use `par.map()`
- [x] 2.7 Migrate `ExecutorRegistryTest.testParForEachWithUnregisteredNameThrows()` to use `par.map()`

## 3. Migrate Demos

- [x] 3.1 Migrate `NestedScopeCancellationDemo.java` outer scope from `forEach` to `map`
- [x] 3.2 Migrate `DeadlockDetectionDemo.java` outer scope from `forEach` to `map`

## 4. Verify

- [x] 4.1 Run `mvn clean compile` to confirm no compilation errors
- [x] 4.2 Run `mvn test` to confirm all tests pass
