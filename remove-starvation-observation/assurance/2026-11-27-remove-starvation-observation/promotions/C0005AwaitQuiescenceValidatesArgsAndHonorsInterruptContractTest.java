package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Candidate CONTRACT-TDD-004, contract C-3.
 *
 * <p>Precondition: a built, idle {@link ParRuntime}; the test closes it before the interrupt probe
 * so the runtime is quiescent and the timeout is non-positive.
 *
 * <p>Operation sequence: call {@code awaitQuiescence(null)}; close; interrupt the calling thread;
 * call {@code awaitQuiescence(Duration.ZERO)}; clear the interrupt; call it again.
 *
 * <p>Observation points: the null-argument call, the interrupted call's exception and flag state,
 * and the final non-positive single check.
 *
 * <p>Assertions: null throws {@code NullPointerException}; the interrupted call throws
 * {@code InterruptedException} with the flag cleared even though the runtime is quiescent and the
 * timeout is non-positive; the subsequent non-positive check returns {@code true}.
 *
 * <p>Uncertainty: none material; validation order is stated by the contract.
 */
@Tag("C-0005")
class C0005AwaitQuiescenceValidatesArgsAndHonorsInterruptContractTest {

    @Test
    void awaitQuiescenceValidatesNullAndHonorsInterruptBeforeState() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        ParId id = ParId.of("io");
        ParRuntime runtime =
                ParRuntime.builder().register(id, pool).defaultPar(id).build();
        try {
            assertThatThrownBy(() -> runtime.awaitQuiescence(null)).isInstanceOf(NullPointerException.class);

            runtime.close();

            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> runtime.awaitQuiescence(Duration.ZERO))
                        .isInstanceOf(InterruptedException.class);
                assertThat(Thread.currentThread().isInterrupted()).isFalse();
            } finally {
                Thread.interrupted();
            }

            assertThat(runtime.awaitQuiescence(Duration.ZERO)).isTrue();
        } finally {
            runtime.close();
            pool.shutdownNow();
        }
    }
}
