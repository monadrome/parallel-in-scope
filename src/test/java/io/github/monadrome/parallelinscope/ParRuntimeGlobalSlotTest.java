package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the global installation slot. Installing a closed {@link ParRuntime} must
 * fail without occupying the slot, {@link ParRuntime#close()} must always release its own slot, and
 * an install racing {@code close()} must never leave a shut-down instance installed.
 */
class ParRuntimeGlobalSlotTest {

    @AfterEach
    void resetGlobalSlot() {
        // Tests here intentionally push the static slot into states the unfixed implementation
        // cannot recover from; always hand it back empty so other tests are unaffected.
        installedSlot().set(null);
    }

    @Test
    void installingAClosedRuntimeFailsAndLeavesTheSlotFree() {
        ParRuntime a = ParRuntime.builder().build();
        a.close();

        assertThatThrownBy(() -> ParRuntime.installGlobal(a))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ParRuntime is closed");

        // A repeated close is a no-op and must not become the only release path: the slot stays
        // free and the process global stays uninstalled.
        a.close();
        assertThatThrownBy(ParRuntime::global).isInstanceOf(IllegalStateException.class);

        ParRuntime b = ParRuntime.builder().build();
        try {
            ParRuntime.installGlobal(b);
            assertThat(ParRuntime.global()).isSameAs(b);
        } finally {
            b.close();
        }
    }

    @Test
    void freshRuntimeInstallsAfterCloseOfTheProperlyInstalledInstance() {
        ParRuntime first = ParRuntime.builder().build();
        ParRuntime.installGlobal(first);
        first.close();

        ParRuntime second = ParRuntime.builder().build();
        try {
            ParRuntime.installGlobal(second);
            assertThat(ParRuntime.global()).isSameAs(second);
        } finally {
            second.close();
        }
    }

    @Test
    void installRacingCloseNeverLeavesTheClosedRuntimeInstalled() throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            ParRuntime a = ParRuntime.builder().build();
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<@Nullable Throwable> installFailure = new AtomicReference<>();
            Thread installer = new Thread(() -> {
                await(start);
                // Bias the installer to run just behind close(): on the unfixed implementation
                // this interleaving strands a closed instance in the slot.
                Thread.yield();
                try {
                    ParRuntime.installGlobal(a);
                } catch (Throwable t) {
                    installFailure.set(t);
                }
            });
            Thread closer = new Thread(() -> {
                await(start);
                a.close();
            });
            installer.setDaemon(true);
            closer.setDaemon(true);
            installer.start();
            closer.start();
            start.countDown();
            installer.join(5000);
            closer.join(5000);
            assertThat(installer.isAlive()).isFalse();
            assertThat(closer.isAlive()).isFalse();

            if (installFailure.get() != null) {
                assertThat(installFailure.get()).isInstanceOf(IllegalStateException.class);
            }
            assertThat(a.closed()).isTrue();
            // Combined invariant once the race settles: the slot never retains a shut-down
            // instance, so the close effectively released its own slot — directly or through the
            // install undoing itself.
            assertThat(installedSlot().get())
                    .withFailMessage("global slot retained a closed runtime at attempt %s", attempt)
                    .isNull();
        }
    }

    private static AtomicReference<@Nullable ParRuntime> installedSlot() {
        try {
            Field field = ParRuntime.class.getDeclaredField("INSTALLED");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            AtomicReference<@Nullable ParRuntime> slot = (AtomicReference<@Nullable ParRuntime>) field.get(null);
            return slot;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
