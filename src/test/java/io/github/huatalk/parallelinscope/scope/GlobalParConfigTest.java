package io.github.huatalk.parallelinscope.scope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalParConfigTest {

    @BeforeEach
    void isolateGlobalConfig() {
        GlobalParConfig.resetForTest();
    }

    @AfterEach
    void resetGlobalConfig() {
        GlobalParConfig.resetForTest();
    }

    @Test
    void explicitConfigCanBeInstalledBeforeFirstGlobalRead() {
        ParConfig config = configWithTimeout(1234);

        GlobalParConfig.initializeDefault(config);

        assertThat(GlobalParConfig.get()).isSameAs(config);
    }

    @Test
    void globalConfigCanOnlyBeInstalledOnce() {
        ParConfig first = configWithTimeout(1000);
        ParConfig second = configWithTimeout(2000);
        GlobalParConfig.initializeDefault(first);

        assertThatThrownBy(() -> GlobalParConfig.initializeDefault(second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already initialized");
        assertThat(GlobalParConfig.get()).isSameAs(first);
    }

    @Test
    void firstGlobalReadInstallsBuiltInDefaultAndPreventsLateConfiguration() {
        ParConfig builtIn = GlobalParConfig.get();

        assertThat(builtIn).isSameAs(GlobalParConfig.get());
        assertThatThrownBy(() -> GlobalParConfig.initializeDefault(configWithTimeout(2000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already initialized");
        assertThat(GlobalParConfig.get()).isSameAs(builtIn);
    }

    @Test
    void rejectedNullDoesNotInitializeGlobalConfig() {
        ParConfig config = configWithTimeout(1234);

        assertThatNullPointerException()
                .isThrownBy(() -> GlobalParConfig.initializeDefault(null))
                .withMessage("config");

        GlobalParConfig.initializeDefault(config);
        assertThat(GlobalParConfig.get()).isSameAs(config);
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedParConfigMethodsUseTheSameGlobalState() {
        ParConfig config = configWithTimeout(1234);

        ParConfig.setDefault(config);

        assertThat(ParConfig.getDefault()).isSameAs(config);
        assertThat(GlobalParConfig.get()).isSameAs(config);
        assertThatThrownBy(() -> ParConfig.setDefault(configWithTimeout(2000)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concurrentInstallersHaveExactlyOneWinner() throws Exception {
        int contenderCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(contenderCount);
        CountDownLatch ready = new CountDownLatch(contenderCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicReference<ParConfig> winner = new AtomicReference<>();
        List<Future<?>> attempts = new ArrayList<>();

        try {
            for (int index = 0; index < contenderCount; index++) {
                ParConfig contender = configWithTimeout(1000 + index);
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        GlobalParConfig.initializeDefault(contender);
                        successes.incrementAndGet();
                        winner.set(contender);
                    } catch (IllegalStateException expected) {
                        // A competing initializer won.
                    }
                    return null;
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(5, TimeUnit.SECONDS);
            }

            assertThat(successes).hasValue(1);
            assertThat(GlobalParConfig.get()).isSameAs(winner.get());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static ParConfig configWithTimeout(long timeoutMillis) {
        return ParConfig.builder()
                .defaultTimeoutMillis(timeoutMillis)
                .build();
    }
}
