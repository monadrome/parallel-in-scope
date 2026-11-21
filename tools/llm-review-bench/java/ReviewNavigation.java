import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.ReferenceTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.DocTreePath;
import com.sun.source.util.DocTreePathScanner;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.UnionType;
import javax.lang.model.type.WildcardType;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/** Emits source declarations and attributed references for a captured review snapshot. */
public final class ReviewNavigation {
    /** Keeps the source-file launcher entry point as the sole entry point. */
    private ReviewNavigation() {}

    /** Reads the source list and classpath without running builds or annotation processors. */
    public static void main(String[] args) throws IOException {
        var root = Path.of(args[0]);
        var paths = Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8).stream()
                .map(root::resolve)
                .toList();
        var classPath = Files.readAllLines(Path.of(args[2]), StandardCharsets.UTF_8).stream()
                .map(Path::of)
                .toList();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var output = new PrintWriter(System.out, false, StandardCharsets.UTF_8);
        try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, classPath);
            files.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
            var options = List.of("-proc:none", "--release", "11", "-Xmaxerrs", "10000");
            var task = (JavacTask) compiler.getTask(
                    null, files, diagnostics, options, null, files.getJavaFileObjectsFromPaths(paths));
            var units = new ArrayList<CompilationUnitTree>();
            task.parse().forEach(units::add);
            task.analyze();
            var errors = new Errors(diagnostics.getDiagnostics());
            output.println("errors\t" + errors.count);
            for (var unit : units) {
                new Scanner(root, unit, task, errors, output).scan(unit, null);
            }
        }
        output.flush();
    }

    /** Compiler errors with spans that can invalidate apparently resolved references. */
    private static final class Errors {
        private final Map<JavaFileObject, List<Diagnostic<? extends JavaFileObject>>> byFile = new HashMap<>();
        private int count;

        /** Indexes errors even when the compiler supplies no source position. */
        private Errors(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
            for (var diagnostic : diagnostics) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    count++;
                    byFile.computeIfAbsent(diagnostic.getSource(), key -> new ArrayList<>()).add(diagnostic);
                }
            }
        }

        /** Rejects an attributed call if a compiler error overlaps that expression. */
        private boolean overlaps(JavaFileObject file, long start, long end) {
            for (var diagnostic : byFile.getOrDefault(file, List.of())) {
                var errorStart = diagnostic.getStartPosition();
                var errorEnd = Math.max(errorStart + 1, diagnostic.getEndPosition());
                if (errorStart >= 0 && errorStart < end && errorEnd > start) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Visits one source file and emits line-backed references in compiler traversal order. */
    private static final class Scanner extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final JavacTask task;
        private final DocTrees trees;
        private final Errors errors;
        private final PrintWriter output;
        private final String file;
        private String owner = "";

        /** Resolves paths relative to the analysed snapshot. */
        private Scanner(Path root, CompilationUnitTree unit, JavacTask task, Errors errors, PrintWriter output) {
            this.unit = unit;
            this.task = task;
            this.trees = DocTrees.instance(task);
            this.errors = errors;
            this.output = output;
            this.file = root.relativize(Path.of(unit.getSourceFile().toUri())).toString().replace('\\', '/');
        }

        /** Gives members and nested declarations their nearest named source type. */
        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            var previous = owner;
            if (trees.getElement(getCurrentPath()) instanceof TypeElement type && !typeName(type).isEmpty()) {
                owner = typeName(type);
                declaration("type", tree, owner);
                documentation();
            }
            try {
                return super.visitClass(tree, unused);
            } finally {
                owner = previous;
            }
        }

        /** Records a method or constructor with the span of its source declaration. */
        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            if (trees.getElement(getCurrentPath()) instanceof ExecutableElement method) {
                var id = signature(method);
                if (id != null) {
                    declaration("member", tree, id);
                }
            }
            documentation();
            return super.visitMethod(tree, unused);
        }

        /** Records a type use written as a simple identifier. */
        @Override
        public Void visitIdentifier(IdentifierTree tree, Void unused) {
            typeUse(tree);
            return super.visitIdentifier(tree, unused);
        }

        /** Records a type use written as a qualified name. */
        @Override
        public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
            typeUse(tree);
            return super.visitMemberSelect(tree, unused);
        }

        /** Records the compile-time target of an ordinary or explicit constructor call. */
        @Override
        public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
            var select = new TreePath(getCurrentPath(), tree.getMethodSelect());
            var receiver = tree.getMethodSelect() instanceof MemberSelectTree member
                    ? new TreePath(select, member.getExpression())
                    : null;
            call("call", select, receiver, tree.getArguments());
            return super.visitMethodInvocation(tree, unused);
        }

        /** Records the constructor selected by a class instance creation expression. */
        @Override
        public Void visitNewClass(NewClassTree tree, Void unused) {
            var enclosing = tree.getEnclosingExpression();
            var receiver = enclosing == null ? null : new TreePath(getCurrentPath(), enclosing);
            call("new", getCurrentPath(), receiver, tree.getArguments());
            return super.visitNewClass(tree, unused);
        }

        /** Records the method or constructor selected by a reference expression. */
        @Override
        public Void visitMemberReference(MemberReferenceTree tree, Void unused) {
            var qualifier = new TreePath(getCurrentPath(), tree.getQualifierExpression());
            call("method reference", getCurrentPath(), qualifier, List.of());
            return super.visitMemberReference(tree, unused);
        }

        /** Emits source extent plus JavaDoc where it belongs to this declaration. */
        private void declaration(String kind, Tree tree, String id) {
            var positions = trees.getSourcePositions();
            var start = positions.getStartPosition(unit, tree);
            var end = positions.getEndPosition(unit, tree);
            if (start < 0 || end <= start) {
                return;
            }
            var doc = trees.getDocCommentTree(getCurrentPath());
            if (doc != null) {
                var docStart = positions.getStartPosition(unit, doc, doc);
                if (docStart >= 0) {
                    start = Math.min(start, docStart);
                }
            }
            output.printf("declaration\t%s\t%s\t%d\t%d\t%s%n", kind, file, line(start), line(end - 1), id);
        }

        /** Resolves explicit JavaDoc references to their declaring type. */
        private void documentation() {
            DocCommentTree doc = trees.getDocCommentTree(getCurrentPath());
            if (doc == null) {
                return;
            }
            var path = new DocTreePath(getCurrentPath(), doc);
            new DocTreePathScanner<Void, Void>() {
                /** Uses the JavaDoc reference site as evidence. */
                @Override
                public Void visitReference(ReferenceTree tree, Void unused) {
                    var target = declaringType(trees.getElement(getCurrentPath()));
                    var start = trees.getSourcePositions().getStartPosition(unit, doc, tree);
                    reference("Javadoc", start, target, "");
                    return super.visitReference(tree, unused);
                }
            }.scan(path, null);
        }

        /** Emits a resolved type-use edge without inferring a method call. */
        private void typeUse(Tree tree) {
            if (trees.getElement(getCurrentPath()) instanceof TypeElement type && type.asType().getKind() != TypeKind.ERROR) {
                reference("type use", trees.getSourcePositions().getStartPosition(unit, tree), type, "");
            }
        }

        /** Rejects javac recovery targets even when the diagnostic is at an operand's declaration. */
        private void call(String kind, TreePath target, TreePath receiver, List<? extends ExpressionTree> arguments) {
            if (!(trees.getElement(target) instanceof ExecutableElement method)) {
                return;
            }
            var site = getCurrentPath();
            var positions = trees.getSourcePositions();
            var start = positions.getStartPosition(unit, site.getLeaf());
            var end = positions.getEndPosition(unit, site.getLeaf());
            if (start < 0 || end <= start || errors.overlaps(unit.getSourceFile(), start, end)) {
                return;
            }
            if (unresolvedOperands(site, target, receiver, arguments)) {
                return;
            }
            var signature = signature(method);
            if (signature != null) {
                reference(kind, start, declaringType(method), signature);
            }
        }

        /** Checks the attributed types that can affect the binding of the call. */
        private boolean unresolvedOperands(
                TreePath site, TreePath target, TreePath receiver, List<? extends ExpressionTree> arguments) {
            var seen = new HashSet<TypeMirror>();
            return unresolved(site, seen)
                    || unresolved(target, seen)
                    || (receiver != null && unresolved(receiver, seen))
                    || arguments.stream().anyMatch(argument -> unresolved(new TreePath(site, argument), seen));
        }

        /** Missing expression attribution cannot support an exact target. */
        private boolean unresolved(TreePath path, Set<TypeMirror> seen) {
            var type = trees.getTypeMirror(path);
            return type == null || containsError(type, seen);
        }

        /** Keeps anonymous and broken target declarations out of cross-file references. */
        private void reference(String kind, long start, TypeElement target, String signature) {
            if (target == null || target.asType().getKind() == TypeKind.ERROR || owner.isEmpty() || start < 0) {
                return;
            }
            var name = typeName(target);
            if (!name.isEmpty()) {
                output.printf("reference\t%s\t%s\t%d\t%s\t%s\t%s%n", kind, file, line(start), owner, name, signature);
            }
        }

        /** Builds a stable overload key with erased parameter types. */
        private String signature(ExecutableElement method) {
            var type = declaringType(method);
            if (type == null || type.asType().getKind() == TypeKind.ERROR || typeName(type).isEmpty()) {
                return null;
            }
            var types = task.getTypes();
            var parameters = method.getParameters().stream().map(parameter -> types.erasure(parameter.asType())).toList();
            var seen = new HashSet<TypeMirror>();
            if (parameters.stream().anyMatch(parameter -> containsError(parameter, seen))) {
                return null;
            }
            return typeName(type) + "#" + method.getSimpleName() + "("
                    + parameters.stream().map(TypeMirror::toString).collect(Collectors.joining(", ")) + ")";
        }

        /** Inspects compound types while permitting recursive generic bounds and absent optional bounds. */
        private static boolean containsError(TypeMirror type, Set<TypeMirror> seen) {
            if (type == null || !seen.add(type)) {
                return false;
            }
            return switch (type.getKind()) {
                case ERROR -> true;
                case ARRAY -> containsError(((ArrayType) type).getComponentType(), seen);
                case DECLARED -> {
                    var declared = (DeclaredType) type;
                    yield containsError(declared.getEnclosingType(), seen)
                            || declared.getTypeArguments().stream().anyMatch(argument -> containsError(argument, seen));
                }
                case EXECUTABLE -> {
                    var executable = (ExecutableType) type;
                    yield containsError(executable.getReturnType(), seen)
                            || executable.getParameterTypes().stream().anyMatch(parameter -> containsError(parameter, seen));
                }
                case TYPEVAR -> {
                    var variable = (TypeVariable) type;
                    yield containsError(variable.getUpperBound(), seen) || containsError(variable.getLowerBound(), seen);
                }
                case WILDCARD -> {
                    var wildcard = (WildcardType) type;
                    yield containsError(wildcard.getExtendsBound(), seen) || containsError(wildcard.getSuperBound(), seen);
                }
                case INTERSECTION -> ((IntersectionType) type).getBounds().stream().anyMatch(bound -> containsError(bound, seen));
                case UNION -> ((UnionType) type).getAlternatives().stream().anyMatch(bound -> containsError(bound, seen));
                default -> false;
            };
        }

        /** Finds the declaring class for a member or a type reference. */
        private static TypeElement declaringType(Element element) {
            if (element instanceof TypeElement type) {
                return type;
            }
            return element != null && element.getEnclosingElement() instanceof TypeElement type ? type : null;
        }

        /** Uses Java's qualified source name, including nested type declarations. */
        private static String typeName(TypeElement type) {
            return type.getQualifiedName().toString();
        }

        /** Converts a compiler source offset to a one-based source line. */
        private long line(long position) {
            return unit.getLineMap().getLineNumber(position);
        }
    }
}
