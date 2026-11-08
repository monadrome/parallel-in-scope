package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Value-equality contract of {@link Tuple2}, the type every multi-member group hands its values
 * back as.
 *
 * <p>The rest of the suite only ever compares tuples expected to be equal, so the inequality half
 * of the contract — differing components, a non-tuple, null — had no coverage at all, and neither
 * did {@code hashCode} or {@code toString}. A value type whose {@code equals} answers true for
 * unequal components breaks every caller that puts a group result in a set or uses it as a map key,
 * and one whose {@code hashCode} ignores its components turns every such map into a linked list.
 */
class Tuple2Test {

    @Test
    void componentsAreReadBackInDeclarationOrder() {
        Tuple2<String, Integer> pair = Tuple2.of("alice", 41);
        assertThat(pair.first()).isEqualTo("alice");
        assertThat(pair.second()).isEqualTo(41);
    }

    @Test
    void equalComponentsCompareEqualAcrossDistinctInstances() {
        assertThat(Tuple2.of("alice", 41)).isEqualTo(Tuple2.of("alice", 41));
    }

    @Test
    void aTupleIsEqualToItself() {
        Tuple2<String, Integer> pair = Tuple2.of("alice", 41);
        // Reflexivity goes through the identity fast path, which is a branch of its own. The alias
        // is what keeps this an honest comparison of two references rather than a literal
        // self-comparison Error Prone rejects as a no-op assertion.
        Object sameInstance = pair;
        assertThat(pair).isEqualTo(sameInstance);
    }

    @Test
    void eitherComponentDifferingMakesTheTuplesUnequal() {
        assertThat(Tuple2.of("alice", 41)).isNotEqualTo(Tuple2.of("alice", 42));
        assertThat(Tuple2.of("alice", 41)).isNotEqualTo(Tuple2.of("bob", 41));
        assertThat(Tuple2.of("alice", 41)).isNotEqualTo(Tuple2.of("bob", 42));
    }

    @Test
    void nullComponentsCompareEqualToNullAndUnequalToAValue() {
        assertThat(Tuple2.of(null, null)).isEqualTo(Tuple2.of(null, null));
        assertThat(Tuple2.of("alice", null)).isEqualTo(Tuple2.of("alice", null));
        assertThat(Tuple2.of("alice", null)).isNotEqualTo(Tuple2.of("alice", 41));
        assertThat(Tuple2.of(null, 41)).isNotEqualTo(Tuple2.of("alice", 41));
    }

    @Test
    void neitherANonTupleNorNullIsEverEqual() {
        Tuple2<String, Integer> pair = Tuple2.of("alice", 41);
        // A type that merely prints the same must not compare equal to the tuple.
        assertThat(pair).isNotEqualTo("Tuple2[alice, 41]");
        assertThat(pair).isNotEqualTo(null);
    }

    @Test
    void hashCodeIsDerivedFromBothComponents() {
        assertThat(Tuple2.of("alice", 41).hashCode()).isEqualTo(Objects.hash("alice", 41));
        // The equals/hashCode agreement two distinct-but-equal tuples have to keep.
        assertThat(Tuple2.of("alice", 41).hashCode())
                .isEqualTo(Tuple2.of("alice", 41).hashCode());
        assertThat(Tuple2.of(null, null).hashCode()).isEqualTo(Objects.hash(null, null));
    }

    @Test
    void toStringNamesTheTypeAndBothComponents() {
        assertThat(Tuple2.of("alice", 41)).hasToString("Tuple2[alice, 41]");
        assertThat(Tuple2.of(null, null)).hasToString("Tuple2[null, null]");
    }
}
