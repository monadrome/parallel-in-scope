package io.example;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;

import java.util.concurrent.AbstractExecutorService;

/** A cancellable, labelled fixture; signalling stays doubled-l. Objects.requireNonNull in prose is fine. */
public final class Clean {
    private final String name;

    // getName() in a comment is not a declaration.
    public Clean(String name) {
        this.name = checkNotNull(name, "name");
        checkArgument(!name.isEmpty(), "name must not be empty, got '%s'", name);
        checkState(name.length() < 10, "ParRuntime is closed");
        checkState(name.length() < 20, "Par '%s' does not belong to the ParRuntime that declared this group", name);
        verify(name.hashCode() != 1, "TaskGroup.start was called twice for %s", name);
        checkArgument(name.length() < 30, "progress " + "reached %s", name.length());
        String message = "Ends with a period.";
        checkArgument(name.length() < 40, message);
    }

    public String name() {
        return name;
    }

    private boolean isPrivate() {
        return getHelper() != null;
    }

    String getHelper() {
        return name;
    }

    public boolean isPresent(String key) {
        return key != null;
    }

    abstract static class Pool extends AbstractExecutorService {
        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return isShutdown();
        }
    }

    interface Callback {
        void cancelled();

        default boolean cancelledOnce() {
            return Boolean.TRUE.equals(new Object() {
                public boolean isAnonymous() {
                    return true;
                }
            }.isAnonymous());
        }
    }
}
