package io.github.monadrome.parallelinscope.assurance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskKey;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Candidate CONTRACT-TDD-002, contract C-2.
 *
 * <p>Precondition: a built {@link ParRuntime} with one registered executor, open and idle.
 *
 * <p>Operation sequence: close the runtime, close it a second time, then attempt a {@code Par.map}
 * batch and a group {@code runAll()} on the closed runtime.
 *
 * <p>Observation points: {@code closed()} after each close; the two post-close submission attempts.
 *
 * <p>Assertions: the first and second close both leave {@code closed() == true} and neither throws;
 * every later batch or group start throws {@code IllegalStateException}.
 *
 * <p>Uncertainty: "single-task submission" has no dedicated public API in the sanitized surface, so
 * rejection is exercised through a one-element {@code Par.map} batch and a single-member group.
 */
public class ContractTdd002CloseRejectsAndIsIdempotentTest {

    @Test
    void closeRejectsNewWorkAndIsIdempotent() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        ParId id = ParId.of("io");
        ParRuntime runtime = ParRuntime.builder().register(id, pool).defaultPar(id).build();
        Par par = runtime.defaultPar();
        try {
            runtime.close();
            assertThat(runtime.closed()).isTrue();

            assertThatCode(runtime::close).doesNotThrowAnyException();
            assertThat(runtime.closed()).isTrue();

            assertThatThrownBy(() -> par.map(List.of(1), x -> x,
                    BatchOptions.timeout("b", Duration.ofSeconds(1))))
                    .isInstanceOf(IllegalStateException.class);

            TaskKey<Integer> member = TaskKey.named("member");
            assertThatThrownBy(() -> runtime.group("g", Duration.ofSeconds(1))
                    .par(member, par, () -> 1)
                    .runAll())
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            runtime.close();
            pool.shutdownNow();
        }
    }
}
