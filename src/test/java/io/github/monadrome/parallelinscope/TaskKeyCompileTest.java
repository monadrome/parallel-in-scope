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

    private boolean compiles(String statements) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        String source = "import io.github.monadrome.parallelinscope.*;"
                + "class KeyConsumer { void use(ParRuntime runtime, Par par, GroupValues<?> values, TaskGroupResult<?, ?> result) {"
                + "TaskKey<Integer> count = TaskKey.of(\"count\", Integer.class);"
                + "TaskKey<java.util.List<String>> names = TaskKey.of(\"names\", new com.google.common.reflect.TypeToken<java.util.List<String>>() {});"
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
