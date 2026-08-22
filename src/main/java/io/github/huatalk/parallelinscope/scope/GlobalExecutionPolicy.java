package io.github.huatalk.parallelinscope.scope;

import io.github.huatalk.parallelinscope.spi.TaskListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable application-level defaults shared by all Par entries in one GlobalPar. */
public final class GlobalExecutionPolicy {
    private final long defaultTimeoutMillis;
    private final List<TaskListener> taskListeners;

    private GlobalExecutionPolicy(Builder builder) {
        this.defaultTimeoutMillis = builder.defaultTimeoutMillis;
        this.taskListeners = Collections.unmodifiableList(new ArrayList<>(builder.taskListeners));
    }

    public static Builder builder() { return new Builder(); }
    public long defaultTimeoutMillis() { return defaultTimeoutMillis; }
    public List<TaskListener> taskListeners() { return taskListeners; }

    public static final class Builder {
        private long defaultTimeoutMillis = 60_000L;
        private final List<TaskListener> taskListeners = new ArrayList<>();

        public Builder defaultTimeoutMillis(long timeoutMillis) {
            if (timeoutMillis <= 0) throw new IllegalArgumentException("timeout must be positive");
            this.defaultTimeoutMillis = timeoutMillis;
            return this;
        }

        public Builder taskListener(TaskListener listener) {
            taskListeners.add(java.util.Objects.requireNonNull(listener));
            return this;
        }

        public GlobalExecutionPolicy build() { return new GlobalExecutionPolicy(this); }
    }
}
