package integrations.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.monadrome.parallelinscope.Checkpoints;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import io.github.monadrome.parallelinscope.TaskTermination;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@Timeout(30)
class QuoteServiceTest {

    private final AtomicBoolean pricingDown = new AtomicBoolean();
    private final AtomicInteger pricingCalls = new AtomicInteger();
    private volatile InventoryClient inventory = sku -> 7;
    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void quoteCombinesBothDownstreamValues() {
        QuoteService service = start();

        Quote quote = service.quote("web", "sku-1");

        assertThat(quote.sku()).isEqualTo("sku-1");
        assertThat(quote.priceCents()).isEqualTo(1_000L);
        assertThat(quote.available()).isEqualTo(7);
    }

    @Test
    void pricingOutageFailsQuotesUntilTheBreakerOpensThenDegrades() {
        QuoteService service = start();
        CircuitBreaker breaker = context.getBean(CircuitBreaker.class);
        pricingDown.set(true);

        int failures = 0;
        Quote degraded = null;
        while (degraded == null && failures < 20) {
            try {
                degraded = service.quote("web", "sku-1");
            } catch (IllegalStateException failure) {
                assertThat(failure).hasMessage("pricing unavailable");
                failures++;
            }
        }
        int callsWhenOpened = pricingCalls.get();
        Quote later = service.quote("web", "sku-2");

        assertThat(failures).isPositive();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(degraded.priceDegraded()).isTrue();
        assertThat(later.priceDegraded()).isTrue();
        assertThat(later.available()).isEqualTo(7);
        assertThat(pricingCalls).hasValue(callsWhenOpened);
    }

    @Test
    void inventoryOverTheSentinelLimitDegradesStockOnly() throws InterruptedException {
        int limit = QuoteConfiguration.INVENTORY_MAX_CONCURRENT_CALLS;
        CountDownLatch held = new CountDownLatch(limit);
        CountDownLatch release = new CountDownLatch(1);
        inventory = sku -> {
            if (sku.equals("held")) {
                held.countDown();
                Checkpoints.checkAwait(release, Duration.ofSeconds(10));
            }
            return 7;
        };
        QuoteService service = start();
        ExecutorService callers = Executors.newFixedThreadPool(limit);
        try {
            for (int i = 0; i < limit; i++) {
                callers.submit(() -> service.quote("web", "held"));
            }
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

            Quote overLimit = service.quote("web", "extra");

            assertThat(overLimit.stockDegraded()).isTrue();
            assertThat(overLimit.priceDegraded()).isFalse();
        } finally {
            release.countDown();
            callers.shutdown();
        }
        Quote afterRelease = awaitLiveStock(service);
        assertThat(afterRelease.available()).isEqualTo(7);
    }

    @Test
    void quoteAllFansOutWithoutAnExecutorWaitCycle() {
        QuoteService service = start();

        TaskBatchResult<Quote> batch = service.quoteAll("web", Arrays.asList("a", "b", "c", "d", "e"));

        assertThat(batch.terminationKindCounts()).containsEntry(TaskTermination.Kind.SUCCESS, 5);
        assertThat(context.getBean(ParRuntime.class).executorWaitReport().cyclicComponents())
                .isEmpty();
    }

    @Test
    void closingTheContextWaitsForExecutionsBeforeThePoolsShutDown() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Boolean> poolShutDownAtExit = new AtomicReference<>();
        AtomicReference<ThreadPoolExecutor> downstreamPool = new AtomicReference<>();
        inventory = sku -> {
            entered.countDown();
            sleepUninterrupted(300);
            poolShutDownAtExit.set(downstreamPool.get().isShutdown());
            return 7;
        };
        QuoteService service = start();
        downstreamPool.set(context.getBean("downstreamExecutor", ThreadPoolExecutor.class));
        ParRuntime runtime = context.getBean(ParRuntime.class);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Quote> inFlight = caller.submit(() -> service.quote("web", "sku-1"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            context.close();

            assertThat(poolShutDownAtExit.get()).isFalse();
            assertThat(downstreamPool.get().isShutdown()).isTrue();
            assertThat(runtime.closed()).isTrue();
            assertThat(inFlight.get(5, TimeUnit.SECONDS).available()).isEqualTo(7);
        } catch (ExecutionException failure) {
            throw new AssertionError("in-flight quote failed", failure.getCause());
        } finally {
            caller.shutdownNow();
        }
    }

    private QuoteService start() {
        PricingClient pricing = sku -> {
            pricingCalls.incrementAndGet();
            if (pricingDown.get()) {
                throw new IllegalStateException("pricing unavailable");
            }
            return 1_000L;
        };
        InventoryClient delegatingInventory = sku -> inventory.available(sku);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(PricingClient.class, () -> pricing);
        context.registerBean(InventoryClient.class, () -> delegatingInventory);
        context.register(QuoteConfiguration.class);
        context.refresh();
        return context.getBean(QuoteService.class);
    }

    private static Quote awaitLiveStock(QuoteService service) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Quote quote = service.quote("web", "extra");
        while (quote.stockDegraded() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
            quote = service.quote("web", "extra");
        }
        return quote;
    }

    private static void sleepUninterrupted(long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        boolean interrupted = false;
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(remaining);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
