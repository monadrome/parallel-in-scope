package io.github.huatalk.parallelinscope.scope;

import com.google.common.util.concurrent.ListeningExecutorService;
import io.github.huatalk.parallelinscope.internal.ExecutorProfile;
import io.github.huatalk.parallelinscope.internal.PurgeContext;

import java.util.Objects;

/** Stable internal association between one executor registration and its capabilities. */
final class ExecutorBinding {

    private final String name;
    private final ListeningExecutorService executor;
    private final ExecutorProfile profile;
    private final PurgeContext purgeContext;

    /** Creates one immutable registration binding. */
    ExecutorBinding(
            String name,
            ListeningExecutorService executor,
            ExecutorProfile profile,
            PurgeContext purgeContext) {
        this.name = Objects.requireNonNull(name);
        this.executor = Objects.requireNonNull(executor);
        this.profile = Objects.requireNonNull(profile);
        this.purgeContext = Objects.requireNonNull(purgeContext);
    }

    /** Returns the stable logical name. */
    String getName() {
        return name;
    }

    /** Returns the executor used for task submission. */
    ListeningExecutorService getExecutor() {
        return executor;
    }

    /** Returns the registration-time capability snapshot. */
    ExecutorProfile getProfile() {
        return profile;
    }

    /** Returns the purge context bound to the same registered executor. */
    PurgeContext getPurgeContext() {
        return purgeContext;
    }
}
