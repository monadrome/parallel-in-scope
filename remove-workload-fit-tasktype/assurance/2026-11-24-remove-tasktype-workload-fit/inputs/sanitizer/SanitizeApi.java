import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Prints the public API of a Java source tree from the javac syntax tree: public top-level types,
 * their public and protected members (every non-private member of an interface), with Javadoc,
 * annotations, modifiers, generics, and signatures. Method bodies, initializers of non-constant
 * fields, private and package-private members, and package-private types are omitted.
 *
 * <p>Usage: {@code java SanitizeApi.java <source-root> <output.md> <label>}
 */
public final class SanitizeApi {
    private final SourcePositions positions;
    private CompilationUnitTree unit;
    private String source;

    private SanitizeApi(SourcePositions positions) {
        this.positions = positions;
    }

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        String label = args[2];
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(path -> path.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        Iterable<? extends JavaFileObject> objects = manager.getJavaFileObjectsFromPaths(files);
        JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostic -> {}, List.of("-proc:none"), null, objects);
        List<CompilationUnitTree> units = new ArrayList<>();
        for (CompilationUnitTree tree : task.parse()) {
            units.add(tree);
        }
        SanitizeApi sanitizer = new SanitizeApi(Trees.instance(task).getSourcePositions());
        StringBuilder out = new StringBuilder();
        out.append("# Sanitized public API\n\n")
                .append("Source: ").append(label).append(".\n\n")
                .append("Generated from the javac syntax tree. Each block keeps one public top-level type with its\n")
                .append("public and protected members (every non-private member of an interface): Javadoc,\n")
                .append("annotations, modifiers, generics, and signatures. Method bodies, non-constant field\n")
                .append("initializers, private and package-private members, and package-private types are omitted;\n")
                .append("a Javadoc reference to an omitted type names an internal type that tests cannot use.\n");
        for (CompilationUnitTree tree : units) {
            String block = sanitizer.render(tree, root);
            if (!block.isEmpty()) {
                out.append('\n').append(block);
            }
        }
        Files.write(output, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String render(CompilationUnitTree tree, Path root) throws IOException {
        this.unit = tree;
        this.source = tree.getSourceFile().getCharContent(true).toString();
        String packageName = tree.getPackageName() == null ? "" : tree.getPackageName().toString();
        Path relative = root.toAbsolutePath().normalize()
                .relativize(Paths.get(tree.getSourceFile().toUri()).toAbsolutePath().normalize());
        boolean packageInfo = relative.getFileName().toString().equals("package-info.java");
        StringBuilder types = new StringBuilder();
        for (Tree declaration : tree.getTypeDecls()) {
            if (declaration instanceof ClassTree) {
                ClassTree type = (ClassTree) declaration;
                if (type.getModifiers().getFlags().contains(Modifier.PUBLIC)) {
                    renderType(types, type, "");
                }
            }
        }
        if (types.length() == 0 && !packageInfo) {
            return "";
        }
        StringBuilder block = new StringBuilder();
        block.append("## ").append(relative.toString().replace('\\', '/')).append("\n\n```java\n");
        if (packageInfo && tree.getPackage() != null) {
            appendJavadoc(block, tree.getPackage(), "");
        }
        block.append("package ").append(packageName).append(";\n");
        // Keep only the imports that the retained declarations or Javadoc name; static imports and
        // imports used only by omitted bodies would describe the implementation, not the API.
        List<String> imports = new ArrayList<>();
        String retained = types.toString();
        for (ImportTree imported : tree.getImports()) {
            if (imported.isStatic()) {
                continue;
            }
            String name = imported.getQualifiedIdentifier().toString();
            String simpleName = name.substring(name.lastIndexOf('.') + 1);
            if (retained.matches("(?s).*\\b" + java.util.regex.Pattern.quote(simpleName) + "\\b.*")) {
                imports.add("import " + name + ";");
            }
        }
        if (!imports.isEmpty()) {
            block.append('\n');
            for (String line : imports) {
                block.append(line).append('\n');
            }
        }
        if (types.length() > 0) {
            block.append('\n').append(types);
        }
        block.append("```\n");
        return block.toString();
    }

    private void renderType(StringBuilder out, ClassTree type, String indent) {
        appendJavadoc(out, type, indent);
        appendAnnotations(out, type.getModifiers(), indent);
        out.append(indent).append(flags(type.getModifiers()));
        switch (type.getKind()) {
            case INTERFACE:
                out.append("interface ");
                break;
            case ENUM:
                out.append("enum ");
                break;
            case ANNOTATION_TYPE:
                out.append("@interface ");
                break;
            default:
                out.append("class ");
        }
        out.append(type.getSimpleName()).append(typeParameters(type.getTypeParameters()));
        if (type.getExtendsClause() != null) {
            out.append(" extends ").append(type.getExtendsClause());
        }
        if (!type.getImplementsClause().isEmpty()) {
            out.append(type.getKind() == Tree.Kind.INTERFACE ? " extends " : " implements ")
                    .append(type.getImplementsClause().stream().map(Object::toString).collect(Collectors.joining(", ")));
        }
        out.append(" {\n");
        String inner = indent + "    ";
        boolean interfaceMembers = type.getKind() == Tree.Kind.INTERFACE || type.getKind() == Tree.Kind.ANNOTATION_TYPE;
        if (type.getKind() == Tree.Kind.ENUM) {
            List<VariableTree> constants = new ArrayList<>();
            for (Tree member : type.getMembers()) {
                if (member instanceof VariableTree && isEnumConstant((VariableTree) member, type)) {
                    constants.add((VariableTree) member);
                }
            }
            for (int index = 0; index < constants.size(); index++) {
                VariableTree constant = constants.get(index);
                appendJavadoc(out, constant, inner);
                appendAnnotations(out, constant.getModifiers(), inner);
                out.append(inner).append(constant.getName()).append(index + 1 < constants.size() ? ",\n" : ";\n");
            }
        }
        for (Tree member : type.getMembers()) {
            if (member instanceof VariableTree) {
                VariableTree field = (VariableTree) member;
                if (type.getKind() == Tree.Kind.ENUM && isEnumConstant(field, type)) {
                    continue;
                }
                if (visible(field.getModifiers(), interfaceMembers)) {
                    out.append('\n');
                    renderField(out, field, inner);
                }
            } else if (member instanceof MethodTree) {
                MethodTree method = (MethodTree) member;
                if (visible(method.getModifiers(), interfaceMembers) && !isGeneratedConstructor(method)) {
                    out.append('\n');
                    renderMethod(out, method, type, inner);
                }
            } else if (member instanceof ClassTree) {
                ClassTree nested = (ClassTree) member;
                if (visible(nested.getModifiers(), interfaceMembers)) {
                    out.append('\n');
                    renderType(out, nested, inner);
                }
            }
        }
        out.append(indent).append("}\n");
    }

    private void renderField(StringBuilder out, VariableTree field, String indent) {
        appendJavadoc(out, field, indent);
        appendAnnotations(out, field.getModifiers(), indent);
        out.append(indent).append(flags(field.getModifiers())).append(field.getType()).append(' ').append(field.getName());
        Set<Modifier> flags = field.getModifiers().getFlags();
        ExpressionTree initializer = field.getInitializer();
        if (initializer instanceof LiteralTree && flags.contains(Modifier.STATIC) && flags.contains(Modifier.FINAL)) {
            out.append(" = ").append(initializer);
        }
        out.append(";\n");
    }

    private void renderMethod(StringBuilder out, MethodTree method, ClassTree owner, String indent) {
        appendJavadoc(out, method, indent);
        appendAnnotations(out, method.getModifiers(), indent);
        out.append(indent).append(flags(method.getModifiers()));
        String typeParameters = typeParameters(method.getTypeParameters());
        if (!typeParameters.isEmpty()) {
            out.append(typeParameters).append(' ');
        }
        boolean constructor = method.getName().contentEquals("<init>");
        if (!constructor) {
            out.append(method.getReturnType()).append(' ').append(method.getName());
        } else {
            out.append(owner.getSimpleName());
        }
        out.append('(')
                .append(method.getParameters().stream().map(this::parameter).collect(Collectors.joining(", ")))
                .append(')');
        if (!method.getThrows().isEmpty()) {
            out.append(" throws ")
                    .append(method.getThrows().stream().map(Object::toString).collect(Collectors.joining(", ")));
        }
        if (method.getDefaultValue() != null) {
            out.append(" default ").append(method.getDefaultValue());
        }
        out.append(";\n");
    }

    private String parameter(VariableTree parameter) {
        StringBuilder text = new StringBuilder();
        for (Tree annotation : parameter.getModifiers().getAnnotations()) {
            text.append(annotation).append(' ');
        }
        if (parameter.getModifiers().getFlags().contains(Modifier.FINAL)) {
            text.append("final ");
        }
        String type = parameter.getType().toString();
        String declared = source.substring(
                (int) positions.getStartPosition(unit, parameter.getType()),
                (int) positions.getEndPosition(unit, parameter));
        if (declared.contains("...") && type.endsWith("[]")) {
            type = type.substring(0, type.length() - 2) + "...";
        }
        return text.append(type).append(' ').append(parameter.getName()).toString();
    }

    private static boolean visible(ModifiersTree modifiers, boolean interfaceMember) {
        Set<Modifier> flags = modifiers.getFlags();
        if (flags.contains(Modifier.PRIVATE)) {
            return false;
        }
        return interfaceMember || flags.contains(Modifier.PUBLIC) || flags.contains(Modifier.PROTECTED);
    }

    private boolean isGeneratedConstructor(MethodTree method) {
        return method.getName().contentEquals("<init>") && positions.getStartPosition(unit, method) < 0;
    }

    private boolean isEnumConstant(VariableTree field, ClassTree owner) {
        if (!(field.getInitializer() instanceof NewClassTree)) {
            return false;
        }
        long start = positions.getStartPosition(unit, field);
        return start >= 0 && source.startsWith(field.getName().toString(), (int) start)
                && field.getType().toString().equals(owner.getSimpleName().toString());
    }

    private static String flags(ModifiersTree modifiers) {
        StringBuilder text = new StringBuilder();
        for (Modifier flag : modifiers.getFlags()) {
            text.append(flag.toString()).append(' ');
        }
        return text.toString();
    }

    private static void appendAnnotations(StringBuilder out, ModifiersTree modifiers, String indent) {
        for (Tree annotation : modifiers.getAnnotations()) {
            out.append(indent).append(annotation).append('\n');
        }
    }

    private static String typeParameters(List<? extends TypeParameterTree> parameters) {
        if (parameters.isEmpty()) {
            return "";
        }
        return "<" + parameters.stream().map(Object::toString).collect(Collectors.joining(", ")) + ">";
    }

    /** Copies the Javadoc comment that immediately precedes {@code tree}, re-indented. */
    private void appendJavadoc(StringBuilder out, Tree tree, String indent) {
        long start = positions.getStartPosition(unit, tree);
        if (start <= 0) {
            return;
        }
        String before = source.substring(0, (int) start);
        String trimmed = before.replaceAll("\\s+$", "");
        if (!trimmed.endsWith("*/")) {
            return;
        }
        int open = trimmed.lastIndexOf("/**");
        if (open < 0 || trimmed.indexOf("*/", open) != trimmed.length() - 2) {
            return;
        }
        String[] lines = trimmed.substring(open).split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            out.append(indent).append(index == 0 ? "" : " ").append(line).append('\n');
        }
    }
}
