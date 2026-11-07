package integrations.app;

import io.github.monadrome.parallelinscope.ImmediateResult;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Runs {@link QuoteService} in a Spring context against simulated downstream services.
 *
 * <p>The first round quotes healthy services. Pricing then goes down: the second round fails until
 * the breaker opens, after which quotes degrade to an unavailable price. Closing the context stops
 * the runtime, waits for its executions, and shuts the pools down.
 */
public final class QuoteApplication {

    private QuoteApplication() {}

    /**
     * Runs the scenario.
     *
     * @param args unused
     */
    public static void main(String[] args) {
        AtomicBoolean pricingDown = new AtomicBoolean();
        PricingClient pricing = sku -> {
            simulateLatency();
            if (pricingDown.get()) {
                throw new IllegalStateException("pricing unavailable");
            }
            return 100L * (sku.hashCode() & 0xff);
        };
        InventoryClient inventory = sku -> {
            simulateLatency();
            return sku.length() * 3;
        };

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PricingClient.class, () -> pricing);
            context.registerBean(InventoryClient.class, () -> inventory);
            context.register(QuoteConfiguration.class);
            context.refresh();

            QuoteService service = context.getBean(QuoteService.class);
            CircuitBreaker breaker = context.getBean(CircuitBreaker.class);
            List<String> skus = skus(12);

            print("healthy", service.quoteAll("web", skus), breaker);

            pricingDown.set(true);
            for (int round = 1; round <= 3; round++) {
                print("pricing down, round " + round, service.quoteAll("web", skus), breaker);
            }
        }
    }

    private static void print(String label, TaskBatchResult<Quote> batch, CircuitBreaker breaker) {
        System.out.println("== " + label + " (breaker " + breaker.getState() + ")");
        System.out.println("   " + batch.outcomeCounts());
        for (ImmediateResult<Quote> result : batch.results()) {
            System.out.println("   "
                    + result.fold(
                            String::valueOf,
                            userFailure -> "failed: " + userFailure.getMessage(),
                            submissionFailure -> "submission failed: " + submissionFailure.getMessage(),
                            (outcome, detail) -> outcome.name()));
        }
    }

    private static List<String> skus(int count) {
        List<String> skus = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            skus.add("sku-" + i);
        }
        return skus;
    }

    private static void simulateLatency() {
        try {
            TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(5, 30));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling downstream", interrupted);
        }
    }
}
