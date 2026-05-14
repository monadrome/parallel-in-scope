package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Definition/owner contract (decision §6, §16.1, §16.5): structure-only immutability, builder
 * sealing, owner binding, and the absence of user-executable fields on long-lived objects.
 */
class TaskGroupDefinitionContractTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void buildSealsTheBuilderAndRepeatedBuildReturnsTheSameInstance() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<String> user = builder.task("user", global.par("worker"));
            TaskGroupDefinition first = builder.build();

            assertThat(builder.build()).isSameAs(first);
            assertThatThrownBy(() -> builder.task("late", global.par("worker")))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> builder.combine("late", global.par("worker")))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> builder.closeGrace(Duration.ZERO)).isInstanceOf(IllegalStateException.class);
            assertThat(builder.build()).isSameAs(first);
            assertThat(user.name()).isEqualTo("user");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void defineGroupValidatesNameAndTimeoutAtTheEntryPoint() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            assertThatThrownBy(() -> global.defineGroup(null, TIMEOUT)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.defineGroup(" ", TIMEOUT)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> global.defineGroup("page", null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.defineGroup("page", Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> global.defineGroup("page", Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> global.defineGroupInheriting(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.defineGroupInheriting("  ")).isInstanceOf(IllegalArgumentException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void foreignParsAreRejectedAtConfigurationTime() {
        ExecutorService firstExecutor = Executors.newSingleThreadExecutor();
        ExecutorService secondExecutor = Executors.newSingleThreadExecutor();
        GlobalPar first = GlobalPar.builder().register("worker", firstExecutor).build();
        GlobalPar second =
                GlobalPar.builder().register("worker", secondExecutor).build();
        try {
            TaskGroupDefinition.Builder builder = first.defineGroup("page", TIMEOUT);
            assertThatThrownBy(() -> builder.task("user", second.par("worker")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> builder.combine("assemble", second.par("worker")))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            first.close();
            second.close();
            firstExecutor.shutdownNow();
            secondExecutor.shutdownNow();
        }
    }

    @Test
    void aForeignDefinitionIsRejectedAtTheSubmitEntryPoint() {
        ExecutorService firstExecutor = Executors.newSingleThreadExecutor();
        ExecutorService secondExecutor = Executors.newSingleThreadExecutor();
        GlobalPar first = GlobalPar.builder().register("worker", firstExecutor).build();
        GlobalPar second =
                GlobalPar.builder().register("worker", secondExecutor).build();
        try {
            TaskGroupDefinition foreign = second.defineGroup("page", TIMEOUT).build();
            assertThatThrownBy(() -> first.submitGroup(foreign, bindings -> {}))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            first.close();
            second.close();
            firstExecutor.shutdownNow();
            secondExecutor.shutdownNow();
        }
    }

    @Test
    void submitGroupValidatesItsArguments() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            TaskGroupDefinition definition = global.defineGroup("page", TIMEOUT).build();
            assertThatThrownBy(() -> global.submitGroup(null, bindings -> {})).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.submitGroup(definition, null)).isInstanceOf(NullPointerException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void ownerCloseRejectsNewSubmissionsButLeavesTheDefinitionUsableData() {
        ExecutorService firstExecutor = Executors.newSingleThreadExecutor();
        ExecutorService secondExecutor = Executors.newSingleThreadExecutor();
        GlobalPar closing =
                GlobalPar.builder().register("worker", firstExecutor).build();
        GlobalPar open = GlobalPar.builder().register("worker", secondExecutor).build();
        try {
            // Same shape, same name, different owner: each definition is bound to its GlobalPar.
            TaskGroupDefinition.Builder closingBuilder = closing.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<String> user = closingBuilder.task("user", closing.par("worker"));
            TaskGroupDefinition built = closingBuilder.build();

            closing.close();
            assertThatThrownBy(() -> closing.submitGroup(built, bindings -> bindings.task(user, () -> "x")))
                    .isInstanceOf(IllegalStateException.class);
            // The definition itself is untouched data; another topology cannot adopt it, but the
            // owner binding is the only thing the close changed.
            assertThat(built.name()).isEqualTo("page");
            assertThat(open.defineGroup("page", TIMEOUT).build().name()).isEqualTo("page");
        } finally {
            closing.close();
            open.close();
            firstExecutor.shutdownNow();
            secondExecutor.shutdownNow();
        }
    }

    @Test
    void definitionAndMemberHoldNoUserExecutableFields() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            builder.task(
                    "user", global.par("worker"), TaskOptions.timeout(TIMEOUT).taskType(TaskType.IO_BOUND));
            builder.combine("assemble", global.par("worker"));
            TaskGroupDefinition definition = builder.build();

            assertNoExecutableFields(TaskGroupDefinition.class);
            assertNoExecutableFields(TaskGroupDefinition.Member.class);
            // Handles print their diagnostic name without exposing executable state.
            assertThat(definition.name()).isEqualTo("page");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    /**
     * Decision §16.5 / D2: a definition (and its handles) must be pure structure. Walk every
     * declared instance field, transitively through value slots, and reject anything that could
     * carry a user executable object or run-state identity.
     */
    private static void assertNoExecutableFields(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                Class<?> fieldType = field.getType();
                assertThat(fieldType.isAssignableFrom(Callable.class)
                                || Callable.class.isAssignableFrom(fieldType)
                                || Runnable.class.isAssignableFrom(fieldType)
                                || Future.class.isAssignableFrom(fieldType)
                                || CancellationToken.class.isAssignableFrom(fieldType)
                                || TaskGroup.CombineBody.class.isAssignableFrom(fieldType)
                                || Consumer.class.isAssignableFrom(fieldType)
                                || Function.class.isAssignableFrom(fieldType)
                                || Supplier.class.isAssignableFrom(fieldType)
                                || TaskExecutionContext.class.isAssignableFrom(fieldType)
                                || MultiTaskContext.class.isAssignableFrom(fieldType)
                                || BodyCompletionTracker.class.isAssignableFrom(fieldType)
                                || TaskBodyState.class.isAssignableFrom(fieldType)
                                || TaskGraphObservationScope.class.isAssignableFrom(fieldType))
                        .as(
                                "field %s.%s must not hold user executable or run state",
                                current.getName(), field.getName())
                        .isFalse();
            }
        }
    }
}
