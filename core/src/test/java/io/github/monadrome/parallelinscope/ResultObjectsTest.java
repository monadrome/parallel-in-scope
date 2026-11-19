package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link ResultObjects} renders user objects by identity without calling their {@code toString}. */
class ResultObjectsTest {

    @Test
    void nullAndSelfReferencesRenderAsWords() {
        Object owner = new Object();

        assertThat(ResultObjects.render(null, owner)).isEqualTo("null");
        assertThat(ResultObjects.render(owner, owner)).isEqualTo("this");
    }

    @Test
    void anObjectRendersAsClassAndIdentityHash() {
        Object value = new Object();

        assertThat(ResultObjects.render(value, this))
                .isEqualTo("Object@" + Integer.toHexString(System.identityHashCode(value)));
    }

    @Test
    void theObjectsOwnToStringIsNeverCalled() {
        Unrenderable value = new Unrenderable();

        assertThat(ResultObjects.render(value, this))
                .isEqualTo("Unrenderable@" + Integer.toHexString(System.identityHashCode(value)));
    }

    private static final class Unrenderable {
        @Override
        public String toString() {
            throw new AssertionError("a diagnostic rendering must not call the object's toString");
        }
    }
}
