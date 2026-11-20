package io.github.monadrome.parallelinscope;

/**
 * The SLF4J level of an execution failure that arrived after its future settled as cancelled:
 * {@link #DEBUG} for a cancellation acknowledgement, {@link #WARN} for every other late failure.
 * Chosen by {@code ExecutionPhaseHintFuture} from the failure's provenance.
 */
enum LateFailureLevel {
    /** A cancellation acknowledgement: no warning, because the cancellation already explains it. */
    DEBUG,
    /** Any other late failure: a real problem the cancellation did not cause. */
    WARN
}
