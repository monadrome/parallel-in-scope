package io.github.huatalk.parallelinscope.scope;

import io.github.huatalk.parallelinscope.spi.LivelockListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

/** Immutable livelock policy owned by one GlobalPar. */
public final class GlobalParLivelockPolicy {
  private final boolean enabled;
  private final List<LivelockListener> listeners;

  private GlobalParLivelockPolicy(Builder builder) {
    this.enabled = builder.enabled;
    IdentityHashMap<LivelockListener, Boolean> seen = new IdentityHashMap<>();
    List<LivelockListener> unique = new ArrayList<>();
    for (LivelockListener listener : builder.listeners) {
      if (seen.put(listener, Boolean.TRUE) == null) unique.add(listener);
    }
    this.listeners = Collections.unmodifiableList(unique);
  }

  public static Builder builder() {
    return new Builder();
  }

  public boolean enabled() {
    return enabled;
  }

  public List<LivelockListener> listeners() {
    return listeners;
  }

  public static final class Builder {
    private boolean enabled;
    private final List<LivelockListener> listeners = new ArrayList<>();

    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    public Builder listener(LivelockListener listener) {
      listeners.add(java.util.Objects.requireNonNull(listener));
      return this;
    }

    public GlobalParLivelockPolicy build() {
      return new GlobalParLivelockPolicy(this);
    }
  }
}
