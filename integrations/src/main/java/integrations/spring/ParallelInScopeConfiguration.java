package integrations.spring;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskType;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires one {@link ParRuntime} and its executors into a Spring application context.
 *
 * <p>The runtime has two entries. {@link #QUOTES} runs the outer per-request fan-out, whose bodies
 * wait for nested groups; {@link #DOWNSTREAM} runs the remote calls those groups make. Waiting
 * bodies and the work they wait for use separate pools, so a full pool cannot starve its own
 * children; {@link ParRuntime#executorWaitReport()} stays free of cycles.
 *
 * <p>The executors are plain {@link ThreadPoolExecutor} beans, not Spring's {@code
 * ThreadPoolTaskExecutor}: the runtime inspects the physical pool to judge starvation risk, and a
 * Spring wrapper is opaque to it. Spring's {@code TaskDecorator} does not apply to tasks submitted
 * by this library; context propagation goes through TransmittableThreadLocal instead.
 *
 * <p>Shutdown runs in three owners' order. {@link ParRuntimeLifecycle} closes the runtime and
 * waits for admitted bodies during the lifecycle stop phase, while every other bean is still
 * available to those bodies. The runtime's own {@code close} destroy method is a second, idempotent
 * close for a context that never started. The executor beans are destroyed after the runtime that
 * depends on them, with {@code shutdown}.
 */
@Configuration(proxyBeanMethods = false)
public class ParallelInScopeConfiguration {

    /** Entry for the outer fan-out whose bodies wait for nested groups. */
    public static final ParId QUOTES = ParId.of("quotes");

    /** Entry for blocking downstream calls. */
    public static final ParId DOWNSTREAM = ParId.of("downstream");

    /** How long the lifecycle stop phase waits for admitted bodies to exit. */
    public static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Creates the pool for the outer fan-out.
     *
     * @return a fixed pool with a bounded queue
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor quotesExecutor() {
        return fixedPool("quotes", 4, 256);
    }

    /**
     * Creates the pool for downstream calls.
     *
     * @return a fixed pool with a bounded queue
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor downstreamExecutor() {
        return fixedPool("downstream", 16, 1024);
    }

    /**
     * Builds the runtime from the two pools.
     *
     * @param quotesExecutor the pool for {@link #QUOTES}
     * @param downstreamExecutor the pool for {@link #DOWNSTREAM}
     * @return the runtime; the pools remain owned by their beans
     */
    @Bean(destroyMethod = "close")
    public ParRuntime parRuntime(
            @Qualifier("quotesExecutor") ThreadPoolExecutor quotesExecutor,
            @Qualifier("downstreamExecutor") ThreadPoolExecutor downstreamExecutor) {
        return ParRuntime.builder()
                .register(QUOTES, quotesExecutor, TaskType.BLOCKING)
                .register(DOWNSTREAM, downstreamExecutor, TaskType.BLOCKING)
                .defaultPar(DOWNSTREAM)
                .build();
    }

    /**
     * Closes the runtime and waits for its bodies before the context destroys any bean.
     *
     * @param parRuntime the runtime to stop
     * @return the lifecycle bean
     */
    @Bean
    public ParRuntimeLifecycle parRuntimeLifecycle(ParRuntime parRuntime) {
        return new ParRuntimeLifecycle(parRuntime, SHUTDOWN_TIMEOUT);
    }

    private static ThreadPoolExecutor fixedPool(String name, int threads, int queueCapacity) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new ThreadFactoryBuilder().setNameFormat(name + "-%d").build());
    }
}
