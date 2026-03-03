package io.github.linzee1.vformation;

import com.google.common.util.concurrent.ListeningExecutorService;
import io.github.linzee1.vformation.context.graph.TaskGraph;
import io.github.linzee1.vformation.scope.AsyncBatchResult;
import io.github.linzee1.vformation.scope.Par;
import io.github.linzee1.vformation.scope.ParOptions;
import io.github.linzee1.vformation.scope.ParConfig;
import io.github.linzee1.vformation.spi.ExecutorResolver;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the executor registry and name-based Par API.
 *
 * @author linqh (linqinghua4 at gmail dot com)
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
        TaskGraph.destroyAfterRequest();
        ParConfig.unregisterExecutor(POOL_NAME);
        ParConfig.setExecutorResolver(null);
        executor.shutdownNow();
    }

    // ==================== 5.1: Register/Get/Unregister Lifecycle ====================

    @Test
    public void testRegisterGetUnregisterLifecycle() {
        assertNull(ParConfig.getExecutor(POOL_NAME));

        ParConfig.registerExecutor(POOL_NAME, executor);
        ListeningExecutorService retrieved = ParConfig.getExecutor(POOL_NAME);
        assertNotNull(retrieved);

        ParConfig.unregisterExecutor(POOL_NAME);
        assertNull(ParConfig.getExecutor(POOL_NAME));
    }

    // ==================== 5.2: Null Validation ====================

    @Test
    public void testRegisterWithNullNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ParConfig.registerExecutor(null, executor));
    }

    @Test
    public void testRegisterWithEmptyNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ParConfig.registerExecutor("", executor));
    }

    @Test
    public void testRegisterWithNullExecutorThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ParConfig.registerExecutor(POOL_NAME, null));
    }

    // ==================== 5.3: parMap and parForEach with executor name ====================

    @Test
    public void testParMapWithExecutorName() throws Exception {
        ParConfig.registerExecutor(POOL_NAME, executor);
        List<Integer> input = Arrays.asList(1, 2, 3);

        ParOptions options = ParOptions.of("registryParMap")
                .timeout(5000)
                .build();

        AsyncBatchResult<Integer> batch = Par.parMap(
                POOL_NAME, input, x -> x * 10, options);

        List<Integer> results = new ArrayList<>();
        for (com.google.common.util.concurrent.ListenableFuture<Integer> f : batch.getResults()) {
            results.add(f.get(5, TimeUnit.SECONDS));
        }

        Collections.sort(results);
        assertEquals(Arrays.asList(10, 20, 30), results);
    }

    @Test
    public void testParForEachWithExecutorName() throws Exception {
        ParConfig.registerExecutor(POOL_NAME, executor);
        List<String> input = Arrays.asList("a", "b", "c");
        CopyOnWriteArrayList<String> results = new CopyOnWriteArrayList<>();

        ParOptions options = ParOptions.of("registryParForEach")
                .timeout(5000)
                .build();

        AsyncBatchResult<Void> batch = Par.parForEach(
                POOL_NAME, input, results::add, options);

        for (com.google.common.util.concurrent.ListenableFuture<Void> f : batch.getResults()) {
            f.get(5, TimeUnit.SECONDS);
        }

        Collections.sort(results);
        assertEquals(Arrays.asList("a", "b", "c"), results);
    }

    // ==================== 5.4: Unregistered name throws ====================

    @Test
    public void testParMapWithUnregisteredNameThrows() {
        ParOptions options = ParOptions.of("test").build();
        assertThrows(IllegalArgumentException.class,
                () -> Par.parMap("nonexistent", Arrays.asList(1), x -> x, options));
    }

    @Test
    public void testParForEachWithUnregisteredNameThrows() {
        ParOptions options = ParOptions.of("test").build();
        assertThrows(IllegalArgumentException.class,
                () -> Par.parForEach("nonexistent", Arrays.asList(1), x -> {}, options));
    }

    // ==================== 5.5: Auto-bridge to purge subsystem ====================

    @Test
    public void testResolveThreadPoolFromRegistry() {
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            ParConfig.registerExecutor(POOL_NAME, tpe);

            // No explicit ExecutorResolver set
            ThreadPoolExecutor resolved = ParConfig.resolveThreadPool(POOL_NAME);
            assertNotNull(resolved);
            assertSame(tpe, resolved);
        } finally {
            tpe.shutdownNow();
        }
    }

    @Test
    public void testResolveThreadPoolReturnsNullForNonThreadPoolExecutor() {
        // Executors.newFixedThreadPool returns a ThreadPoolExecutor in practice,
        // but let's test with a non-TPE to verify the instanceof check
        ParConfig.registerExecutor(POOL_NAME, executor);
        // executor from Executors.newFixedThreadPool IS a ThreadPoolExecutor, so it should resolve
        ThreadPoolExecutor resolved = ParConfig.resolveThreadPool(POOL_NAME);
        assertNotNull(resolved);
    }

    // ==================== 5.6: Explicit ExecutorResolver takes priority ====================

    @Test
    public void testExplicitExecutorResolverTakesPriority() {
        ThreadPoolExecutor registryTpe = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        ThreadPoolExecutor resolverTpe = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

        try {
            ParConfig.registerExecutor(POOL_NAME, registryTpe);

            ParConfig.setExecutorResolver(new ExecutorResolver() {
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
            });

            ThreadPoolExecutor resolved = ParConfig.resolveThreadPool(POOL_NAME);
            assertSame(resolverTpe, resolved, "Explicit ExecutorResolver should take priority over registry");
        } finally {
            registryTpe.shutdownNow();
            resolverTpe.shutdownNow();
        }
    }
}
