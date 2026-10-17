package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Pins which identity space each observation record belongs to: a task unit and a task-group run
 * are separate spaces, and a name is neither.
 */
class TaskIdentityTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void aBatchSharesOneUnitIdAcrossItsElementsAndClaimsNoGroup() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        try {
            TaskBatchResult<String> batch = global.par(ParId.of("worker"))
                    .map(Arrays.asList("a", "b"), item -> item, BatchOptions.timeout("batch", TIMEOUT));

            List<@Nullable TaskCompletion<String>> completions = batch.completions();

            assertThat(completions).hasSize(2);
            TaskId<TaskUnit> unitId = completions.get(0).unitId();
            assertThat(unitId).isNotNull();
            assertThat(completions).allSatisfy(completion -> {
                assertThat(completion.unitId()).isEqualTo(unitId);
                assertThat(completion.groupId()).isNull();
            });
        } finally {
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void aGroupRunIsNotAUnitSoItsSummaryCarriesOnlyAGroupId() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        try {
            TaskGroup<String, Void> group = global.groupDraft("page", TIMEOUT)
                    .par(TaskKey.<String>named("user"), global.par(ParId.of("worker")), () -> "alice")
                    .submitAll();

            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);
            TaskCompletion<TaskGroupReport> summary =
                    group.completionFuture().completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(summary.groupId()).isEqualTo(group.groupId()).isEqualTo(result.groupId());
            assertThat(summary.unitId()).isNull();
        } finally {
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void aGroupMemberCarriesAUnitIdThatIsNeverTheGroupId() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        try {
            TaskGroup<String, Void> group = global.groupDraft("page", TIMEOUT)
                    .par(TaskKey.<String>named("user"), global.par(ParId.of("worker")), () -> "alice")
                    .submitAll();
            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.members()).isNotEmpty();
            assertThat(result.members().values()).allSatisfy(member -> {
                assertThat(member.unitId()).isNotNull();
                assertThat(member.groupId()).isNull();
                assertThat((Object) member.unitId()).isNotEqualTo((Object) result.groupId());
            });
        } finally {
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void aDerivedViewHasNoUnitIdentityToCarry() throws Exception {
        CancellationToken token = new CancellationToken();
        SettableFuture<String> delegate = SettableFuture.create();
        Task<String> derived =
                Task.of("base", token, delegate).transform(value -> value + "!", MoreExecutors.directExecutor());
        delegate.set("value");

        TaskCompletion<String> snapshot = derived.completionFuture().get(2, TimeUnit.SECONDS);

        assertThat(snapshot.taskName()).isEqualTo("base");
        assertThat(snapshot.unitId()).isNull();
        assertThat(snapshot.groupId()).isNull();
    }

    @Test
    void aSyntheticRecordKeepsTheIdTheCallerSupplied() {
        TaskCompletion<String> completion = TaskCompletion.succeeded("task", TaskId.unit("1"), 0, 10, 30, 80, "value");

        assertThat(completion.unitId()).isEqualTo(TaskId.unit("1"));
        assertThat(completion.groupId()).isNull();
    }
}
