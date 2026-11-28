package io.github.monadrome.parallelinscope.assurance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.ParRuntimeSnapshot;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Candidate CONTRACT-TDD-009, contract C-5.
 *
 * <p>Precondition: a freshly built, open, idle {@link ParRuntime} with no work submitted.
 *
 * <p>Operation sequence: sample a snapshot fresh, close the runtime, then sample a snapshot again.
 *
 * <p>Observation points: the four public readings and {@code toString()} of each snapshot.
 *
 * <p>Assertions: the fresh snapshot reports {@code closed() == false} and all three counters zero;
 * the post-close snapshot reports {@code closed() == true}.
 *
 * <p>Uncertainty: only the contract-stated cases are asserted; post-close counters are not
 * cross-checked because the contract does not require consistency beyond those cases.
 */
public class ContractTdd009SnapshotIdleAndClosedTest {

    @Test
    void snapshotReportsIdleCountersThenClosed() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        ParId id = ParId.of("io");
        ParRuntime runtime = ParRuntime.builder().register(id, pool).defaultPar(id).build();
        try {
            ParRuntimeSnapshot fresh = runtime.snapshot();
            assertThat(fresh.closed()).isFalse();
            assertThat(fresh.activeAdmissions()).isZero();
            assertThat(fresh.undrainedBatches()).isZero();
            assertThat(fresh.unexitedExecutionSignals()).isZero();
            assertThat(fresh.toString()).isNotNull();

            runtime.close();

            ParRuntimeSnapshot closed = runtime.snapshot();
            assertThat(closed.closed()).isTrue();
            assertThat(closed.toString()).isNotNull();
        } finally {
            runtime.close();
            pool.shutdownNow();
        }
    }
}
