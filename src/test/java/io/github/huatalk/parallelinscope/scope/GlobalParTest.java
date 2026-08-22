package io.github.huatalk.parallelinscope.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.huatalk.parallelinscope.context.GlobalParObservationContext;
import io.github.huatalk.parallelinscope.context.graph.TaskGraph;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GlobalParTest {
  @Test
  void executorRuntimeIsPackagePrivateImplementationDetail() {
    assertThat(Modifier.isPublic(ExecutorRuntime.class.getModifiers())).isFalse();
  }

  @Test
  void buildsImmutableNamedEntriesAndSharesRuntimeBySuppliedIdentity() {
    ExecutorService executor = Executors.newFixedThreadPool(1);
    try {
      GlobalPar global =
          GlobalPar.builder()
              .register("one", executor)
              .register("same", executor)
              .defaultPar("one")
              .build();

      assertThat(global.defaultPar()).isSameAs(global.par("one"));
      assertThat(global.par("one").getDisplayName()).isEqualTo("one");
      assertThat(global.par("one").getGlobalPar()).isSameAs(global);
      assertThat(global.par("one").getRuntimeForTest())
          .isSameAs(global.par("same").getRuntimeForTest());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void rejectsUnknownDefaultAndDuplicateNames() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      assertThatThrownBy(() -> GlobalPar.builder().defaultPar("missing").build())
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> GlobalPar.builder().register("x", executor).register("x", executor))
          .isInstanceOf(IllegalArgumentException.class);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void executesUsingTheExecutorBoundAtBuildTime() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      GlobalPar global = GlobalPar.builder().register("io", executor).build();

      AsyncBatchResult<Integer> result =
          global
              .par("io")
              .map(
                  Collections.singletonList(2),
                  value -> value + 1,
                  ExecutionOptions.of("increment").build());

      assertThat(result.getResults().get(0).get()).isEqualTo(3);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void nestedBatchesAcrossExecutorsShareCancellationContextAndObservationGraph() throws Exception {
    ExecutorService outerExecutor = Executors.newSingleThreadExecutor();
    ExecutorService innerExecutor = Executors.newSingleThreadExecutor();
    GlobalPar global =
        GlobalPar.builder()
            .register("outer", outerExecutor)
            .register("inner", innerExecutor)
            .build();
    try (GlobalParObservationContext observation = global.openObservation()) {
      AsyncBatchResult<Integer> outer =
          global
              .par("outer")
              .map(
                  Collections.singletonList(2),
                  value -> {
                    AsyncBatchResult<Integer> inner =
                        global
                            .par("inner")
                            .map(
                                Collections.singletonList(value),
                                item -> item + 1,
                                ExecutionOptions.of("inner").build());
                    try {
                      return inner.getResults().get(0).get(2, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                      throw new RuntimeException(failure);
                    }
                  },
                  ExecutionOptions.of("outer").build());

      assertThat(outer.getResults().get(0).get(2, TimeUnit.SECONDS)).isEqualTo(3);
      assertThat(TaskGraph.data()).isSameAs(observation.data());
      assertThat(observation.data().getGraph().edges()).isNotEmpty();
    } finally {
      global.close();
      outerExecutor.shutdownNow();
      innerExecutor.shutdownNow();
    }
  }

  @Test
  void validatesPoliciesNamesAndStaticGlobalInstallation() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      GlobalExecutionPolicy policy =
          GlobalExecutionPolicy.builder().defaultTimeoutMillis(42).build();
      GlobalPar.Builder builder =
          GlobalPar.builder().executionPolicy(policy).register("io", executor);
      assertThatThrownBy(() -> builder.parPolicyOverride("missing", policy).build())
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  GlobalPar.builder()
                      .register("io", executor)
                      .parPolicyOverride("io", policy)
                      .parPolicyOverride("io", policy))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> GlobalPar.builder().register("", executor))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> GlobalPar.builder().register("null", null))
          .isInstanceOf(NullPointerException.class);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void installsAndReturnsTheProcessGlobalOnlyOnce() {
    GlobalPar installed = GlobalPar.builder().build();
    try {
      GlobalPar.installGlobal(installed);

      assertThat(GlobalPar.global()).isSameAs(installed);
      assertThatThrownBy(() -> GlobalPar.installGlobal(GlobalPar.builder().build()))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      installed.close();
    }
  }

  @Test
  void observationIsOwnedAndClosedExactlyOnce() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    GlobalPar global = GlobalPar.builder().register("io", executor).build();
    try {
      io.github.huatalk.parallelinscope.context.GlobalParObservationContext observation =
          global.openObservation();
      assertThat(observation.owner()).isSameAs(global);
      assertThat(observation.isClosed()).isFalse();
      observation.close();
      observation.close();
      assertThat(observation.isClosed()).isTrue();
    } finally {
      global.close();
      executor.shutdownNow();
    }
  }

  @Test
  void purgePolicyAndLivelockListenersAreImmutableAndIdentityDeduplicated() {
    AtomicInteger calls = new AtomicInteger();
    io.github.huatalk.parallelinscope.spi.LivelockListener listener =
        event -> calls.incrementAndGet();
    GlobalParLivelockPolicy livelock =
        GlobalParLivelockPolicy.builder()
            .enabled(true)
            .listener(listener)
            .listener(listener)
            .build();
    assertThat(livelock.listeners()).hasSize(1);
    assertThatThrownBy(() -> livelock.listeners().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    GlobalParPurgePolicy purge =
        GlobalParPurgePolicy.builder()
            .enabled(true)
            .queuePressureThreshold(1.0)
            .cancelledTaskRatioThreshold(0.5)
            .build();
    assertThat(purge.enabled()).isTrue();
    assertThat(purge.queuePressureThreshold()).isEqualTo(1.0);
    assertThat(purge.cancelledTaskRatioThreshold()).isEqualTo(0.5);
    assertThat(GlobalParPurgePolicy.builder().build().enabled()).isFalse();
    assertThat(GlobalParLivelockPolicy.builder().build().enabled()).isFalse();
  }

  @Test
  void exposesImmutableTopologyAndConfiguredPolicies() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      GlobalExecutionPolicy policy =
          GlobalExecutionPolicy.builder().defaultTimeoutMillis(123).build();
      GlobalParLivelockPolicy livelock = GlobalParLivelockPolicy.builder().enabled(true).build();
      GlobalParPurgePolicy purge = GlobalParPurgePolicy.builder().enabled(true).build();
      GlobalPar global =
          GlobalPar.builder()
              .executionPolicy(policy)
              .livelockPolicy(livelock)
              .purgePolicy(purge)
              .register("one", executor)
              .build();

      assertThat(global.executionPolicy()).isSameAs(policy);
      assertThat(global.executionPolicyFor("one")).isSameAs(policy);
      assertThat(global.livelockPolicy()).isSameAs(livelock);
      assertThat(global.purgePolicy()).isSameAs(purge);
      assertThat(global.find("one")).contains(global.par("one"));
      assertThat(global.find("missing")).isEmpty();
      assertThat(global.pars()).containsOnlyKeys("one");
      assertThat(global.runtimes()).containsOnlyKeys("one");
      assertThat(global.runtimesByIdentity()).hasSize(1);
      assertThatThrownBy(() -> global.pars().clear())
          .isInstanceOf(UnsupportedOperationException.class);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void executorRuntimeKeepsSuppliedIdentityAndCreatesOnlyNeededAdapter() {
    ExecutorService plain = Executors.newSingleThreadExecutor();
    com.google.common.util.concurrent.ListeningExecutorService listening =
        com.google.common.util.concurrent.MoreExecutors.listeningDecorator(
            Executors.newSingleThreadExecutor());
    try {
      ExecutorRuntime plainRuntime = new ExecutorRuntime(plain);
      ExecutorRuntime listeningRuntime = new ExecutorRuntime(listening);
      ExecutorIdentity samePlain = new ExecutorIdentity(plain);
      ExecutorIdentity different = new ExecutorIdentity(listening);

      assertThat(plainRuntime.suppliedExecutor()).isSameAs(plain);
      assertThat(plainRuntime.submissionExecutorIsAdapter()).isTrue();
      assertThat(listeningRuntime.submissionExecutor()).isSameAs(listening);
      assertThat(listeningRuntime.submissionExecutorIsAdapter()).isFalse();
      assertThat(plainRuntime.identity()).isEqualTo(samePlain).isNotEqualTo(different);
      assertThat(plainRuntime.identity().hashCode()).isEqualTo(samePlain.hashCode());
      assertThat(plainRuntime.identity().suppliedExecutor()).isSameAs(plain);
      assertThat(plainRuntime.identity().toString()).contains("@");
      assertThat(plainRuntime.blockingRisk()).isEqualTo(BlockingRisk.UNKNOWN);
    } finally {
      plain.shutdownNow();
      listening.shutdownNow();
    }
  }

  @Test
  void exposesImmutableGlobalTaskListeners() {
    io.github.huatalk.parallelinscope.spi.TaskListener listener = event -> {};
    GlobalExecutionPolicy policy = GlobalExecutionPolicy.builder().taskListener(listener).build();

    assertThat(policy.taskListeners()).containsExactly(listener);
    assertThatThrownBy(() -> policy.taskListeners().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
