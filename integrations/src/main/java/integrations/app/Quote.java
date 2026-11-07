package integrations.app;

/** A price and stock answer for one SKU; either part may be a degraded fallback. */
public final class Quote {

    /** Price returned while the pricing breaker is open. */
    public static final long PRICE_UNAVAILABLE = -1L;

    /** Stock returned while Sentinel blocks inventory calls. */
    public static final int STOCK_UNKNOWN = -1;

    private final String sku;
    private final long priceCents;
    private final int available;

    /**
     * Creates a quote.
     *
     * @param sku the SKU
     * @param priceCents the price, or {@link #PRICE_UNAVAILABLE}
     * @param available the stock, or {@link #STOCK_UNKNOWN}
     */
    public Quote(String sku, long priceCents, int available) {
        this.sku = sku;
        this.priceCents = priceCents;
        this.available = available;
    }

    /** @return the SKU */
    public String sku() {
        return sku;
    }

    /** @return the price in cents, or {@link #PRICE_UNAVAILABLE} */
    public long priceCents() {
        return priceCents;
    }

    /** @return the available quantity, or {@link #STOCK_UNKNOWN} */
    public int available() {
        return available;
    }

    /** @return whether the price is a fallback */
    public boolean priceDegraded() {
        return priceCents == PRICE_UNAVAILABLE;
    }

    /** @return whether the stock is a fallback */
    public boolean stockDegraded() {
        return available == STOCK_UNKNOWN;
    }

    @Override
    public String toString() {
        return sku + "{price=" + (priceDegraded() ? "unavailable" : priceCents) + ", stock="
                + (stockDegraded() ? "unknown" : available) + "}";
    }
}
