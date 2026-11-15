package integrations.app;

/** Remote pricing service; implementations block and need their own client timeouts. */
public interface PricingClient {

    /**
     * Returns the current price of one SKU.
     *
     * @param sku the SKU
     * @return the price in cents
     */
    long priceCents(String sku);
}
