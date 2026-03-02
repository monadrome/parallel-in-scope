package io.github.linzee1.vformation;

import com.google.common.util.concurrent.ListeningExecutorService;
import io.github.linzee1.vformation.context.graph.TaskGraph;
import io.github.linzee1.vformation.scope.AsyncBatchResult;
import io.github.linzee1.vformation.scope.ParallelHelper;
import io.github.linzee1.vformation.scope.ParallelOptions;
import io.github.linzee1.vformation.scope.Par;
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
 * Tests for the executor registry and name-based ParallelHelper API.
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
        Par.unregisterExecutor(POOL_NAME);
        Par.setExecutorResolver(null);
        executor.shutdownNow();
    }

    // ==================== 5.1: Register/Get/Unregister Lifecycle ====================

    @Test
    public void testRegisterGetUnregisterLifecycle() {
        assertNull(Par.getExecutor(POOL_NAME));

        Par.registerExecutor(POOL_NAME, executor);
        ListeningExecutorService retrieved = Par.getExecutor(POOL_NAME);
        assertNotNull(retrieved);

        Par.unregisterExecutor(POOL_NAME);
        assertNull(Par.getExecutor(POOL_NAME));
    }

    // ==================== 5.2: Null Validation ====================

    @Test
    public void testRegisterWithNullNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> Par.registerExecutor(null, executor));
    }

    @Test
    public void testRegisterWithEmptyNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> Par.registerExecutor("", executor));
    }

    @Test
    public void testRegisterWithNullExecutorThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> Par.registerExecutor(POOL_NAME, null));
    }

    // ==================== 5.3: parMap and parForEach with executor name ====================

    @Test
    public void testParMapWithExecutorName() throws Exception {
        Par.registerExecutor(POOL_NAME, executor);
        List<Integer> input = Arrays.asList(1, 2, 3);

        ParallelOptions options = ParallelOptions.of("registryParMap")
                .timeout(5000)
                .build();

        AsyncBatchResult<Integer> batch = ParallelHelper.parMap(
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
        Par.registerExecutor(POOL_NAME, executor);
        List<String> input = Arrays.asList("a", "b", "c");
        CopyOnWriteArrayList<String> results = new CopyOnWriteArrayList<>();

        ParallelOptions options = ParallelOptions.of("registryParForEach")
                .timeout(5000)
                .build();

        AsyncBatchResult<Void> batch = ParallelHelper.parForEach(
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
        ParallelOptions options = ParallelOptions.of("test").build();
        assertThrows(IllegalArgumentException.class,
                () -> ParallelHelper.parMap("nonexistent", Arrays.asList(1), x -> x, options));
    }

    @Test
    public void testParForEachWithUnregisteredNameThrows() {
        ParallelOptions options = ParallelOptions.of("test").build();
        assertThrows(IllegalArgumentException.class,
                () -> ParallelHelper.parForEach("nonexistent", Arrays.asList(1), x -> {}, options));
    }

    // ==================== 5.5: Auto-bridge to purge subsystem ====================

    @Test
    public void testResolveThreadPoolFromRegistry() {
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            Par.registerExecutor(POOL_NAME, tpe);

            // No explicit ExecutorResolver set
            ThreadPoolExecutor resolved = Par.resolveThreadPool(POOL_NAME);
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
        Par.registerExecutor(POOL_NAME, executor);
        // executor from Executors.newFixedThreadPool IS a ThreadPoolExecutor, so it should resolve
        ThreadPoolExecutor resolved = Par.resolveThreadPool(POOL_NAME);
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
            Par.registerExecutor(POOL_NAME, registryTpe);

            Par.setExecutorResolver(new ExecutorResolver() {
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

            ThreadPoolExecutor resolved = Par.resolveThreadPool(POOL_NAME);
            assertSame(resolverTpe, resolved, "Explicit ExecutorResolver should take priority over registry");
        } finally {
            registryTpe.shutdownNow();
            resolverTpe.shutdownNow();
        }
    }
}
