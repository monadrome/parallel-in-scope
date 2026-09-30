package verification;

import com.google.common.reflect.TypeToken;
import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.GroupValues;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import io.github.monadrome.parallelinscope.TaskFuture;
import io.github.monadrome.parallelinscope.TaskGroup;
import io.github.monadrome.parallelinscope.TaskOutcome;
import io.github.monadrome.parallelinscope.TaskType;
import io.github.monadrome.parallelinscope.Tuple2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class MavenCentralConsumerTest {

    private static final ParId CONSUMER = ParId.of("consumer-pool");

    @Test
    void publishedArtifactCanBeResolvedAndUsed() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime runtime = ParRuntime.builder().register(CONSUMER, executor).build();
        try {
            BatchOptions options = BatchOptions.timeout("consumer-smoke", Duration.ofSeconds(5))
                    .parallelism(2)
                    .taskType(TaskType.IO_BOUND);

            Par par = runtime.par(CONSUMER);
            TaskBatchResult<Integer> result = par.map(Arrays.asList(1, 2), value -> value * 2, options);

            List<TaskFuture<Integer>> results = result.results();
            assertEquals(2, results.size());
            assertEquals(2, results.get(0).get());
            assertEquals(4, results.get(1).get());
            assertEquals("consumer-smoke", results.get(0).taskName());
            assertEquals(TaskOutcome.SUCCESS, results.get(0).outcome());

            assertEquals("consumer-smoke", options.name());
            assertEquals(2, options.parallelism());
            assertEquals(Optional.of(Duration.ofSeconds(5)), options.timeout());
            assertEquals(TaskType.IO_BOUND, options.taskType());
            assertEquals(CONSUMER, par.id());
            assertEquals(runtime, par.runtime());
        } finally {
            runtime.close();
            executor.shutdownNow();
        }
    }

    /**
     * Exercises the deepest generic inference the public API asks of a consumer: a three-member
     * chain whose value type is a left-nested {@link Tuple2}, a terminal combine whose body
     * destructures that nest inside a lambda, and typed lookups. This runs under a real JDK 8
     * compiler in CI, which is where the nesting is most likely to break.
     */
    @Test
    void publishedArtifactExposesTheOneShotGroupChain() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime runtime = ParRuntime.builder().register(CONSUMER, executor).build();
        TypeToken<List<String>> namesType = new TypeToken<List<String>>() {};
        try {
            Par par = runtime.par(CONSUMER);
            try (TaskGroup<Tuple2<Tuple2<String, List<String>>, Integer>, String> group = runtime
                    .group("consumer-group", Duration.ofSeconds(5))
                    .par("user", par, String.class, () -> "alice")
                    .par("names", par, namesType, () -> Arrays.asList("a", "b"))
                    .par("count", par, Integer.class, () -> 2)
                    .combine(
                            "summary",
                            par,
                            String.class,
                            values -> values.first().first() + ":"
                                    + values.first().second().size() + ":"
                                    + values.second())
                    .submitAll()) {

                GroupValues<Tuple2<Tuple2<String, List<String>>, Integer>> values =
                        group.valuesFuture().get(5, TimeUnit.SECONDS);
                Tuple2<Tuple2<String, List<String>>, Integer> typed = values.typedValues();
                assertEquals("alice", typed.first().first());
                assertEquals(Arrays.asList("a", "b"), typed.first().second());
                assertEquals(Integer.valueOf(2), typed.second());

                assertEquals(3, values.size());
                assertEquals("alice", values.valueOf("user", TypeToken.of(String.class)));
                assertEquals(Arrays.asList("a", "b"), values.valueAt(1, namesType));
                assertEquals("alice", group.futureOf("user", TypeToken.of(String.class)).get(5, TimeUnit.SECONDS));
                assertEquals(
                        "alice:2:2",
                        group.terminalFuture()
                                .orElseThrow(() -> new AssertionError("combine not declared"))
                                .get(5, TimeUnit.SECONDS));
                assertEquals(TaskOutcome.SUCCESS, group.completionFuture().get(5, TimeUnit.SECONDS).outcome());
            }
        } finally {
            runtime.close();
            executor.shutdownNow();
        }
    }

    @Test
    void timeoutCancelsRunningTasks() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        ParRuntime runtime = ParRuntime.builder().register(CONSUMER, executor).build();
        try {
            Par par = runtime.par(CONSUMER);
            TaskBatchResult<Integer> result = par.map(
                    Arrays.asList(1, 2),
                    value -> awaitInterruption(started, interrupted, value),
                    BatchOptions.timeout("timeout-contract", Duration.ofMillis(200))
                            .parallelism(2)
                            .taskType(TaskType.IO_BOUND));

            assertTrue(started.await(5, TimeUnit.SECONDS), "tasks did not start");
            assertThrows(
                    CancellationException.class, () -> result.results().get(0).get(5, TimeUnit.SECONDS));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "timeout did not interrupt running tasks");
            assertTrue(result.results().stream().allMatch(future -> future.isCancelled()));
        } finally {
            runtime.close();
            executor.shutdownNow();
        }
    }

    @Test
    void failureCancelsSiblingTasks() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch siblingsStarted = new CountDownLatch(2);
        CountDownLatch siblingsInterrupted = new CountDownLatch(2);
        ParRuntime runtime = ParRuntime.builder().register(CONSUMER, executor).build();
        try {
            Par par = runtime.par(CONSUMER);
            TaskBatchResult<Integer> result = par.map(
                    Arrays.asList(1, 2, 3),
                    value -> {
                        if (value == 1) {
                            if (!await(siblingsStarted, 5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("siblings did not start");
                            }
                            throw new IllegalArgumentException("expected failure");
                        }
                        return awaitInterruption(siblingsStarted, siblingsInterrupted, value);
                    },
                    BatchOptions.timeout("fail-fast-contract", Duration.ofSeconds(10))
                            .parallelism(3)
                            .taskType(TaskType.IO_BOUND));

            assertThrows(
                    ExecutionException.class, () -> result.results().get(0).get(5, TimeUnit.SECONDS));
            assertTrue(siblingsInterrupted.await(5, TimeUnit.SECONDS), "failure did not interrupt sibling tasks");
            assertTrue(result.results().get(1).isCancelled());
            assertTrue(result.results().get(2).isCancelled());
        } finally {
            runtime.close();
            executor.shutdownNow();
        }
    }

    @Test
    void outerTimeoutPropagatesToNestedTasks() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch innerStarted = new CountDownLatch(2);
        CountDownLatch innerInterrupted = new CountDownLatch(2);
        CountDownLatch releaseOuter = new CountDownLatch(1);
        AtomicReference<TaskBatchResult<Integer>> innerResult = new AtomicReference<>();
        ParRuntime runtime = ParRuntime.builder().register(CONSUMER, executor).build();
        try {
            Par par = runtime.par(CONSUMER);
            TaskBatchResult<Integer> outerResult = par.map(
                    Arrays.asList(1),
                    outerValue -> {
                        TaskBatchResult<Integer> nested = par.map(
                                Arrays.asList(10, 20),
                                value -> awaitInterruption(innerStarted, innerInterrupted, value),
                                BatchOptions.timeout("inner-contract", Duration.ofSeconds(10))
                                        .parallelism(2)
                                        .taskType(TaskType.IO_BOUND));
                        innerResult.set(nested);
                        // Stay in the body until the test releases it, swallowing the interrupt the
                        // deadline delivers: the outer element must end cancelled by the deadline,
                        // not by the body racing it with an exception of its own.
                        awaitUninterruptibly(releaseOuter);
                        return outerValue;
                    },
                    BatchOptions.timeout("outer-contract", Duration.ofMillis(300))
                            .parallelism(1)
                            .taskType(TaskType.IO_BOUND));

            assertTrue(innerStarted.await(5, TimeUnit.SECONDS), "nested tasks did not start");
            assertThrows(
                    CancellationException.class, () -> outerResult.results().get(0).get(5, TimeUnit.SECONDS));
            assertTrue(innerInterrupted.await(5, TimeUnit.SECONDS), "outer timeout did not interrupt nested tasks");
            assertTrue(innerResult.get().results().stream().allMatch(future -> future.isCancelled()));
        } finally {
            releaseOuter.countDown();
            runtime.close();
            executor.shutdownNow();
        }
    }

    private static int awaitInterruption(
            CountDownLatch started, CountDownLatch interrupted, int value) {
        started.countDown();
        try {
            new CountDownLatch(1).await(10, TimeUnit.SECONDS);
            return value;
        } catch (InterruptedException e) {
            interrupted.countDown();
            Thread.currentThread().interrupt();
            throw new RuntimeException("task interrupted", e);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean await(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("task interrupted while awaiting peers", e);
        }
    }
}
