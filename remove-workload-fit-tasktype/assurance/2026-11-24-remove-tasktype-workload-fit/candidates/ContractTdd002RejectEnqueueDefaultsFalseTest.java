package io.github.monadrome.parallelinscope.assurance;

import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.TaskOptions;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRACT-TDD-002 (contract C-2).
 *
 * <p>Precondition: none beyond the library on the class path; no executor or runtime is needed.
 * Operation sequence: build default {@code BatchOptions} and {@code TaskOptions}, read their
 * {@code rejectEnqueue()} values, then set the flag and read again.
 *
 * <p>Observation points: the boolean returned by {@code BatchOptions.rejectEnqueue()} and
 * {@code TaskOptions.rejectEnqueue()}.
 *
 * <p>Assertions: both default to {@code false} unless set; both report {@code true} after
 * {@code rejectEnqueue(true)} and {@code false} after {@code rejectEnqueue(false)}.
 *
 * <p>Uncertainty: none.
 */
public class ContractTdd002RejectEnqueueDefaultsFalseTest {

    @Test
    void rejectEnqueueDefaultsToFalse() {
        BatchOptions batch = BatchOptions.timeout("ct002-batch", Duration.ofSeconds(30));
        TaskOptions task = TaskOptions.timeout(Duration.ofSeconds(30));

        assertThat(batch.rejectEnqueue()).isFalse();
        assertThat(task.rejectEnqueue()).isFalse();

        assertThat(batch.rejectEnqueue(true).rejectEnqueue()).isTrue();
        assertThat(task.rejectEnqueue(true).rejectEnqueue()).isTrue();
        assertThat(batch.rejectEnqueue(false).rejectEnqueue()).isFalse();
        assertThat(task.rejectEnqueue(false).rejectEnqueue()).isFalse();
    }
}
