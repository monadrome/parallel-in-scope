package io.github.huatalk.parallelinscope.scope;

/** Immutable thread-pool-level purge policy owned by one GlobalPar. */
public final class GlobalParPurgePolicy {
    private final boolean enabled;
    private final double queuePressureThreshold;
    private final double cancelledTaskRatioThreshold;

    private GlobalParPurgePolicy(Builder builder) {
        this.enabled = builder.enabled;
        this.queuePressureThreshold = builder.queuePressureThreshold;
        this.cancelledTaskRatioThreshold = builder.cancelledTaskRatioThreshold;
    }

    public static Builder builder() { return new Builder(); }
    public boolean enabled() { return enabled; }
    public double queuePressureThreshold() { return queuePressureThreshold; }
    public double cancelledTaskRatioThreshold() { return cancelledTaskRatioThreshold; }

    public static final class Builder {
        private boolean enabled;
        private double queuePressureThreshold = 0.80;
        private double cancelledTaskRatioThreshold = 0.05;

        public Builder enabled(boolean enabled) { this.enabled = enabled; return this; }
        public Builder queuePressureThreshold(double threshold) {
            validate(threshold, "queuePressureThreshold");
            this.queuePressureThreshold = threshold;
            return this;
        }
        public Builder cancelledTaskRatioThreshold(double threshold) {
            validate(threshold, "cancelledTaskRatioThreshold");
            this.cancelledTaskRatioThreshold = threshold;
            return this;
        }
        public GlobalParPurgePolicy build() { return new GlobalParPurgePolicy(this); }

        private static void validate(double value, String name) {
            if (Double.isNaN(value) || value <= 0.0 || value > 1.0) {
                throw new IllegalArgumentException(name + " must be in (0, 1]");
            }
        }
    }
}
