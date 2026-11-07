package io.github.monadrome.queues;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PackageBoundaryTest {
    private static final String QUEUE_PACKAGE = "io.github.monadrome.queues";
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+);");
    private static final Pattern CORE_IMPORT =
            Pattern.compile("(?m)^import\\s+(?:static\\s+)?io\\.github\\.monadrome\\.parallelinscope");

    @Test
    void standaloneSourcesUseOnlyTheQueuePackage() throws IOException {
        Set<String> packages = new TreeSet<>();
        for (Path source : mainSources()) {
            Matcher declaration = PACKAGE.matcher(new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
            assertThat(declaration.find())
                    .as("package declaration in %s", source)
                    .isTrue();
            packages.add(declaration.group(1));
            assertThat(CORE_IMPORT
                            .matcher(new String(Files.readAllBytes(source), StandardCharsets.UTF_8))
                            .find())
                    .as("core import in %s", source)
                    .isFalse();
        }
        assertThat(packages).containsExactly(QUEUE_PACKAGE);
    }

    private static Set<Path> mainSources() throws IOException {
        Path root = Paths.get("src/main/java").resolve(QUEUE_PACKAGE.replace('.', '/'));
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }
}
