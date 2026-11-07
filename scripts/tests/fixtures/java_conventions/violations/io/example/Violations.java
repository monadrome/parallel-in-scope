package io.example;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import java.util.Objects;

public class Violations {
    private final Object value;

    public Violations(Object value) {
        this.value = Objects.requireNonNull(value);
        Preconditions.checkArgument(value.hashCode() != 0, "value must not hash to zero.");
        Preconditions.checkState(value.hashCode() != 1, "Value hashed to one");
        Verify.verifyNotNull(value, "value %d is null", 1);
        Preconditions.checkArgument(value.hashCode() != 2,
                "value %1$s hashed to two", value);
        Preconditions.checkArgument(value.hashCode() != 3, "50%% of %s", value);
    }

    /** The task was canceled. */
    public Object getValue() {
        return value;
    }

    protected boolean isSignaling() {
        return false;
    }

    public interface Source {
        String getName();
    }
}
