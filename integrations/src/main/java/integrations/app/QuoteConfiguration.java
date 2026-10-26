package integrations.app;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import integrations.resilience4j.ResilientCall;
import integrations.sentinel.SentinelFlowRules;
import integrations.spring.ParallelInScopeConfiguration;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Wires {@link QuoteService} with its Resilience4j policies and Sentinel rules.
 *
 * <p>The application supplies the {@link PricingClient} and {@link InventoryClient} beans.
 */
@Configuration(proxyBeanMethods = false)
@Import(ParallelInScopeConfiguration.class)
public class QuoteConfiguration {

    /** Breaker and retry instance name for pricing. */
    public static final String PRICING = "pricing";

    /** Concurrent inventory calls Sentinel admits before degrading. */
    public static final int INVENTORY_MAX_CONCURRENT_CALLS = 8;

    /**
     * Creates the breaker registry whose default configuration ignores cancellation.
     *
     * @return the registry
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.of(ResilientCall.circuitBreakerConfig()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .build());
    }

    /**
     * Creates the retry registry whose default configuration never retries cancellation.
     *
     * @return the registry
     */
    @Bean
    public RetryRegistry retryRegistry() {
        return RetryRegistry.of(ResilientCall.retryConfig()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(50))
                .build());
    }

    /**
     * Returns the pricing breaker.
     *
     * @param registry the breaker registry
     * @return the breaker named {@link #PRICING}
     */
    @Bean
    public CircuitBreaker pricingCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(PRICING);
    }

    /**
     * Returns the pricing retry.
     *
     * @param registry the retry registry
     * @return the retry named {@link #PRICING}
     */
    @Bean
    public Retry pricingRetry(RetryRegistry registry) {
        return registry.retry(PRICING);
    }

    /**
     * Limits concurrent inventory calls from every caller.
     *
     * @return a thread-count flow rule on {@link QuoteService#INVENTORY_RESOURCE}
     */
    @Bean
    public FlowRule inventoryConcurrencyRule() {
        FlowRule rule = new FlowRule(QuoteService.INVENTORY_RESOURCE);
        rule.setGrade(RuleConstant.FLOW_GRADE_THREAD);
        rule.setCount(INVENTORY_MAX_CONCURRENT_CALLS);
        return rule;
    }

    /**
     * Loads every {@link FlowRule} bean into Sentinel for the context's lifetime.
     *
     * @param rules the flow rule beans
     * @return the rule holder
     */
    @Bean
    public SentinelFlowRules sentinelFlowRules(List<FlowRule> rules) {
        return new SentinelFlowRules(rules);
    }

    /**
     * Creates the quote service.
     *
     * @param runtime the runtime
     * @param pricing the pricing client
     * @param inventory the inventory client
     * @param pricingCircuitBreaker the pricing breaker
     * @param pricingRetry the pricing retry
     * @param sentinelFlowRules forces the rules to load before the service can be called
     * @return the service
     */
    @Bean
    public QuoteService quoteService(
            ParRuntime runtime,
            PricingClient pricing,
            InventoryClient inventory,
            CircuitBreaker pricingCircuitBreaker,
            Retry pricingRetry,
            SentinelFlowRules sentinelFlowRules) {
        return new QuoteService(runtime, pricing, inventory, pricingCircuitBreaker, pricingRetry);
    }
}
