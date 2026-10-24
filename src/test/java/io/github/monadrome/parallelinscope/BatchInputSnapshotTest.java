package io.github.monadrome.parallelinscope;

import static com.google.common.base.Verify.verifyNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Input snapshots keep sequential collections linear and reject null elements before execution. */
class BatchInputSnapshotTest {

    /** Both batch entry points traverse sequential inputs without indexed reads. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sequentialInputsAreSnapshottedBeforePreparingTasks(boolean combined) {
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("worker"), executor).build()) {
            SequentialInput input = new SequentialInput();
            input.addAll(Arrays.asList(1, 2, 3));
            Par par = runtime.par(ParId.of("worker"));
            BatchOptions options = BatchOptions.timeout("snapshot", Duration.ofSeconds(5));
            TaskBatchResult<Integer> result;
            if (combined) {
                BatchCombinedResult<Integer, Integer> execution = par.mapAndCombine(
                        input,
                        value -> value * 2,
                        options,
                        par,
                        values -> verifyNotNull(values).size());
                assertThat(execution.terminalValueOrThrow()).isEqualTo(3);
                result = execution.batchResult();
            } else {
                result = par.map(input, value -> value * 2, options);
            }
            assertThat(result.valuesOrThrow()).containsExactly(2, 4, 6);
            assertThat(input.indexedReads).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    /** Null input elements cannot execute any mapper or terminal body in either entry point. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("NullAway")
    void nullElementsAreRejectedBeforeAnyTaskRuns(boolean combined) {
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("worker"), executor).build()) {
            Par par = runtime.par(ParId.of("worker"));
            List<Integer> input = Arrays.asList(1, null, 3);
            AtomicInteger bodies = new AtomicInteger();
            BatchOptions options = BatchOptions.timeout("null-input", Duration.ofSeconds(5));
            assertThatThrownBy(() -> {
                        if (combined) {
                            par.mapAndCombine(
                                    input,
                                    value -> bodies.incrementAndGet(),
                                    options,
                                    par,
                                    values -> bodies.incrementAndGet());
                        } else {
                            par.map(input, value -> bodies.incrementAndGet(), options);
                        }
                    })
                    .isInstanceOf(NullPointerException.class);
            assertThat(bodies).hasValue(0);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Linked input that records indexed traversal without changing its collection behavior. */
    private static final class SequentialInput extends LinkedList<Integer> {
        private static final long serialVersionUID = 1L;
        private int indexedReads;

        /** Records accesses that would require a linked-list walk for every prepared element. */
        @Override
        public Integer get(int index) {
            indexedReads++;
            return super.get(index);
        }
    }
}
