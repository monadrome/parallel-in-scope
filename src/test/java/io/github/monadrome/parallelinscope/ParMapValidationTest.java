package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ParMapValidationTest {
    static Stream<Arguments> inputs() {
        return Stream.of(
                Arguments.of(Collections.singletonList(1)), Arguments.of(Collections.emptyList()), Arguments.of((Object)
                        null));
    }

    @ParameterizedTest
    @MethodSource("inputs")
    @SuppressWarnings("NullAway")
    void nullMapperIsRejectedForNonemptyEmptyAndNullInputs(@Nullable List<Integer> input) {
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("worker"), executor).build()) {
            Par par = runtime.par(ParId.of("worker"));
            BatchOptions options = BatchOptions.timeout("batch", Duration.ofSeconds(5));
            assertThatThrownBy(() -> par.map(input, null, options))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("function cannot be null");
        } finally {
            executor.shutdownNow();
        }
    }
}
