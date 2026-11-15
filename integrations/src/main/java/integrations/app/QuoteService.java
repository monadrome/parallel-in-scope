package integrations.app;

import integrations.resilience4j.ResilientCall;
import integrations.sentinel.SentinelGuard;
import integrations.spring.ParallelInScopeConfiguration;
import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import io.github.monadrome.parallelinscope.TaskKey;
import io.github.monadrome.parallelinscope.TaskType;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.time.Duration;
import java.util.List;

/**
 * Builds quotes from two downstream services, combining all three integrations.
 *
 * <p>{@link #quote} runs one group: the price and stock members run in parallel on the downstream
 * pool, and the combine assembles the quote once both succeed. Pricing goes through a Resilience4j
 * retry and circuit breaker and degrades to {@link Quote#PRICE_UNAVAILABLE} while the breaker is
 * open. Inventory goes through a Sentinel resource and degrades to {@link Quote#STOCK_UNKNOWN}
 * when a rule blocks it. Any other failure fails the member, cancels its sibling, and is thrown
 * from {@link #quote}.
 *
 * <p>{@link #quoteAll} fans out one {@link #quote} group per SKU on the quotes pool. Each nested
 * group's deadline is capped by the batch deadline, and a failed SKU cancels the remaining ones.
 */
public final class QuoteService {

    /** Sentinel resource name for inventory calls. */
    public static final String INVENTORY_RESOURCE = "inventory";

    /** Sentinel context name for requests entering through this service. */
    public static final String SENTINEL_CONTEXT = "quote-api";

    /** Deadline of one quote group. */
    public static final Duration QUOTE_TIMEOUT = Duration.ofSeconds(2);

    /** Deadline of one {@link #quoteAll} batch, which caps every nested quote. */
    public static final Duration BATCH_TIMEOUT = Duration.ofSeconds(5);

    private static final TaskKey<Long> PRICE = TaskKey.named("price");
    private static final TaskKey<Integer> STOCK = TaskKey.named("stock");
    private static final TaskKey<Quote> QUOTE = TaskKey.named("quote");

    private final ParRuntime runtime;
    private final Par quotes;
    private final Par downstream;
    private final PricingClient pricing;
    private final InventoryClient inventory;
    private final CircuitBreaker pricingBreaker;
    private final Retry pricingRetry;

    /**
     * Creates the service.
     *
     * @param runtime the runtime that owns the quotes and downstream entries
     * @param pricing the pricing client
     * @param inventory the inventory client
     * @param pricingBreaker the breaker guarding pricing
     * @param pricingRetry the retry policy for pricing
     */
    public QuoteService(
            ParRuntime runtime,
            PricingClient pricing,
            InventoryClient inventory,
            CircuitBreaker pricingBreaker,
            Retry pricingRetry) {
        this.runtime = runtime;
        this.quotes = runtime.par(ParallelInScopeConfiguration.QUOTES);
        this.downstream = runtime.par(ParallelInScopeConfiguration.DOWNSTREAM);
        this.pricing = pricing;
        this.inventory = inventory;
        this.pricingBreaker = pricingBreaker;
        this.pricingRetry = pricingRetry;
    }

    /**
     * Quotes one SKU.
     *
     * @param caller the calling application, matched by origin-specific Sentinel rules
     * @param sku the SKU
     * @return the quote; degraded parts are marked by their fallback values
     * @throws RuntimeException the first downstream failure that was not degraded, as thrown
     */
    public Quote quote(String caller, String sku) {
        return runtime.group("quote", QUOTE_TIMEOUT)
                .par(PRICE, downstream, () -> price(sku))
                .par(STOCK, downstream, () -> stock(caller, sku))
                .combineValues(
                        QUOTE, downstream, values -> new Quote(sku, values.valueOf(PRICE), values.valueOf(STOCK)))
                .runAll()
                .terminalValueOrThrow();
    }

    /**
     * Quotes several SKUs in parallel, at most eight at a time.
     *
     * @param caller the calling application
     * @param skus the SKUs
     * @return the frozen per-SKU results in input order
     */
    public TaskBatchResult<Quote> quoteAll(String caller, List<String> skus) {
        return quotes.map(
                skus,
                sku -> quote(caller, sku),
                BatchOptions.timeout("quotes", BATCH_TIMEOUT).parallelism(8).taskType(TaskType.BLOCKING));
    }

    private long price(String sku) {
        return ResilientCall.call(
                pricingBreaker, pricingRetry, () -> pricing.priceCents(sku), open -> Quote.PRICE_UNAVAILABLE);
    }

    private int stock(String caller, String sku) {
        return SentinelGuard.callWithOrigin(
                SENTINEL_CONTEXT,
                caller,
                INVENTORY_RESOURCE,
                () -> inventory.available(sku),
                blocked -> Quote.STOCK_UNKNOWN);
    }
}
