package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Keyed declarations apply explicit task options to first members, later members, and combines.
 *
 * <p>The options are observed through a member timeout far tighter than the group's: honored, it
 * ends the run within milliseconds; dropped in favor of the inherited deadline, the run would last
 * the group's 30 seconds, which the elapsed-time bound rejects.
 */
@SuppressWarnings("NullAway.Init")
class GroupClassOptionsOverloadTest {

    private static final Duration GROUP_TIMEOUT = Duration.ofSeconds(30);
    private static final TaskOptions TIGHT = TaskOptions.timeout(Duration.ofMillis(100));
    private static final long HONORED_WITHIN_NANOS = Duration.ofSeconds(10).toNanos();

    private final CountDownLatch never = new CountDownLatch(1);
    private ExecutorService pool;
    private ParRuntime runtime;
    private Par par;

    @BeforeEach
    void start() {
        pool = Executors.newFixedThreadPool(4);
        runtime = ParRuntime.builder().register(ParId.of("worker"), pool).build();
        par = runtime.par(ParId.of("worker"));
    }

    @AfterEach
    void stop() {
        runtime.close();
        pool.shutdownNow();
    }

    @Test
    void theFirstKeyedMemberAppliesItsOptions() {
        long start = System.nanoTime();

        TaskGroupResult<String, Void> result = runtime.group("first", GROUP_TIMEOUT)
                .par(TaskKey.<String>named("blocked"), par, TIGHT, () -> {
                    never.await();
                    return "unreachable";
                })
                .runAll();

        assertThat(System.nanoTime() - start).isLessThan(HONORED_WITHIN_NANOS);
        assertThat(result.resultOf("blocked").outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
    }

    @Test
    void aLaterKeyedMemberAppliesItsOptions() {
        long start = System.nanoTime();

        TaskGroupResult<Tuple2<Integer, String>, Void> result = runtime.group("later", GROUP_TIMEOUT)
                .par(TaskKey.<Integer>named("count"), par, () -> 1)
                .par(TaskKey.<String>named("blocked"), par, TIGHT, () -> {
                    never.await();
                    return "unreachable";
                })
                .runAll();

        assertThat(System.nanoTime() - start).isLessThan(HONORED_WITHIN_NANOS);
        assertThat(result.resultAt(1).outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
    }

    @Test
    void theKeyedCombineAppliesItsOptions() {
        long start = System.nanoTime();

        TaskGroupResult<Integer, String> result = runtime.group("combined", GROUP_TIMEOUT)
                .par(TaskKey.<Integer>named("count"), par, () -> 1)
                .combine(TaskKey.<String>named("join"), par, TIGHT, values -> {
                    never.await();
                    return "unreachable";
                })
                .runAll();

        assertThat(System.nanoTime() - start).isLessThan(HONORED_WITHIN_NANOS);
        assertThat(result.terminalResult()).isNotNull();
        assertThat(result.terminalResult().outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        assertThat(result.resultOf("count").outcome()).isEqualTo(TaskOutcome.SUCCESS);
    }
}
