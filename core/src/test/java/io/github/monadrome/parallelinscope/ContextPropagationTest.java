package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.alibaba.ttl.spi.TtlEnhanced;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Contract tests for reusable captures, guarded scopes, composition, and callback boundaries. */
class ContextPropagationTest {
    /** Reuses an immutable empty capture without sharing thread-bound installation frames. */
    @Test
    void noOpCaptureReusesSnapshotButNotScopeFrames() {
        ContextPropagation provider = ContextPropagation.none();
        ContextSnapshot snapshot = provider.capture();
        assertThat(provider.capture()).isSameAs(snapshot);
        ContextScope outer = snapshot.install();
        try {
            ContextScope inner = snapshot.install();
            try {
                assertThat(inner).isNotSameAs(outer);
                assertThatThrownBy(outer::close).isInstanceOf(IllegalStateException.class);
            } finally {
                inner.close();
            }
        } finally {
            outer.close();
        }
    }

    /** Restores nested captures and rejects crossed closure before changing application state. */
    @Test
    void nestedScopesRejectCrossedCloseAndAllowRetry() {
        ThreadLocal<String> local = new ThreadLocal<>();
        ContextPropagation provider = provider(local);
        local.set("captured");
        ContextSnapshot snapshot = provider.capture();
        local.set("entering");
        ContextScope outer = snapshot.install();
        local.set("nested");
        ContextScope inner = snapshot.install();
        assertThatThrownBy(outer::close).isInstanceOf(IllegalStateException.class);
        assertThat(local.get()).isEqualTo("captured");
        inner.close();
        assertThat(local.get()).isEqualTo("nested");
        outer.close();
        assertThat(local.get()).isEqualTo("entering");
        outer.close();
        assertThat(local.get()).isEqualTo("entering");
    }

    /** Rejects wrong-thread closure even after successful closure without consuming another frame. */
    @Test
    void rejectsWrongThreadClosure() throws Exception {
        AtomicInteger restores = new AtomicInteger();
        ContextScope scope = ContextSnapshot.of(() -> restores::incrementAndGet).install();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> assertThatThrownBy(scope::close).isInstanceOf(IllegalStateException.class))
                    .get(5, TimeUnit.SECONDS);
            assertThat(restores).hasValue(0);
            scope.close();
            worker.submit(() -> assertThatThrownBy(scope::close).isInstanceOf(IllegalStateException.class))
                    .get(5, TimeUnit.SECONDS);
            assertThat(restores).hasValue(1);
        } finally {
            scope.close();
            worker.shutdownNow();
        }
    }

    /** Consumes a failed restoration once and leaves the enclosing frame closable. */
    @Test
    void failedRestorationConsumesFrame() {
        AtomicInteger restores = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("restore failed");
        ContextScope outer = ContextPropagation.none().capture().install();
        ContextScope inner = ContextSnapshot.of(() -> () -> {
                    restores.incrementAndGet();
                    throw failure;
                })
                .install();
        assertThatThrownBy(inner::close).isSameAs(failure);
        inner.close();
        outer.close();
        assertThat(restores).hasValue(1);
    }

    /** Disallows installation and closure from provider callbacks before nested side effects. */
    @Test
    void rejectsCallbackReentry() {
        ContextSnapshot empty = ContextPropagation.none().capture();
        ContextScope outer = empty.install();
        ContextSnapshot invalid = ContextSnapshot.of(() -> {
            assertThatThrownBy(outer::close).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(empty::install).isInstanceOf(IllegalStateException.class);
            return () -> {
                assertThatThrownBy(outer::close).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(empty::install).isInstanceOf(IllegalStateException.class);
            };
        });
        invalid.install().close();
        outer.close();
    }

    /** A failed installer issues no frame and does not poison later protocol calls. */
    @Test
    void failedInstallationLeavesStackUsable() {
        IllegalArgumentException failure = new IllegalArgumentException("installation failed");
        ContextScope outer = ContextPropagation.none().capture().install();
        assertThatThrownBy(() -> ContextSnapshot.of(() -> {
                            throw failure;
                        })
                        .install())
                .isSameAs(failure);
        assertThatThrownBy(() -> ContextSnapshot.of(() -> null).install()).isInstanceOf(NullPointerException.class);
        outer.close();
        ContextPropagation.none().capture().install().close();
    }

    /** Concurrent replay of one snapshot backs up each worker's bindings independently. */
    @Test
    void concurrentReuseHasIndependentBackups() throws Exception {
        ThreadLocal<String> local = new ThreadLocal<>();
        local.set("captured");
        ContextSnapshot snapshot = provider(local).capture();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch installed = new CountDownLatch(2);
        CountDownLatch restore = new CountDownLatch(1);
        try {
            Future<?> first = workers.submit(() -> reuse(snapshot, local, "first", installed, restore));
            Future<?> second = workers.submit(() -> reuse(snapshot, local, "second", installed, restore));
            assertThat(installed.await(5, TimeUnit.SECONDS)).isTrue();
            restore.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            restore.countDown();
            workers.shutdownNow();
        }
        assertThat(local.get()).isEqualTo("captured");
    }

    /** Composition copies its array, captures in order, installs in order, and unwinds in reverse. */
    @Test
    @SuppressWarnings("NullAway")
    void compositionOrdersAndDefensivelyCopiesProviders() {
        List<String> events = new ArrayList<>();
        ContextPropagation first = recording("first", events);
        ContextPropagation second = recording("second", events);
        ContextPropagation[] providers = {first, second};
        ContextPropagation composition = ContextPropagation.compose(providers);
        providers[0] = ContextPropagation.none();
        ContextSnapshot snapshot = composition.capture();
        assertThat(events).containsExactly("capture first", "capture second");
        snapshot.install().close();
        assertThat(events)
                .containsExactly(
                        "capture first",
                        "capture second",
                        "install first",
                        "install second",
                        "restore second",
                        "restore first");
        assertThatThrownBy(() -> ContextPropagation.compose(first, first)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextPropagation.compose((ContextPropagation[]) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ContextPropagation.compose(first, null)).isInstanceOf(NullPointerException.class);
        ContextPropagation.compose().capture().install().close();
    }

    /** Partial installation restores successful predecessors and retains the installation failure. */
    @Test
    void partialInstallationRollsBackAndSuppressesCleanupFailure() {
        IllegalStateException installFailure = new IllegalStateException("install failed");
        IllegalStateException restoreFailure = new IllegalStateException("restore failed");
        List<String> events = new ArrayList<>();
        ContextPropagation first = () -> ContextSnapshot.of(() -> () -> {
            events.add("restored");
            throw restoreFailure;
        });
        ContextPropagation second = () -> ContextSnapshot.of(() -> {
            throw installFailure;
        });
        assertThatThrownBy(() ->
                        ContextPropagation.compose(first, second).capture().install())
                .isSameAs(installFailure);
        assertThat(events).containsExactly("restored");
        assertThat(installFailure.getSuppressed()).containsExactly(restoreFailure);
        ContextPropagation.none().capture().install().close();
    }

    /** All restorations run; the latest-installed failing component owns the primary failure. */
    @Test
    void compositionAttemptsEveryRestorationAfterFailure() {
        AssertionError first = new AssertionError("first");
        IllegalStateException second = new IllegalStateException("second");
        ContextScope scope = ContextPropagation.compose(failingRestore(first), failingRestore(second))
                .capture()
                .install();
        assertThatThrownBy(scope::close).isSameAs(second);
        assertThat(second.getSuppressed()).containsExactly(first);
        scope.close();
    }

    /** Checked failures from JVM-language providers cannot skip other restorations or mask a body failure. */
    @Test
    void checkedRestorationFailurePreservesUnwindAndFailureOrder() {
        IOException cleanup = new IOException("checked restore failure");
        IllegalStateException other = new IllegalStateException("earlier provider");
        ContextPropagation checked = () -> ContextSnapshot.of(() -> () -> {
            throw unchecked(cleanup);
        });
        ContextScope scope = ContextPropagation.compose(failingRestore(other), checked)
                .capture()
                .install();
        assertThatThrownBy(scope::close).isSameAs(cleanup);
        assertThat(cleanup.getSuppressed()).containsExactly(other);

        IllegalStateException body = new IllegalStateException("body failure");
        assertThatThrownBy(() -> ContextExecutors.directContextExecutor(checked).execute(() -> {
                    throw body;
                }))
                .isSameAs(body);
        assertThat(body.getSuppressed()).containsExactly(cleanup);
    }

    /** Throws a JVM failure outside a Java functional interface's declared exception types. */
    @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
    static <Failure extends Throwable> RuntimeException unchecked(Throwable failure) throws Failure {
        throw (Failure) failure;
    }

    /** Callback and task adapters preserve body failures, including identical cleanup throwables. */
    @Test
    void bodyFailureWinsWithoutSelfSuppression() throws Exception {
        IllegalStateException body = new IllegalStateException("body");
        AssertionError cleanup = new AssertionError("cleanup");
        ContextPropagation provider = failingRestore(cleanup);
        assertThatThrownBy(
                        () -> ContextExecutors.directContextExecutor(provider).execute(() -> {
                            throw body;
                        }))
                .isSameAs(body);
        assertThat(body.getSuppressed()).containsExactly(cleanup);
        Callable<Object> wrapped = ContextPropagationSupport.wrap(failingRestore(body), () -> {
            throw body;
        });
        assertThatThrownBy(wrapped::call).isSameAs(body);
        assertThat(body.getSuppressed()).containsExactly(cleanup);
        assertThatThrownBy(() -> ContextExecutors.directContextExecutor(failingRestore(body))
                        .execute(() -> {
                            throw body;
                        }))
                .isSameAs(body);
        assertThatThrownBy(() ->
                        ContextPropagationSupport.wrap(provider, () -> "value").call())
                .isSameAs(cleanup);
    }

    /** Registration captures once while direct invocation captures every execute call. */
    @Test
    @SuppressWarnings("NullAway")
    void callbackBoundariesAndNoOpAreExplicit() {
        ThreadLocal<String> local = new ThreadLocal<>();
        local.set("registration");
        ContextPropagation provider = provider(local);
        Executor registered = ContextExecutors.registrationContextExecutor(provider);
        Executor direct = ContextExecutors.directContextExecutor(provider);
        local.set("invocation");
        registered.execute(() -> {
            assertThat(local.get()).isEqualTo("registration");
            local.set("changed");
        });
        direct.execute(() -> {
            assertThat(local.get()).isEqualTo("invocation");
            local.set("changed");
        });
        assertThat(local.get()).isEqualTo("invocation");
        ContextPropagation.none().capture().install().close();
        assertThat(local.get()).isEqualTo("invocation");
        assertThatThrownBy(() -> ContextSnapshot.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ContextExecutors.directContextExecutor(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registered.execute(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> direct.execute(null)).isInstanceOf(NullPointerException.class);
    }

    /** Explicit TTL composition retains enhancement recognition and restores entering TTL values. */
    @Test
    void ttlCompositionRetainsEnhancedCallableAndBindings() throws Exception {
        TransmittableThreadLocal<String> local = new TransmittableThreadLocal<>();
        try {
            local.set("captured");
            ContextPropagation propagation =
                    ContextPropagation.compose(ContextPropagation.ttl(), ContextPropagation.none());
            Callable<String> callable = ContextPropagationSupport.wrap(propagation, local::get);
            assertThat(callable).isInstanceOf(TtlEnhanced.class);
            local.set("entering");
            assertThat(callable.call()).isEqualTo("captured");
            assertThat(local.get()).isEqualTo("entering");
        } finally {
            local.remove();
        }
    }

    /** Custom-only compositions must not suppress an external TTL adapter by claiming enhancement. */
    @Test
    void nestedCompositionsMarkOnlyActualTtlTransport() throws Exception {
        ThreadLocal<String> local = new ThreadLocal<>();
        local.set("captured");
        ContextPropagation custom =
                ContextPropagation.compose(ContextPropagation.none(), ContextPropagation.compose(provider(local)));
        Callable<String> callable = ContextPropagationSupport.wrap(custom, local::get);
        assertThat(callable).isNotInstanceOf(TtlEnhanced.class);
        Callable<String> ttlCallable = ContextPropagationSupport.wrap(
                ContextPropagation.compose(
                        ContextPropagation.none(),
                        ContextPropagation.compose(ContextPropagation.ttl(), provider(local))),
                local::get);
        assertThat(ttlCallable).isInstanceOf(TtlEnhanced.class);
        local.set("entering");
        assertThat(callable.call()).isEqualTo("captured");
        assertThat(ttlCallable.call()).isEqualTo("captured");
        assertThat(local.get()).isEqualTo("entering");
    }

    /** Scope operations preserve an existing interruption signal. */
    @Test
    void scopeDoesNotConsumeInterruption() {
        Thread.currentThread().interrupt();
        try {
            ContextPropagation.none().capture().install().close();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    /** Captures one application ThreadLocal without sharing per-installation backup slots. */
    static ContextPropagation provider(ThreadLocal<String> local) {
        return () -> {
            String captured = local.get();
            return ContextSnapshot.of(() -> {
                String previous = local.get();
                local.set(captured);
                return () -> {
                    if (previous == null) {
                        local.remove();
                    } else {
                        local.set(previous);
                    }
                };
            });
        };
    }

    /** Supplies deterministic capture, install, and restore events for composition tests. */
    private static ContextPropagation recording(String name, List<String> events) {
        return () -> {
            events.add("capture " + name);
            return ContextSnapshot.of(() -> {
                events.add("install " + name);
                return () -> events.add("restore " + name);
            });
        };
    }

    /** Returns a provider whose restoration throws the supplied unchecked failure. */
    private static ContextPropagation failingRestore(Throwable failure) {
        return () -> ContextSnapshot.of(() -> () -> {
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            throw (RuntimeException) failure;
        });
    }

    /** Holds two independent installations concurrently before verifying each restoration backup. */
    private static void reuse(
            ContextSnapshot snapshot,
            ThreadLocal<String> local,
            String entering,
            CountDownLatch installed,
            CountDownLatch restore) {
        local.set(entering);
        try (ContextScope ignored = snapshot.install()) {
            assertThat(local.get()).isEqualTo("captured");
            installed.countDown();
            try {
                assertThat(restore.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            local.set("mutated " + entering);
        }
        assertThat(local.get()).isEqualTo(entering);
        snapshot.install().close();
        assertThat(local.get()).isEqualTo(entering);
    }
}
