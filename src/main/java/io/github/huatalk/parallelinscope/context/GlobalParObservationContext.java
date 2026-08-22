package io.github.huatalk.parallelinscope.context;

import io.github.huatalk.parallelinscope.context.graph.TaskGraph;
import io.github.huatalk.parallelinscope.scope.GlobalPar;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit request-level task-graph observation scope owned by one {@link GlobalPar}.
 *
 * <p>This context is intentionally not a global collector. It captures and restores the graph data
 * active on the opening thread, and {@link #close()} is idempotent so it can be used with
 * try-with-resources. Tasks submitted under the scope share its graph through the batch context.
 */
public final class GlobalParObservationContext implements AutoCloseable {
  private final GlobalPar owner;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final TaskGraph.Data previousData;
  private final TaskGraph.Data data;

  public GlobalParObservationContext(GlobalPar owner) {
    this.owner = Objects.requireNonNull(owner, "owner cannot be null");
    this.previousData = TaskGraph.initOnRequest(this);
    this.data = TaskGraph.data();
  }

  public GlobalPar owner() {
    return owner;
  }

  public boolean isClosed() {
    return closed.get();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      TaskGraph.destroyAfterRequest(this);
    }
  }

  /** Marks the scope closed after graph destruction without recursively invoking destruction. */
  public void complete() {
    closed.set(true);
  }

  /** Returns the graph data that was active before this observation was opened. */
  public TaskGraph.Data previousData() {
    return previousData;
  }

  /** Returns the request graph shared by all tasks in this observation scope. */
  public TaskGraph.Data data() {
    return data;
  }
}
