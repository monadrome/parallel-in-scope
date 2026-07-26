package io.github.huatalk.parallelinscope;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import io.github.huatalk.parallelinscope.internal.ConcurrentLimitExecutor;
import io.github.huatalk.parallelinscope.scope.AsyncBatchResult;
import io.github.huatalk.parallelinscope.scope.ParOptions;
import io.github.huatalk.parallelinscope.scope.TaskType;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises admission position by task-type rejection behavior as a full Cartesian surface. */
public class ConcurrentLimitExecutorCartesianTest {

    private enum Admission {
        INITIAL,
        LATER
    }

    /** Builds every rejection-position and task-type combination. */
    private static Stream<Arguments> rejectionCases() {
        return Stream.of(Admission.values())
                .flatMap(admission -> Stream.of(TaskType.CPU_BOUND, TaskType.IO_BOUND)
                        .map(taskType -> Arguments.of(
                                Named.of(caseId(admission, taskType), admission), taskType)));
    }

    /** Verifies fallback, propagation, execution thread, and placeholder state for each case. */
    @ParameterizedTest(name = "{0};taskType={1}")
    @MethodSource("rejectionCases")
    public void rejectionBehaviorDependsOnAdmissionAndTaskType(
            Admission admission, TaskType taskType) throws Exception {
        int rejectedExecution = admission == Admission.INITIAL ? 1 : 2;
        RejectAtExecutorService rawWorker = new RejectAtExecutorService(rejectedExecution);
        ListeningExecutorService worker = MoreExecutors.listeningDecorator(rawWorker);
        ExecutorService rawSubmitter = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "cartesian-submitter"));
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(rawSubmitter);
        AtomicReference<String> rejectedTaskThread = new AtomicReference<>();
        String callerThread = Thread.currentThread().getName();

        try {
            ParOptions options = ParOptions.of("rejection-cartesian")
                    .parallelism(1)
                    .timeout(5000)
                    .taskType(taskType)
                    .build();
            ConcurrentLimitExecutor<Integer> executor =
                    ConcurrentLimitExecutor.create(worker, options, submitter);
            List<Callable<Integer>> tasks = admission == Admission.INITIAL
                    ? Arrays.asList(recordingTask(1, rejectedTaskThread))
                    : Arrays.asList(() -> 0, recordingTask(1, rejectedTaskThread));

            if (admission == Admission.INITIAL && taskType == TaskType.IO_BOUND) {
                assertThatThrownBy(() -> executor.submitAll(tasks))
                        .isInstanceOf(RejectedExecutionException.class);
                assertThat(rejectedTaskThread).hasNullValue();
                return;
            }

            AsyncBatchResult<Integer> result = executor.submitAll(tasks);
            if (taskType == TaskType.CPU_BOUND) {
                assertCpuFallback(admission, result, rejectedTaskThread, callerThread);
            } else {
                assertLaterIoRejection(result, rejectedTaskThread);
            }
        } finally {
            worker.shutdownNow();
            submitter.shutdownNow();
        }
    }

    /** Creates a task that records the thread used by CPU direct fallback. */
    private static Callable<Integer> recordingTask(
            int value, AtomicReference<String> executionThread) {
        return () -> {
            executionThread.set(Thread.currentThread().getName());
            return value;
        };
    }

    /** Asserts direct fallback runs at the phase-specific submission site. */
    private static void assertCpuFallback(
            Admission admission,
            AsyncBatchResult<Integer> result,
            AtomicReference<String> executionThread,
            String callerThread) throws Exception {
        ListenableFuture<Integer> target = result.getResults().get(result.getResults().size() - 1);
        assertThat(target.get(5, TimeUnit.SECONDS)).isOne();
        result.getSubmitCanceller().get(5, TimeUnit.SECONDS);
        if (admission == Admission.INITIAL) {
            assertThat(executionThread).hasValue(callerThread);
        } else {
            assertThat(executionThread.get()).startsWith("cartesian-submitter");
        }
    }

    /** Asserts later IO rejection fails the submitter while leaving its placeholder unresolved. */
    private static void assertLaterIoRejection(
            AsyncBatchResult<Integer> result,
            AtomicReference<String> executionThread) {
        assertThatThrownBy(() -> result.getSubmitCanceller().get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
        assertThat(result.getResults().get(1).isDone()).isFalse();
        assertThat(result.getResults().get(1).cancel(false)).isTrue();
        assertThat(executionThread).hasNullValue();
    }

    /** Returns a stable generated-case identity. */
    private static String caseId(Admission admission, TaskType taskType) {
        return "surface=rejection;admission=" + admission + ";taskType=" + taskType;
    }

    /** Runs accepted commands inline and rejects one configured execution ordinal. */
    private static final class RejectAtExecutorService extends AbstractExecutorService {
        private final int rejectedExecution;
        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicBoolean shutdown = new AtomicBoolean();

        /** Creates an executor that rejects the supplied one-based execution ordinal. */
        private RejectAtExecutorService(int rejectedExecution) {
            this.rejectedExecution = rejectedExecution;
        }

        /** Rejects one command and executes all other commands on the calling thread. */
        @Override
        public void execute(Runnable command) {
            if (shutdown.get() || executions.incrementAndGet() == rejectedExecution) {
                throw new RejectedExecutionException("rejected execution " + rejectedExecution);
            }
            command.run();
        }

        /** Marks this test executor shut down. */
        @Override
        public void shutdown() {
            shutdown.set(true);
        }

        /** Marks this test executor shut down and returns no queued commands. */
        @Override
        public List<Runnable> shutdownNow() {
            shutdown.set(true);
            return java.util.Collections.emptyList();
        }

        /** Reports whether shutdown was requested. */
        @Override
        public boolean isShutdown() {
            return shutdown.get();
        }

        /** Inline commands are terminal whenever this executor is shut down. */
        @Override
        public boolean isTerminated() {
            return shutdown.get();
        }

        /** Waits trivially because accepted commands execute inline. */
        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown.get();
        }
    }
}
