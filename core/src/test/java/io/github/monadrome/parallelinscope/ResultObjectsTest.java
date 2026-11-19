package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link ResultObjects} renders user objects by identity without calling their {@code toString}. */
class ResultObjectsTest {

    @Test
    void nullAndSelfReferencesRenderAsWords() {
        Object owner = new Object();

        assertThat(ResultObjects.render(null, owner)).isEqualTo("null");
        assertThat(ResultObjects.render(owner, owner)).isEqualTo("this future");
    }

    @Test
    void anObjectRendersAsClassAndIdentityHash() {
        Object value = new Object();

        assertThat(ResultObjects.render(value, this))
                .isEqualTo("Object@" + Integer.toHexString(System.identityHashCode(value)));
    }

    @Test
    void anAnonymousValueRendersWithItsBinaryNameInsteadOfAnEmptyPrefix() {
        Object value = new Object() {};

        assertThat(value.getClass().getSimpleName()).isEmpty();
        assertThat(ResultObjects.render(value, this))
                .isEqualTo(value.getClass().getName() + '@' + Integer.toHexString(System.identityHashCode(value)));
    }

    @Test
    void theObjectsOwnToStringIsNeverCalled() {
        Unrenderable value = new Unrenderable();

        assertThat(ResultObjects.render(value, this))
                .isEqualTo("Unrenderable@" + Integer.toHexString(System.identityHashCode(value)));
    }

    @Test
    void aFailureRendersAsItsTypeOnly() {
        assertThat(ResultObjects.renderFailure(null, this)).isEqualTo("null");
        IllegalStateException failure = new IllegalStateException("boom");
        assertThat(ResultObjects.renderFailure(failure, this)).isEqualTo("java.lang.IllegalStateException");
        assertThat(ResultObjects.renderFailure(failure, failure)).isEqualTo("this future");
    }

    @Test
    void aFailureMessageNeverReachesTheRendering() {
        Throwable secretBearing = new IllegalStateException("credentials=hunter2");
        assertThat(ResultObjects.renderFailure(secretBearing, this))
                .isEqualTo("java.lang.IllegalStateException")
                .doesNotContain("hunter2");
    }

    @Test
    void aFailureWhoseOwnRenderingThrowsStillRendersAsItsType() {
        Throwable unrenderable = new UnrenderableThrowable("boom");

        assertThat(ResultObjects.renderFailure(unrenderable, this)).isEqualTo(UnrenderableThrowable.class.getName());
    }

    private static final class Unrenderable {
        @Override
        public String toString() {
            throw new AssertionError("a diagnostic rendering must not call the object's toString");
        }
    }

    private static final class UnrenderableThrowable extends RuntimeException {
        UnrenderableThrowable(String message) {
            super(message);
        }

        @Override
        public String toString() {
            throw new AssertionError("a diagnostic rendering must not call the throwable's toString");
        }
    }
}
