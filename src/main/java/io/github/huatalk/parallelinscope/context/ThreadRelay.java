package io.github.huatalk.parallelinscope.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import io.github.huatalk.parallelinscope.cancel.CancellationToken;
import io.github.huatalk.parallelinscope.scope.ExecutorIdentity;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Relays task cancellation and diagnostic identity across TTL boundaries. */
public final class ThreadRelay {
    private enum Item {
        TOKEN,
        TASK,
        EXECUTOR,
        IDENTITY
    }

    private static final ThreadLocal<ThreadRelay> TL = ThreadLocal.withInitial(ThreadRelay::new);

    static {
        TransmittableThreadLocal.Transmitter.registerThreadLocal(TL, relay -> new ThreadRelay(relay.current));
    }

    private final Map<Item, Object> parent = new ConcurrentHashMap<>();
    private final Map<Item, Object> current = new ConcurrentHashMap<>();

    public ThreadRelay() {}

    private ThreadRelay(Map<Item, Object> inherited) {
        if (inherited != null) parent.putAll(inherited);
    }

    public static ThreadRelay getThreadRelay() {
        return TL.get();
    }

    public static @Nullable CancellationToken getParentCancellationToken() {
        return token(TL.get().parent.get(Item.TOKEN));
    }

    public static void setCurrentCancellationToken(@Nullable CancellationToken value) {
        put(Item.TOKEN, value);
    }

    public static @Nullable CancellationToken getCurrentCancellationToken() {
        return token(TL.get().current.get(Item.TOKEN));
    }

    public static void setCurrentTaskName(@Nullable String value) {
        TL.get().current.put(Item.TASK, Optional.ofNullable(value));
    }

    public static String getCurrentTaskName() {
        return string(TL.get().current.get(Item.TASK));
    }

    public static void setCurrentExecutorName(@Nullable String value) {
        TL.get().current.put(Item.EXECUTOR, Optional.ofNullable(value));
    }

    public static String getCurrentExecutorName() {
        return string(TL.get().current.get(Item.EXECUTOR));
    }

    public static void setCurrentExecutorIdentity(@Nullable ExecutorIdentity value) {
        TL.get().current.put(Item.IDENTITY, Optional.ofNullable(value));
    }

    public static @Nullable ExecutorIdentity getCurrentExecutorIdentity() {
        return identity(TL.get().current.get(Item.IDENTITY));
    }

    public static @Nullable ExecutorIdentity getParentExecutorIdentity() {
        return identity(TL.get().parent.get(Item.IDENTITY));
    }

    public static void restoreCurrent(
            @Nullable CancellationToken token,
            @Nullable String task,
            @Nullable String executor,
            @Nullable ExecutorIdentity identity) {
        setCurrentCancellationToken(token);
        setCurrentTaskName(task);
        setCurrentExecutorName(executor);
        setCurrentExecutorIdentity(identity);
    }

    public static void clearCurrent() {
        TL.get().current.clear();
    }

    private static void put(Item item, @Nullable Object value) {
        if (value == null) TL.get().current.remove(item);
        else TL.get().current.put(item, value);
    }

    private static @Nullable CancellationToken token(Object value) {
        return value instanceof CancellationToken ? (CancellationToken) value : null;
    }

    private static String string(Object value) {
        if (value instanceof Optional) {
            Object v = ((Optional<?>) value).orElse(null);
            return v instanceof String ? (String) v : "NA";
        }
        return value instanceof String ? (String) value : "NA";
    }

    private static @Nullable ExecutorIdentity identity(Object value) {
        if (value instanceof Optional) {
            Object v = ((Optional<?>) value).orElse(null);
            return v instanceof ExecutorIdentity ? (ExecutorIdentity) v : null;
        }
        return value instanceof ExecutorIdentity ? (ExecutorIdentity) value : null;
    }
}
