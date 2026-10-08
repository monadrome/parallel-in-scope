package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TaskGroupResult#failedTaskResult()}: the recorded failure's result container, whether the
 * failed task is a plain member or the terminal combine.
 */
// JUnit-style: 'runtime' is assigned inside each test method, not in a constructor or @BeforeEach.
@SuppressWarnings("NullAway.Init")
class TaskGroupFailedTaskResultTest {

    private ParRuntime runtime;

    @AfterEach
    void cleanUp() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void successfulGroupHasNoFailedTaskResult() {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        runtime = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroupResult<Integer, Void> result = runtime.group("ok", Duration.ofSeconds(30))
                    .par("only", runtime.par(ParId.of("worker")), Integer.class, () -> 1)
                    .runAll();
            assertThat(result.failedTaskName()).isNull();
            assertThat(result.failedTaskResult()).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void memberFailureResolvesToTheMemberResult() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        runtime = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            IllegalStateException boom = new IllegalStateException("boom");
            TaskGroupResult<Tuple2<Integer, Integer>, Void> result = runtime.group(
                            "member-fails", Duration.ofSeconds(30))
                    .par("ok", runtime.par(ParId.of("worker")), Integer.class, () -> 1)
                    .par("bad", runtime.par(ParId.of("worker")), Integer.class, () -> {
                        throw boom;
                    })
                    .runAll();

            assertThat(result.failedTaskName()).isEqualTo("bad");
            Optional<ImmediateResult<?>> failed = result.failedTaskResult();
            assertThat(failed).isPresent();
            assertThat(failed.get().outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(failed.get().failure()).isSameAs(boom);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void combineFailureResolvesToTheTerminalResult() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        runtime = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            IllegalStateException boom = new IllegalStateException("combine boom");
            TaskGroupResult<Integer, Integer> result = runtime.group("combine-fails", Duration.ofSeconds(30))
                    .par("ok", runtime.par(ParId.of("worker")), Integer.class, () -> 1)
                    .combine("join", runtime.par(ParId.of("worker")), Integer.class, values -> {
                        throw boom;
                    })
                    .runAll();

            assertThat(result.failedTaskName()).isEqualTo("join");
            Optional<ImmediateResult<?>> failed = result.failedTaskResult();
            assertThat(failed).isPresent();
            assertThat(failed.get().outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(failed.get().failure()).isSameAs(boom);
        } finally {
            executor.shutdownNow();
        }
    }
}
