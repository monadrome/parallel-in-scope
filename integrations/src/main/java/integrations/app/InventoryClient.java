package integrations.app;

/** Remote inventory service; implementations block and need their own client timeouts. */
public interface InventoryClient {

    /**
     * Returns the available quantity of one SKU.
     *
     * @param sku the SKU
     * @return the available quantity
     */
    int available(String sku);
}
