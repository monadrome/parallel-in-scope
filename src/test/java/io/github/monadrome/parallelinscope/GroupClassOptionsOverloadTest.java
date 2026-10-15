package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code (TaskOptions, Class)} forms of {@code GroupStart.par}, {@code GroupStep.par}, and
 * {@code GroupStep.combine} apply the supplied options and declare the class as the slot's type,
 * exactly as their {@code (TaskOptions, TypeToken)} counterparts do with {@code TypeToken.of}.
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
    void theFirstMemberAppliesItsOptionsAndDeclaresTheClass() {
        long start = System.nanoTime();

        TaskGroupResult<String, Void> result = runtime.group("first", GROUP_TIMEOUT)
                .par("blocked", par, TIGHT, String.class, () -> {
                    never.await();
                    return "unreachable";
                })
                .runAll();

        assertThat(System.nanoTime() - start).isLessThan(HONORED_WITHIN_NANOS);
        assertThat(result.resultOf("blocked", String.class).outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
    }

    @Test
    void aLaterMemberAppliesItsOptionsAndDeclaresTheClass() {
        long start = System.nanoTime();

        TaskGroupResult<Tuple2<Integer, String>, Void> result = runtime.group("later", GROUP_TIMEOUT)
                .par("count", par, Integer.class, () -> 1)
                .par("blocked", par, TIGHT, String.class, () -> {
                    never.await();
                    return "unreachable";
                })
                .runAll();

        assertThat(System.nanoTime() - start).isLessThan(HONORED_WITHIN_NANOS);
        assertThat(result.resultAt(1, String.class).outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
    }

    @Test
    void theCombineAppliesItsOptionsAndDeclaresTheClass() {
        long start = System.nanoTime();

        TaskGroupResult<Integer, String> result = runtime.group("combined", GROUP_TIMEOUT)
                .par("count", par, Integer.class, () -> 1)
                .combine("join", par, TIGHT, String.class, values -> {
                    never.await();
                    return "unreachable";
                })
                .runAll();

        assertThat(System.nanoTime() - start).isLessThan(HONORED_WITHIN_NANOS);
        assertThat(result.terminalResult()).isNotNull();
        assertThat(result.terminalResult().outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        assertThat(result.resultOf("count", Integer.class).outcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    /** Passes null on purpose: the forms must reject it like the token forms do. */
    @SuppressWarnings("NullAway")
    @Test
    void theClassFormsValidateExactlyLikeTheTokenForms() {
        assertThatThrownBy(() -> runtime.group("primitive", GROUP_TIMEOUT).par("n", par, TIGHT, int.class, () -> 1))
                .isInstanceOf(IllegalArgumentException.class);
        Class<String> missing = null;
        assertThatThrownBy(() -> runtime.group("null", GROUP_TIMEOUT).par("s", par, TIGHT, missing, () -> "x"))
                .isInstanceOf(NullPointerException.class);
        TaskOptions noOptions = null;
        assertThatThrownBy(() -> runtime.group("null", GROUP_TIMEOUT)
                        .par("s", par, String.class, () -> "x")
                        .combine("j", par, noOptions, String.class, values -> "x"))
                .isInstanceOf(NullPointerException.class);
    }
}
