package io.github.monadrome.parallelinscope;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

/**
 * Prepared states for race-condition tests: one topology has one construction, so a fix reaches
 * every consumer. Race tests build their pre-state through these functions instead of inline
 * ad-hoc construction (see {@code verification/race-claims/README.md}). A prepared state moves
 * here when a second race test needs it or a registered claim names its topology; single-use
 * probes and workload fixtures stay in their owning test class.
 */
final class PreparedStates {

    private PreparedStates() {}

    /** A running parent token with one linked child; construction wires parent-to-child propagation. */
    static TokenPair parentChild() {
        CancellationToken parent = CancellationToken.create();
        return new TokenPair(parent, new CancellationToken(parent));
    }

    /** A cancelled parent token with one child constructed after the cancellation committed. */
    static TokenPair parentChildWithCancelledParent() {
        CancellationToken parent = CancellationToken.create();
        parent.cancel();
        return new TokenPair(parent, new CancellationToken(parent));
    }

    /** Binds the token to one never-completing task future and returns it. */
    static <T> SettableFuture<T> bindSingleton(
            CancellationToken token, ListenableFuture<?> submitCanceller, ScheduledExecutorService timer) {
        SettableFuture<T> task = SettableFuture.create();
        token.bind(Collections.singletonList(task), submitCanceller, timer);
        return task;
    }

    /** Window futures whose callables return their own index, as prepared for window submissions. */
    static List<ExecutionPhaseHintFuture<Integer>> indexFutures(int size) {
        Callable<Integer>[] callables = new Callable[size];
        Arrays.setAll(callables, index -> () -> index);
        return Arrays.stream(callables).map(ExecutionPhaseHintFuture::create).collect(Collectors.toList());
    }

    /** A resolved batch unit with a forced timeout and bounded parallelism for window submissions. */
    static MultiTaskContext windowUnit(String name, int parallelism, int taskCount) {
        return MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout(name, Duration.ofSeconds(30))
                        .parallelism(parallelism)
                        .spec(),
                taskCount));
    }

    /** One execution slot registered with its tracker, as a submitted window element carries it. */
    static TaskExecutionState trackedSlot(String name) {
        MultiTaskContext unit = MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout(name, Duration.ofSeconds(30)).spec(), 1));
        return ExecutionExitTracker.create(1).register(unit);
    }

    /** A parent token and its linked child. */
    static final class TokenPair {
        private final CancellationToken parent;
        private final CancellationToken child;

        private TokenPair(CancellationToken parent, CancellationToken child) {
            this.parent = parent;
            this.child = child;
        }

        /** The token whose terminal state propagates to the child. */
        CancellationToken parent() {
            return parent;
        }

        /** The token that reports propagated cancellation when the parent commits. */
        CancellationToken child() {
            return child;
        }
    }
}
