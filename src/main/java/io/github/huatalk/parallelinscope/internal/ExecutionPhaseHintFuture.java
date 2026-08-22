package io.github.huatalk.parallelinscope.internal;

import com.google.common.util.concurrent.ForwardingListenableFuture.SimpleForwardingListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import io.github.huatalk.parallelinscope.spi.ExecutionPhase;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A listenable future and runnable that publishes hints about its execution phase.
 *
 * @param <V> result type
 */
final class ExecutionPhaseHintFuture<V> extends SimpleForwardingListenableFuture<V>
    implements RunnableFuture<V> {

  private static final Logger LOGGER = Logger.getLogger(ExecutionPhaseHintFuture.class.getName());
  private static final Consumer<ExecutionPhase> NOOP = phase -> {};

  /**
   * Tracks whether the worker or cancellation claimed the task first. The resulting phase is a hint
   * for consumers such as queue maintenance; it is not an exact queue-membership probe. This is
   * separate from the future state maintained by {@link ListenableFutureTask}.
   *
   * <pre>
   * Phase                       Meaning
   * --------------------------  -------------------------------------------------------------
   * SUBMITTED                   No worker has claimed the runnable yet.
   * RUNNING                     A worker has claimed the runnable.
   * CANCELLED_BEFORE_RUN        Cancellation won before run() claimed the runnable.
   * CANCEL_REQUESTED_RUNNING    Cancellation followed a run() claim.
   * TERMINAL                    run() has returned.
   *
   * State transitions:
   *
   *                          cancellation wins
   * SUBMITTED ----------------------------------------------> CANCELLED_BEFORE_RUN
   *     |
   *     | worker run() wins
   *     v
   *  RUNNING ---- cancellation succeeds ----> CANCEL_REQUESTED_RUNNING
   *     |                                              |
   *     +---------------- run() returns ---------------+
   *                            |
   *                            v
   *                         TERMINAL
   * </pre>
   */
  private final ListenableFutureTask<V> task;

  private final AtomicReference<ExecutionPhase> phase =
      new AtomicReference<>(ExecutionPhase.SUBMITTED);

  private volatile Consumer<? super ExecutionPhase> phaseObserver;

  /** Creates a future with a phase observer. */
  static <V> ExecutionPhaseHintFuture<V> create(
      Callable<V> callable, Consumer<? super ExecutionPhase> phaseObserver) {
    return new ExecutionPhaseHintFuture<>(ListenableFutureTask.create(callable), phaseObserver);
  }

  /** Creates a future with a phase observer for a runnable and fixed result. */
  static <V> ExecutionPhaseHintFuture<V> create(
      Runnable runnable, V result, Consumer<? super ExecutionPhase> phaseObserver) {
    return new ExecutionPhaseHintFuture<>(
        ListenableFutureTask.create(runnable, result), phaseObserver);
  }

  /** Wraps Guava's future semantics with task-local execution-phase hints. */
  private ExecutionPhaseHintFuture(
      ListenableFutureTask<V> task, Consumer<? super ExecutionPhase> phaseObserver) {
    super(task);
    this.task = task;
    this.phaseObserver = Objects.requireNonNull(phaseObserver);
  }

  /** Runs the delegate only after claiming the transition out of the submitted state. */
  @Override
  public void run() {
    if (!phase.compareAndSet(ExecutionPhase.SUBMITTED, ExecutionPhase.RUNNING)) {
      return;
    }
    notifyPhase(ExecutionPhase.RUNNING);
    try {
      task.run();
    } finally {
      phase.set(ExecutionPhase.TERMINAL);
      notifyPhase(ExecutionPhase.TERMINAL);
      phaseObserver = NOOP;
    }
  }

  /** Classifies successful cancellation using the same state raced by {@link #run()}. */
  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    if (!task.cancel(mayInterruptIfRunning)) {
      return false;
    }
    while (true) {
      ExecutionPhase current = phase.get();
      if (current == ExecutionPhase.SUBMITTED) {
        if (phase.compareAndSet(ExecutionPhase.SUBMITTED, ExecutionPhase.CANCELLED_BEFORE_RUN)) {
          notifyPhase(ExecutionPhase.CANCELLED_BEFORE_RUN);
          phaseObserver = NOOP;
          return true;
        }
      } else if (current == ExecutionPhase.RUNNING) {
        if (phase.compareAndSet(ExecutionPhase.RUNNING, ExecutionPhase.CANCEL_REQUESTED_RUNNING)) {
          notifyPhase(ExecutionPhase.CANCEL_REQUESTED_RUNNING);
          return true;
        }
      } else {
        phaseObserver = NOOP;
        return true;
      }
    }
  }

  private void notifyPhase(ExecutionPhase phase) {
    try {
      phaseObserver.accept(phase);
    } catch (Throwable e) {
      LOGGER.log(Level.WARNING, "Execution phase observer failed", e);
    }
  }
}
