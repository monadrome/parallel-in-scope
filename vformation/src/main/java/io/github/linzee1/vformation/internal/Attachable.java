package io.github.linzee1.vformation.internal;

/**
 * Marker interface for Runnable/Callable with key-value attachment support.
 * <p>
 * Provides a lightweight Map-like contract for attaching metadata to task wrappers,
 * enabling context passing between task submission and execution phases.
 *
 * @author linqh (linqinghua4 at gmail dot com)
 */
public interface Attachable {

    /**
     * Returns the value associated with the specified key,
     * or {@code null} if no mapping exists.
     *
     * @param key the key whose associated value is to be returned
     * @return the associated value, or {@code null}
     */
    Object get(String key);

    /**
     * Associates the specified value with the specified key.
     * If a previous mapping existed, the old value is replaced.
     *
     * @param key   key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value, or {@code null} if there was no mapping
     */
    Object put(String key, Object value);
}
