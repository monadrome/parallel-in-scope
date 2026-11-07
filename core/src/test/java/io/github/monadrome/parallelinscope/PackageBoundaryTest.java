package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PackageBoundaryTest {
    private static final String ROOT = "io.github.monadrome.parallelinscope";
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+);");

    @Test
    void coreSourcesUseOnlyTheRootPackage() throws IOException {
        Set<String> packages = new TreeSet<>();
        for (Path source : mainSources()) {
            java.util.regex.Matcher declaration =
                    PACKAGE.matcher(new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
            assertThat(declaration.find())
                    .as("package declaration in %s", source)
                    .isTrue();
            packages.add(declaration.group(1));
        }

        assertThat(packages).containsExactly(ROOT);
    }

    private static Set<Path> mainSources() throws IOException {
        Path root = Paths.get("src/main/java").resolve(ROOT.replace('.', '/'));
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        }
    }
}
