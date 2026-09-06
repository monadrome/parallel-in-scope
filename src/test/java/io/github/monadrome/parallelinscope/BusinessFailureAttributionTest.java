package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A business {@link TimeoutException} thrown by a task body is a user failure, not the framework
 * deadline: the group must converge on the recorded failure's outcome with the original throwable,
 * and unfinished siblings must read {@link TaskOutcome#FAIL_FAST} — never {@link
 * TaskOutcome#TIMEOUT} while the group deadline has not expired.
 */
// JUnit-style: 'global' is assigned inside each test method, not in a constructor or @BeforeEach.
@SuppressWarnings("NullAway.Init")
class BusinessFailureAttributionTest {

    private ParRuntime global;

    @AfterEach
    void cleanUp() {
        if (global != null) {
            global.close();
        }
    }

    @Test
    void businessTimeoutExceptionFromAMemberIsNotAFrameworkTimeout() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        global = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            CountDownLatch siblingStarted = new CountDownLatch(1);
            TimeoutException businessFailure = new TimeoutException("business-level timeout");

            TaskGroupResult<Tuple2<Integer, Integer>, Void> result = global.group("load", Duration.ofSeconds(30))
                    .par("failing", global.par(ParId.of("worker")), Integer.class, () -> {
                        // Wait until the sibling is mid-flight so the cascade reaches a running body.
                        assertThat(siblingStarted.await(30, TimeUnit.SECONDS)).isTrue();
                        throw businessFailure;
                    })
                    .par("sibling", global.par(ParId.of("worker")), Integer.class, () -> {
                        siblingStarted.countDown();
                        Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                        return 2;
                    })
                    .runAll();

            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("failing");
            assertThat(result.valuesResult().failure()).isSameAs(businessFailure);
            ImmediateResult<?> failing = result.resultOf("failing");
            assertThat(failing.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(failing.failure()).isSameAs(businessFailure);
            ImmediateResult<?> sibling = result.resultOf("sibling");
            assertThat(sibling.outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void businessTimeoutExceptionFromTheCombineIsNotAFrameworkTimeout() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        global = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TimeoutException businessFailure = new TimeoutException("business-level timeout");

            TaskGroupResult<Tuple2<Integer, Integer>, Integer> result = global.group("load", Duration.ofSeconds(30))
                    .par("left", global.par(ParId.of("worker")), Integer.class, () -> 1)
                    .par("right", global.par(ParId.of("worker")), Integer.class, () -> 2)
                    .combine("join", global.par(ParId.of("worker")), Integer.class, values -> {
                        throw businessFailure;
                    })
                    .runAll();

            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("join");
            ImmediateResult<?> terminal = result.terminalResult();
            assertThat(terminal).isNotNull();
            assertThat(terminal.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(terminal.failure()).isSameAs(businessFailure);
        } finally {
            executor.shutdownNow();
        }
    }
}
