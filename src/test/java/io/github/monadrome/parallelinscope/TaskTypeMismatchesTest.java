package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
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

    /**
     * Racing first sightings of one pair must store it once and claim one slot: after 1024 such
     * races the memory holds exactly 1024 pairs, and only the next new pair overflows.
     */
    @Test
    void racingFirstSightingsOfOnePairClaimOneSlot() throws Exception {
        TaskTypeMismatches mismatches = new TaskTypeMismatches();
        int threads = 4;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<TaskTypeMismatches.Outcome>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    List<TaskTypeMismatches.Outcome> mine = new ArrayList<>();
                    for (int round = 0; round < TaskTypeMismatches.CAPACITY; round++) {
                        barrier.await(10, TimeUnit.SECONDS);
                        mine.add(mismatches.record(PAR, "unit-" + round));
                    }
                    return mine;
                }));
            }
            List<TaskTypeMismatches.Outcome> outcomes = new ArrayList<>();
            for (Future<List<TaskTypeMismatches.Outcome>> future : futures) {
                outcomes.addAll(future.get(60, TimeUnit.SECONDS));
            }

            assertThat(outcomes).doesNotContainNull().hasSize(threads * TaskTypeMismatches.CAPACITY);
            assertThat(outcomes)
                    .filteredOn(outcome -> outcome == TaskTypeMismatches.Outcome.FIRST)
                    .hasSize(TaskTypeMismatches.CAPACITY);
            assertThat(outcomes).doesNotContain(TaskTypeMismatches.Outcome.OVERFLOW);
            assertThat(mismatches.suppressed()).isZero();
            assertThat(mismatches.record(PAR, "one-more")).isEqualTo(TaskTypeMismatches.Outcome.OVERFLOW);
            assertThat(mismatches.suppressed()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Two new pairs, each admitted twice at once, race for the last free slot. The pair that wins is
     * remembered once; both admissions of the pair that loses are counted. Each admission is decided
     * against one consistent state, so the losing pair is never reported silent without being counted.
     */
    @Test
    void twoNewPairsRacingForTheLastSlotCountEveryAdmissionOfTheLoser() throws Exception {
        int rounds = 20_000;
        int threads = 4;
        List<TaskTypeMismatches> tables = new ArrayList<>(rounds);
        for (int round = 0; round < rounds; round++) {
            TaskTypeMismatches table = new TaskTypeMismatches(4);
            for (int i = 0; i < 3; i++) {
                table.record(PAR, "kept-" + i);
            }
            tables.add(table);
        }
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<TaskTypeMismatches.Outcome>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                String name = t < 2 ? "x" : "y";
                futures.add(pool.submit(() -> {
                    List<TaskTypeMismatches.Outcome> mine = new ArrayList<>(rounds);
                    for (TaskTypeMismatches table : tables) {
                        barrier.await(10, TimeUnit.SECONDS);
                        mine.add(table.record(PAR, name));
                    }
                    return mine;
                }));
            }
            List<List<TaskTypeMismatches.Outcome>> perThread = new ArrayList<>();
            for (Future<List<TaskTypeMismatches.Outcome>> future : futures) {
                perThread.add(future.get(120, TimeUnit.SECONDS));
            }

            for (int round = 0; round < rounds; round++) {
                List<TaskTypeMismatches.Outcome> outcomes = new ArrayList<>();
                for (List<TaskTypeMismatches.Outcome> mine : perThread) {
                    outcomes.add(mine.get(round));
                }
                assertThat(outcomes)
                        .as("round %s", round)
                        .containsExactlyInAnyOrder(
                                TaskTypeMismatches.Outcome.FIRST,
                                TaskTypeMismatches.Outcome.SILENT,
                                TaskTypeMismatches.Outcome.SILENT,
                                TaskTypeMismatches.Outcome.OVERFLOW);
                assertThat(tables.get(round).suppressed()).as("round %s", round).isEqualTo(2);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aCapacityMustBePositive() {
        assertThatThrownBy(() -> new TaskTypeMismatches(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("capacity 0 must be positive");
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
