package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskKeyCompileTest {
    @TempDir
    Path output;

    @Test
    void java8AcceptsGenericKeyBodiesAndTypedReads() throws Exception {
        assertThat(compiles(
                        "TaskGroupResult<java.util.List<String>, Integer> r = runtime.group(\"g\", java.time.Duration.ofSeconds(1))"
                                + ".par(names, par, () -> java.util.Arrays.asList(\"a\"))"
                                + ".combineValues(count, par, v -> v.valueOf(names).size()).runAll();"
                                + "java.util.List<String> n = r.valuesOrThrow().valueOf(names);"
                                + "ImmediateResult<Integer> i = r.resultOf(count);"))
                .isTrue();
    }

    @Test
    void java8RejectsBodiesThatDisagreeWithTheirKeyAndCannotWidenTheKeyType() throws Exception {
        assertThat(compiles("runtime.group(\"g\", java.time.Duration.ofSeconds(1)).par(count, par, () -> \"wrong\");"))
                .isFalse();
        assertThat(compiles("runtime.group(\"g\", java.time.Duration.ofSeconds(1))"
                        + ".par(names, par, () -> java.util.Arrays.asList(1));"))
                .isFalse();
    }

    @Test
    void java8RejectsWrongReadTypesAndWrongCombineResults() throws Exception {
        assertThat(compiles("String wrong = values.valueOf(count);")).isFalse();
        assertThat(compiles("ImmediateResult<String> wrong = result.resultOf(count);"))
                .isFalse();
        assertThat(compiles("runtime.group(\"g\", java.time.Duration.ofSeconds(1)).par(count, par, () -> 1)"
                        + ".combineValues(count, par, v -> \"wrong\");"))
                .isFalse();
    }

    @Test
    void java8AcceptsKeysInsideGenericMethodsWithoutReifyingTheType() throws Exception {
        assertThat(compiles("TaskKey<T> itemKey = TaskKey.named(\"item\");"
                        + "TaskKey<java.util.List<T>> listKey = TaskKey.named(\"list\");"
                        + "TaskGroupResult<T, java.util.List<T>> r = runtime.group(\"g\", java.time.Duration.ofSeconds(1))"
                        + ".par(itemKey, par, () -> item)"
                        + ".combine(listKey, par, v -> java.util.Collections.singletonList(v)).runAll();"
                        + "T v = r.valuesOrThrow().valueOf(itemKey);"
                        + "java.util.List<T> l = r.resultOf(listKey).valueOrThrow();"))
                .isTrue();
    }

    @Test
    void java8RejectsWrongLaterMembersAndTupleCombinesWithExplicitOptions() throws Exception {
        String start = "runtime.group(\"g\", java.time.Duration.ofSeconds(1)).par(count, par, () -> 1)";
        assertThat(compiles(
                        start + ".par(names, par, TaskOptions.inheritTimeout(), () -> java.util.Arrays.asList(1));"))
                .isFalse();
        assertThat(compiles(start + ".combine(names, par, TaskOptions.inheritTimeout(), v -> 1);"))
                .isFalse();
        assertThat(compiles("String wrong = values.valueOf(\"count\");")).isFalse();
        assertThat(compiles("ImmediateResult<String> wrong = result.resultAt(0);"))
                .isFalse();
    }

    private boolean compiles(String statements) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        String source = "import io.github.monadrome.parallelinscope.*;"
                + "class KeyConsumer { <T> void use(ParRuntime runtime, Par par, GroupValues<?> values, TaskGroupResult<?, ?> result, T item) throws Exception {"
                + "TaskKey<Integer> count = TaskKey.named(\"count\");"
                + "TaskKey<java.util.List<String>> names = TaskKey.named(\"names\");"
                + statements + "}}";
        JavaFileObject file =
                new SimpleJavaFileObject(URI.create("string:///KeyConsumer.java"), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return source;
                    }
                };
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, null)) {
            return compiler.getTask(
                            new StringWriter(),
                            manager,
                            null,
                            Arrays.asList(
                                    "--release",
                                    "8",
                                    "-proc:none",
                                    "-classpath",
                                    System.getProperty("java.class.path"),
                                    "-d",
                                    output.toString()),
                            null,
                            Collections.singletonList(file))
                    .call();
        }
    }
}
