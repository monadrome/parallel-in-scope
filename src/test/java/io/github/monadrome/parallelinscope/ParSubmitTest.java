package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/** Contract tests for {@link Par#submit(String, java.util.concurrent.Callable, TaskOptions)}. */
class ParSubmitTest {

    @Test
    void submitRunsOneScopedTaskWithNameAndDeadline() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<TaskCompletion<?>> listenerCompletion = new AtomicReference<>();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("worker"), executor)
                .taskListener(listenerCompletion::set)
                .build();
        try {
            TaskFuture<String> task = global.par(ParId.of("worker"))
                    .submit(
                            "single",
                            () -> {
                                MultiTaskContext unit = Objects.requireNonNull(TaskExecutionContext.current())
                                        .multiTaskContext();
                                assertThat(unit.name()).isEqualTo("single");
                                return "done";
                            },
                            TaskOptions.timeout(Duration.ofSeconds(30)));

            assertThat(task.get(2, TimeUnit.SECONDS)).isEqualTo("done");
            assertThat(task.taskName()).isEqualTo("single");
            assertThat(task.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(Objects.requireNonNull(listenerCompletion.get()).taskName())
                    .isEqualTo("single");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void submitRunsOnTheCallerThreadWhenOptionsRequestIt() throws Exception {
        ExecutorService rejected = Executors.newSingleThreadExecutor();
        rejected.shutdownNow();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), rejected).build();
        Thread caller = Thread.currentThread();
        try {
            TaskFuture<Thread> task = global.par(ParId.of("worker"))
                    .submit(
                            "inline",
                            Thread::currentThread,
                            TaskOptions.timeout(Duration.ofSeconds(30)).runOnCallerThread(true));

            assertThat(task.get(2, TimeUnit.SECONDS)).isSameAs(caller);
            assertThat(task.outcome()).isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            global.close();
        }
    }

    /**
     * The default: a rejected task fails without entering user code, for every task type.
     * {@code CPU_BOUND} is the default type, so this is what an unconfigured task does.
     */
    @Test
    void submitFailsWithoutRunningItsBodyWhenRejectedByDefault() throws Exception {
        ExecutorService rejected = Executors.newSingleThreadExecutor();
        rejected.shutdownNow();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), rejected).build();
        AtomicReference<Boolean> bodyRan = new AtomicReference<>(false);
        try {
            TaskFuture<String> task = global.par(ParId.of("worker"))
                    .submit(
                            "rejected",
                            () -> {
                                bodyRan.set(true);
                                return "ran";
                            },
                            TaskOptions.timeout(Duration.ofSeconds(30)));

            assertThatThrownBy(() -> task.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(SubmissionException.class);
            assertThat(task.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(bodyRan.get()).isFalse();
        } finally {
            global.close();
        }
    }

    @Test
    void submitRequiresAnExplicitTimeoutWithoutAnEnclosingScope() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            Par par = global.par(ParId.of("worker"));
            assertThatThrownBy(() -> par.submit("single", () -> "x", TaskOptions.inheritTimeout()))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void submitDeadlineExpiresAndCancelsTheTask() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            CountDownLatch started = new CountDownLatch(1);
            TaskFuture<String> task = global.par(ParId.of("worker"))
                    .submit(
                            "slow",
                            () -> {
                                started.countDown();
                                while (true) {
                                    Checkpoints.checkpoint();
                                }
                            },
                            TaskOptions.timeout(Duration.ofMillis(50)).taskType(TaskType.IO_BOUND));

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> task.get(2, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
            assertThat(task.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void submitInsideABatchInheritsCancellation() throws Exception {
        ExecutorService outer = Executors.newSingleThreadExecutor();
        ExecutorService inner = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("outer"), outer)
                .register(ParId.of("inner"), inner)
                .build();
        try {
            TaskBatchResult<String> batch = global.par(ParId.of("outer"))
                    .map(
                            Arrays.asList("a"),
                            ignored ->
                                    com.google.common.util.concurrent.Futures.getUnchecked(global.par(ParId.of("inner"))
                                            .submit("nested", () -> "nested-value", TaskOptions.inheritTimeout())),
                            BatchOptions.timeout("outer-batch", Duration.ofSeconds(30)));

            assertThat(batch.valuesOrThrow()).containsExactly("nested-value");
        } finally {
            global.close();
            outer.shutdownNow();
            inner.shutdownNow();
        }
    }

    @Test
    void submitRejectsAfterClose() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        global.close();
        try {
            Par par = global.par(ParId.of("worker"));
            assertThatThrownBy(() -> par.submit("single", () -> "x", TaskOptions.timeout(Duration.ofSeconds(1))))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void inertRejectEnqueueIsReportedOncePerParAndNeverForAQueueThatHonoursIt() throws Exception {
        ExecutorService plain = Executors.newFixedThreadPool(2);
        ThreadPoolExecutor smart = new ThreadPoolExecutor(1, 2, 0L, TimeUnit.MILLISECONDS, new SmartBlockingQueue<>(4));
        ParRuntime global = ParRuntime.builder()
                // Two Par names on one physical pool: the diagnostic is per Par, not per executor.
                .register(ParId.of("plain"), plain)
                .register(ParId.of("plain-two"), plain)
                .register(ParId.of("smart"), smart)
                .build();
        Logger parLogger = Logger.getLogger(Par.class.getName());
        List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        parLogger.addHandler(capture);
        try {
            Par par = global.par(ParId.of("plain"));
            // The option is off for this call, so there is nothing to report yet.
            par.submit(
                            "silent",
                            () -> "a",
                            TaskOptions.timeout(Duration.ofSeconds(30)).rejectEnqueue(false))
                    .get(2, TimeUnit.SECONDS);
            assertThat(records).isEmpty();

            // A fixed pool's default queue never reads the flag: say so once, not once per task.
            par.submit("loud", () -> "b", TaskOptions.timeout(Duration.ofSeconds(30)))
                    .get(2, TimeUnit.SECONDS);
            par.submit("loud-again", () -> "c", TaskOptions.timeout(Duration.ofSeconds(30)))
                    .get(2, TimeUnit.SECONDS);
            assertThat(records).hasSize(1);
            assertThat(records.get(0).getLevel()).isEqualTo(Level.WARNING);
            assertThat(records.get(0).getMessage()).contains("plain", "inert");

            global.par(ParId.of("plain-two"))
                    .submit("loud-other", () -> "d", TaskOptions.timeout(Duration.ofSeconds(30)))
                    .get(2, TimeUnit.SECONDS);
            assertThat(records).hasSize(2);

            // A SmartBlockingQueue reads the flag, so the caller is not warned about an inert one.
            // runOnCallerThread keeps the run deterministic: this queue rejects the queued task.
            global.par(ParId.of("smart"))
                    .submit(
                            "quiet",
                            () -> "e",
                            TaskOptions.timeout(Duration.ofSeconds(30)).runOnCallerThread(true))
                    .get(2, TimeUnit.SECONDS);
            assertThat(records).hasSize(2);
        } finally {
            parLogger.removeHandler(capture);
            global.close();
            plain.shutdownNow();
            smart.shutdownNow();
        }
    }
}
