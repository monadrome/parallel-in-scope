package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Reference-retention contract between a running parent scope and its completed child scopes: the
 * parent's cancellation listener must not keep child business results alive.
 *
 * <p>A parent body that loops over child scopes, consuming and discarding each page of results,
 * used to retain every historical child result through the parent token's listener list until the
 * parent token itself finished. Retention must instead track the currently active child scopes,
 * not the history of successful ones.
 */
class ParentChildResultRetentionTest {

    private static final int CHILDREN = 16;
    private static final int PAYLOAD_BYTES = 1024 * 1024;

    /**
     * A parent scope stays open while sixteen child scopes complete successfully; the consumed
     * child payloads must become collectable without waiting for the parent to finish. Fails on the
     * pre-fix implementation, where the parent's pending listener list pins every completed child
     * token together with the success list carried by the child's {@code futureToken}.
     */
    @Test
    void completedChildResultsAreCollectableWhileParentScopeRuns() throws Exception {
        ExecutorService parentPool = Executors.newSingleThreadExecutor();
        ExecutorService childPool = MoreExecutors.newDirectExecutorService();
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("parent"), parentPool)
                .register(ParId.of("child"), childPool)
                .build();
        CountDownLatch prepared = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<WeakReference<byte[]>> references = Collections.synchronizedList(new ArrayList<>());
        Thread caller = new Thread(() -> {
            try {
                runtime.par(ParId.of("parent"))
                        .map(
                                Collections.singletonList(1),
                                ignored -> {
                                    for (int i = 0; i < CHILDREN; i++) {
                                        references.add(new WeakReference<>(childPage(runtime.par(ParId.of("child")))));
                                    }
                                    prepared.countDown();
                                    Uninterruptibles.awaitUninterruptibly(release);
                                    return 1;
                                },
                                BatchOptions.timeout("parent", Duration.ofSeconds(30)));
            } finally {
                prepared.countDown();
            }
        });
        caller.setDaemon(true);
        caller.start();
        try {
            assertThat(prepared.await(5, TimeUnit.SECONDS))
                    .as("parent scope did not prepare its child calls")
                    .isTrue();
            assertThat(references)
                    .as("the retention check is vacuous unless every child payload was recorded")
                    .hasSize(CHILDREN);

            awaitCollectable(references);

            assertThat(caller.isAlive())
                    .as("the parent scope must still be running when retention is checked")
                    .isTrue();
        } finally {
            release.countDown();
            caller.join(5000);
            runtime.close();
            parentPool.shutdownNow();
            childPool.shutdownNow();
        }
    }

    /**
     * The severable link must stay live while the child scope is running: cancelling the parent
     * token from outside still propagates into a child scope whose body has not finished.
     */
    @Test
    void parentCancelWhileChildScopeRunsStillPropagates() throws Exception {
        ExecutorService parentPool = Executors.newSingleThreadExecutor();
        ExecutorService childPool = Executors.newSingleThreadExecutor();
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("parent"), parentPool)
                .register(ParId.of("child"), childPool)
                .build();
        AtomicReference<CancellationToken> parentToken = new AtomicReference<>();
        AtomicReference<String> childReport = new AtomicReference<>();
        AtomicBoolean childInterrupted = new AtomicBoolean();
        CountDownLatch childEntered = new CountDownLatch(1);
        CountDownLatch releaseChild = new CountDownLatch(1);
        Thread caller = new Thread(() -> runtime.par(ParId.of("parent"))
                .map(
                        Collections.singletonList(1),
                        ignored -> {
                            parentToken.set(java.util.Objects.requireNonNull(TaskExecutionContext.current())
                                    .multiTaskContext()
                                    .cancellationToken());
                            childReport.set(runtime.par(ParId.of("child"))
                                    .map(
                                            Collections.singletonList(1),
                                            value -> {
                                                childEntered.countDown();
                                                try {
                                                    releaseChild.await();
                                                    return value;
                                                } catch (InterruptedException interrupted) {
                                                    childInterrupted.set(true);
                                                    Thread.currentThread().interrupt();
                                                    return value;
                                                }
                                            },
                                            BatchOptions.timeout("child", Duration.ofSeconds(30)))
                                    .reportString());
                            return 1;
                        },
                        BatchOptions.timeout("parent", Duration.ofSeconds(30))));
        caller.setDaemon(true);
        caller.start();
        try {
            assertThat(childEntered.await(5, TimeUnit.SECONDS))
                    .as("child scope did not start")
                    .isTrue();
            CancellationToken parent = parentToken.get();
            assertThat(parent).isNotNull();

            parent.cancel(true);

            awaitTrue(childInterrupted);
            awaitReported(childReport);
            assertThat(childReport.get())
                    .as("child scope should report cancellation, not success")
                    .doesNotStartWith("SUCCESS");
        } finally {
            releaseChild.countDown();
            caller.join(5000);
            runtime.close();
            parentPool.shutdownNow();
            childPool.shutdownNow();
        }
    }

    /**
     * The sever itself is deterministic and must not wait for GC: once a child token commits any
     * terminal state, its parent link no longer reaches the child.
     */
    @Test
    void terminalChildSeversTheParentLinkDeterministically() throws Exception {
        CancellationToken parent = CancellationToken.create();
        CancellationToken child = new CancellationToken(parent);

        child.cancel(true);

        assertThat(linkedChild(child))
                .as("a terminal child token must not keep its parent link reachable")
                .isNull();
    }

    /**
     * Construction window: a parent whose future is already terminal runs the listener inline
     * during {@code addListener}. The holder must already be installed then, so the synchronous
     * propagated cancellation severs it instead of leaking a link installed afterwards.
     */
    @Test
    void childCreatedFromAlreadyCancelledParentSeversLinkDuringConstruction() throws Exception {
        CancellationToken parent = CancellationToken.create();
        parent.cancel(true);

        CancellationToken child = new CancellationToken(parent);

        assertThat(child.state()).isEqualTo(CancellationToken.State.PROPAGATED_CANCELLED);
        assertThat(linkedChild(child))
                .as("the inline propagated cancellation must sever the link installed in the constructor")
                .isNull();
    }

    /** Reads the child reference of a token's parent link, or {@code null} when severed/absent. */
    private static @Nullable CancellationToken linkedChild(CancellationToken token) throws Exception {
        Field linkField = CancellationToken.class.getDeclaredField("parentLink");
        linkField.setAccessible(true);
        Object link = linkField.get(token);
        if (link == null) {
            return null;
        }
        Field childField = link.getClass().getDeclaredField("child");
        childField.setAccessible(true);
        return (CancellationToken) childField.get(link);
    }

    private static byte[] childPage(Par child) {
        try {
            return child.map(
                            Collections.singletonList(1),
                            value -> new byte[PAYLOAD_BYTES],
                            BatchOptions.timeout("child", Duration.ofSeconds(10)))
                    .valuesOrThrow()
                    .get(0);
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    /** Forces collection until every reference clears or the bounded window expires. */
    private static void awaitCollectable(List<WeakReference<byte[]>> references) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int live = references.size();
        while (System.nanoTime() < deadline) {
            System.gc();
            live = 0;
            for (WeakReference<byte[]> reference : references) {
                if (reference.get() != null) {
                    live++;
                }
            }
            if (live == 0) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(live)
                .as("parent listener chain must not retain %s completed child payloads", live)
                .isZero();
    }

    /** Waits for the synchronous child exit to publish its report. */
    private static void awaitReported(AtomicReference<String> report) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (report.get() == null && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertThat(report.get()).isNotNull();
    }

    /** Waits for a flag without relying on a fixed sleep. */
    private static void awaitTrue(AtomicBoolean value) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!value.get() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertThat(value).isTrue();
    }
}
