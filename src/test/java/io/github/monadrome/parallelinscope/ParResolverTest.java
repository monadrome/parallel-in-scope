package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ParResolverTest {

    @Test
    void runtimeIsAnImmutableReadOnlyResolver() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("io"), executor).build();
        try {
            ParResolver resolver = runtime;
            Par expected = runtime.par(ParId.of("io"));

            assertThat(resolver.find(ParId.of("io"))).contains(expected);
            assertThat(resolver.resolve(ParId.of("io"))).isSameAs(expected);
            assertThat(resolver.find(ParId.of("missing"))).isEmpty();
            assertThatThrownBy(() -> resolver.resolve(ParId.of("missing")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("no Par registered with id 'missing'");
        } finally {
            runtime.close();
            executor.shutdownNow();
        }
    }
}
