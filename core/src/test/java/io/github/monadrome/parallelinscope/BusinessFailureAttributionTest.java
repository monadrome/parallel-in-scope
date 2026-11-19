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
 * A business {@link TimeoutException} thrown by a task execution is a user failure, not the framework
 * deadline: the group must converge on the recorded failure's outcome with the original throwable,
 * and unfinished siblings must read {@link CancellationReason.Kind#ABORTED_BY_FAIL_FAST} — never
 * {@link CancellationReason.Kind#DEADLINE_EXCEEDED} while the group deadline has not expired.
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

            TaskGroupResult<Void> result = global.group("load", Duration.ofSeconds(30))
                    .par(TaskKey.<Integer>named("failing"), global.par(ParId.of("worker")), () -> {
                        // Wait until the sibling is mid-flight so the cascade reaches a running execution.
                        assertThat(siblingStarted.await(30, TimeUnit.SECONDS)).isTrue();
                        throw businessFailure;
                    })
                    .par(TaskKey.<Integer>named("sibling"), global.par(ParId.of("worker")), () -> {
                        siblingStarted.countDown();
                        Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                        return 2;
                    })
                    .runAll();

            TerminationAssertions.assertScopeFailed(result.termination());
            assertThat(result.failedTaskName()).isEqualTo("failing");
            assertThat(result.valuesResult().failure()).isSameAs(businessFailure);
            ImmediateResult<?> failing = result.resultOf("failing");
            TerminationAssertions.assertUserFailure(failing.termination());
            assertThat(failing.failure()).isSameAs(businessFailure);
            ImmediateResult<?> sibling = result.resultOf("sibling");
            TerminationAssertions.assertCancelled(sibling.termination(), CancellationReason.Kind.ABORTED_BY_FAIL_FAST);
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

            TaskGroupResult<Integer> result = global.group("load", Duration.ofSeconds(30))
                    .par(TaskKey.<Integer>named("left"), global.par(ParId.of("worker")), () -> 1)
                    .par(TaskKey.<Integer>named("right"), global.par(ParId.of("worker")), () -> 2)
                    .combineValues(TaskKey.<Integer>named("join"), global.par(ParId.of("worker")), values -> {
                        throw businessFailure;
                    })
                    .runAll();

            TerminationAssertions.assertScopeFailed(result.termination());
            assertThat(result.failedTaskName()).isEqualTo("join");
            ImmediateResult<?> terminal = result.terminalResult();
            assertThat(terminal).isNotNull();
            TerminationAssertions.assertUserFailure(terminal.termination());
            assertThat(terminal.failure()).isSameAs(businessFailure);
        } finally {
            executor.shutdownNow();
        }
    }
}
