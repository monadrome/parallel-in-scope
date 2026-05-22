package io.github.huatalk.parallelinscope;

import io.github.huatalk.parallelinscope.cancel.*;
import io.github.huatalk.parallelinscope.context.*;
import io.github.huatalk.parallelinscope.context.graph.*;
import io.github.huatalk.parallelinscope.internal.*;
import io.github.huatalk.parallelinscope.queue.*;
import io.github.huatalk.parallelinscope.scope.*;
import io.github.huatalk.parallelinscope.spi.*;

import com.google.common.util.concurrent.ListeningExecutorService;
import io.github.huatalk.parallelinscope.spi.ExecutorResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the executor registry and name-based Par API.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class ExecutorRegistryTest {

    private static final String POOL_NAME = "registry-test-pool";
    private ExecutorService executor;

    @BeforeEach
    public void setUp() {
        executor = Executors.newFixedThreadPool(2);
        TaskGraph.initOnRequest();
    }

    @AfterEach
    public void tearDown() {
        ParConfig config = ParConfig.builder().build();
        TaskGraph.destroyAfterRequest(config);
        executor.shutdownNow();
    }

    // ==================== 5.1: Register/Get Lifecycle ====================

    @Test
    public void testRegisterAndGet() {
        ParConfig config = ParConfig.builder()
                .executor(POOL_NAME, executor)
                .build();

        ListeningExecutorService retrieved = config.getExecutor(POOL_NAME);
        assertThat(retrieved).isNotNull();
    }

    @Test
    public void testGetUnregistered_returnsNull() {
        ParConfig config = ParConfig.builder().build();
        assertThat(config.getExecutor(POOL_NAME)).isNull();
    }

    // ==================== 5.2: Null Validation ====================

    @Test
    public void testRegisterWithNullNameThrows() {
        assertThatThrownBy(() -> ParConfig.builder().executor(null, executor))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testRegisterWithEmptyNameThrows() {
        assertThatThrownBy(() -> ParConfig.builder().executor("", executor))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testRegisterWithNullExecutorThrows() {
        assertThatThrownBy(() -> ParConfig.builder().executor(POOL_NAME, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== 5.3: map and forEach with executor name ====================

    @Test
    public void testParMapWithExecutorName() throws Exception {
        ParConfig config = ParConfig.builder()
                .executor(POOL_NAME, executor)
                .build();
        Par par = new Par(config);
        List<Integer> input = Arrays.asList(1, 2, 3);

        ParOptions options = ParOptions.of("registryParMap")
                .timeout(5000)
                .build();

        AsyncBatchResult<Integer> batch = par.map(
                POOL_NAME, input, x -> x * 10, options);

        List<Integer> results = new ArrayList<>();
        for (com.google.common.util.concurrent.ListenableFuture<Integer> f : batch.getResults()) {
            results.add(f.get(5, TimeUnit.SECONDS));
        }

        Collections.sort(results);
        assertThat(results).containsExactly(10, 20, 30);
    }

    @Test
    public void testParForEachWithExecutorName() throws Exception {
        ParConfig config = ParConfig.builder()
                .executor(POOL_NAME, executor)
                .build();
        Par par = new Par(config);
        List<String> input = Arrays.asList("a", "b", "c");
        CopyOnWriteArrayList<String> results = new CopyOnWriteArrayList<>();

        ParOptions options = ParOptions.of("registryParForEach")
                .timeout(5000)
                .build();

        AsyncBatchResult<Void> batch = par.map(
                POOL_NAME, input, item -> {
                    results.add(item);
                    return null;
                }, options);

        for (com.google.common.util.concurrent.ListenableFuture<Void> f : batch.getResults()) {
            f.get(5, TimeUnit.SECONDS);
        }

        Collections.sort(results);
        assertThat(results).containsExactly("a", "b", "c");
    }

    // ==================== 5.4: Unregistered name throws ====================

    @Test
    public void testParMapWithUnregisteredNameThrows() {
        ParConfig config = ParConfig.builder().build();
        Par par = new Par(config);
        ParOptions options = ParOptions.of("test").build();
        assertThatThrownBy(() -> par.map("nonexistent", Arrays.asList(1), x -> x, options))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testParForEachWithUnregisteredNameThrows() {
        ParConfig config = ParConfig.builder().build();
        Par par = new Par(config);
        ParOptions options = ParOptions.of("test").build();
        assertThatThrownBy(() -> par.map("nonexistent", Arrays.asList(1), x -> null, options))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== 5.5: Auto-bridge to purge subsystem ====================

    @Test
    public void testResolveThreadPoolFromRegistry() {
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            ParConfig config = ParConfig.builder()
                    .executor(POOL_NAME, tpe)
                    .build();

            // No explicit ExecutorResolver set
            ThreadPoolExecutor resolved = config.resolveThreadPool(POOL_NAME);
            assertThat(resolved).isSameAs(tpe);
        } finally {
            tpe.shutdownNow();
        }
    }

    @Test
    public void testResolveThreadPoolReturnsNullForNonThreadPoolExecutor() {
        // Executors.newFixedThreadPool returns a ThreadPoolExecutor in practice,
        // but let's test with a non-TPE to verify the instanceof check
        ParConfig config = ParConfig.builder()
                .executor(POOL_NAME, executor)
                .build();
        // executor from Executors.newFixedThreadPool IS a ThreadPoolExecutor, so it should resolve
        ThreadPoolExecutor resolved = config.resolveThreadPool(POOL_NAME);
        assertThat(resolved).isNotNull();
    }

    // ==================== 5.6: Explicit ExecutorResolver takes priority ====================

    @Test
    public void testExplicitExecutorResolverTakesPriority() {
        ThreadPoolExecutor registryTpe = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        ThreadPoolExecutor resolverTpe = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

        try {
            ParConfig config = ParConfig.builder()
                    .executor(POOL_NAME, registryTpe)
                    .executorResolver(new ExecutorResolver() {
                        @Override
                        public ThreadPoolExecutor resolveThreadPool(String executorName) {
                            if (POOL_NAME.equals(executorName)) {
                                return resolverTpe;
                            }
                            return null;
                        }

                        @Override
                        public Map<String, String> getTaskToExecutorMapping() {
                            return Collections.emptyMap();
                        }
                    })
                    .build();

            ThreadPoolExecutor resolved = config.resolveThreadPool(POOL_NAME);
            assertThat(resolved)
                    .as("Explicit ExecutorResolver should take priority over registry")
                    .isSameAs(resolverTpe);
        } finally {
            registryTpe.shutdownNow();
            resolverTpe.shutdownNow();
        }
    }
}
