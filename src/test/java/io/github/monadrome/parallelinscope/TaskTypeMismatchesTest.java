package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The bounded once-per-pair memory behind submission-time {@link TaskType} mismatch logging. */
class TaskTypeMismatchesTest {
    private static final ParId PAR = ParId.of("cpu");

    @Test
    void aPairIsReportedOnceAndADifferentEntryIsADifferentPair() {
        TaskTypeMismatches mismatches = new TaskTypeMismatches();

        assertThat(mismatches.record(PAR, "load")).isEqualTo(TaskTypeMismatches.Outcome.FIRST);
        assertThat(mismatches.record(PAR, "load")).isEqualTo(TaskTypeMismatches.Outcome.SILENT);
        assertThat(mismatches.record(ParId.of("io"), "load")).isEqualTo(TaskTypeMismatches.Outcome.FIRST);
        assertThat(mismatches.suppressed()).isZero();
    }

    @Test
    void aFullMemoryReportsTheOverflowOnceAndCountsEveryNewPairAdmission() {
        TaskTypeMismatches mismatches = new TaskTypeMismatches();
        for (int i = 0; i < TaskTypeMismatches.CAPACITY; i++) {
            assertThat(mismatches.record(PAR, "unit-" + i)).isEqualTo(TaskTypeMismatches.Outcome.FIRST);
        }

        assertThat(mismatches.record(PAR, "late-a")).isEqualTo(TaskTypeMismatches.Outcome.OVERFLOW);
        assertThat(mismatches.record(PAR, "late-a")).isEqualTo(TaskTypeMismatches.Outcome.SILENT);
        assertThat(mismatches.record(PAR, "late-b")).isEqualTo(TaskTypeMismatches.Outcome.SILENT);
        assertThat(mismatches.record(PAR, "unit-7")).isEqualTo(TaskTypeMismatches.Outcome.SILENT);
        assertThat(mismatches.suppressed()).isEqualTo(3);
    }

    @Test
    void concurrentRecordersOfOnePairReportItExactlyOnce() throws Exception {
        TaskTypeMismatches mismatches = new TaskTypeMismatches();
        List<TaskTypeMismatches.Outcome> outcomes = recordConcurrently(mismatches, 16, 200, thread -> "shared");

        assertThat(outcomes)
                .filteredOn(outcome -> outcome == TaskTypeMismatches.Outcome.FIRST)
                .hasSize(1);
        assertThat(outcomes).doesNotContain(TaskTypeMismatches.Outcome.OVERFLOW);
        assertThat(mismatches.suppressed()).isZero();
    }

    @Test
    void concurrentRecordersNeverStoreMoreThanTheCapacityAndOverflowOnce() throws Exception {
        TaskTypeMismatches mismatches = new TaskTypeMismatches();
        int threads = 16;
        int perThread = 200;
        List<TaskTypeMismatches.Outcome> outcomes =
                recordConcurrently(mismatches, threads, perThread, thread -> "unit-" + thread + "-");

        long first = outcomes.stream()
                .filter(outcome -> outcome == TaskTypeMismatches.Outcome.FIRST)
                .count();
        long overflow = outcomes.stream()
                .filter(outcome -> outcome == TaskTypeMismatches.Outcome.OVERFLOW)
                .count();
        assertThat(first).isEqualTo(TaskTypeMismatches.CAPACITY);
        assertThat(overflow).isEqualTo(1);
        assertThat(mismatches.suppressed()).isEqualTo((long) threads * perThread - TaskTypeMismatches.CAPACITY);
    }

    private static List<TaskTypeMismatches.Outcome> recordConcurrently(
            TaskTypeMismatches mismatches, int threads, int perThread, NamePrefix prefix) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<TaskTypeMismatches.Outcome>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                String base = prefix.of(t);
                futures.add(pool.submit(() -> {
                    start.await();
                    List<TaskTypeMismatches.Outcome> mine = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        mine.add(mismatches.record(PAR, base.equals("shared") ? base : base + i));
                    }
                    return mine;
                }));
            }
            start.countDown();
            List<TaskTypeMismatches.Outcome> all = new ArrayList<>();
            for (Future<List<TaskTypeMismatches.Outcome>> future : futures) {
                all.addAll(future.get(30, TimeUnit.SECONDS));
            }
            return all;
        } finally {
            pool.shutdownNow();
        }
    }

    private interface NamePrefix {
        String of(int thread);
    }
}
