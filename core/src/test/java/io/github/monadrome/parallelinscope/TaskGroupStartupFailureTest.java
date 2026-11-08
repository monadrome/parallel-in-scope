package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A group whose startup fails after preparation (here: its cancellation signal refuses the listener)
 * throws from {@code runAll}, runs no execution, and leaves nothing behind that would keep the runtime
 * from quiescing.
 */
class TaskGroupStartupFailureTest {

    private static final ParId WORKER = ParId.of("worker");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private ExecutorService executor;
    private ParRuntime runtime;

    /** A caller-owned signal whose {@code addListener} throws, failing admission after preparation. */
    private static final class RefusingSignal extends AbstractFuture<Object> {
        @Override
        public void addListener(Runnable listener, Executor executor) {
            throw new IllegalStateException("addListener refused");
        }
    }

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        runtime = ParRuntime.builder().register(WORKER, executor).build();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
        executor.shutdownNow();
    }

    @Test
    void failedStartupRunsNoExecutionAndLetsTheRuntimeQuiesce() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();

        assertThatThrownBy(() -> runtime.group("refused", TIMEOUT)
                        .cancelOn(new RefusingSignal())
                        .par(TaskKey.<Integer>named("only"), runtime.par(WORKER), () -> {
                            ran.set(true);
                            return 1;
                        })
                        .runAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("addListener refused");

        runtime.close();
        assertThat(runtime.awaitQuiescence(Duration.ofSeconds(2))).isTrue();
        assertThat(ran).isFalse();
    }

    @Test
    void failedStartupCancelsAnAdoptedSourceAndLetsTheRuntimeQuiesce() throws Exception {
        SettableFuture<Integer> source = SettableFuture.create();

        assertThatThrownBy(() -> runtime.group("refused-adopted", TIMEOUT)
                        .cancelOn(new RefusingSignal())
                        .adopt(TaskKey.<Integer>named("adopted"), source)
                        .runAll())
                .hasMessage("addListener refused");

        assertThat(source.isCancelled()).isTrue();
        runtime.close();
        assertThat(runtime.awaitQuiescence(Duration.ofSeconds(2))).isTrue();
    }
}
