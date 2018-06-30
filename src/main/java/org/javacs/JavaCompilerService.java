package org.javacs;

import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.MethodDoc;
import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

// TODO eliminate uses of URI in favor of Path
public class JavaCompilerService {
    private static final Logger LOG = Logger.getLogger("main");
    // Not modifiable! If you want to edit these, you need to create a new instance
    private final Set<Path> sourcePath, classPath, docPath;
    private final JavaCompiler compiler = ServiceLoader.load(JavaCompiler.class).iterator().next();
    private final Javadocs docs;
    private final ClassSource jdkClasses = Classes.jdkTopLevelClasses(), classPathClasses;
    // Diagnostics from the last compilation task
    private final List<Diagnostic<? extends JavaFileObject>> diags = new ArrayList<>();
    // Use the same file manager for multiple tasks, so we don't repeatedly re-compile the same files
    private final StandardJavaFileManager fileManager =
            compiler.getStandardFileManager(diags::add, null, Charset.defaultCharset());
    // Cache a single compiled file
    // Since the user can only edit one file at a time, this should be sufficient
    private Cache cache;

    public JavaCompilerService(Set<Path> sourcePath, Set<Path> classPath, Set<Path> docPath) {
        Class klass = compiler.getClass();
        URL location = klass.getResource('/' + klass.getName().replace('.', '/') + ".class");

        LOG.info("Creating a new compiler...");
        LOG.info("Source path:");
        for (Path p : sourcePath) {
            LOG.info("  " + p);
        }
        LOG.info("Class path:");
        for (Path p : classPath) {
            LOG.info("  " + p);
        }
        LOG.info("Doc path:");
        for (Path p : docPath) {
            LOG.info("  " + p);
        }
        // sourcePath and classPath can't actually be modified, because JavaCompiler remembers them from task to task
        this.sourcePath = Collections.unmodifiableSet(sourcePath);
        this.classPath = Collections.unmodifiableSet(classPath);
        this.docPath = docPath;
        this.docs = new Javadocs(sourcePath, docPath);
        this.classPathClasses = Classes.classPathTopLevelClasses(classPath);
    }

    private Set<String> subPackages(String parentPackage) {
        var result = new HashSet<String>();
        Consumer<String> checkClassName =
                name -> {
                    String packageName = Parser.mostName(name);
                    if (packageName.startsWith(parentPackage) && packageName.length() > parentPackage.length()) {
                        int start = parentPackage.length() + 1;
                        int end = packageName.indexOf('.', start);
                        if (end == -1) end = packageName.length();
                        String prefix = packageName.substring(0, end);
                        result.add(prefix);
                    }
                };
        for (var name : jdkClasses.classes()) checkClassName.accept(name);
        for (var name : classPathClasses.classes()) checkClassName.accept(name);
        return result;
    }

    /** Combine source path or class path entries using the system separator, for example ':' in unix */
    private static String joinPath(Collection<Path> classOrSourcePath) {
        return classOrSourcePath.stream().map(p -> p.toString()).collect(Collectors.joining(File.pathSeparator));
    }

    private static List<String> options(Set<Path> sourcePath, Set<Path> classPath) {
        return Arrays.asList(
                "-classpath",
                joinPath(classPath),
                "-sourcepath",
                joinPath(sourcePath),
                // "-verbose",
                "-proc:none",
                "-g",
                // You would think we could do -Xlint:all,
                // but some lints trigger fatal errors in the presence of parse errors
                "-Xlint:cast",
                "-Xlint:deprecation",
                "-Xlint:empty",
                "-Xlint:fallthrough",
                "-Xlint:finally",
                "-Xlint:path",
                "-Xlint:unchecked",
                "-Xlint:varargs",
                "-Xlint:static");
    }

    /** Create a task that compiles a single file */
    private JavacTask singleFileTask(URI file, String contents) {
        diags.clear();
        return (JavacTask)
                compiler.getTask(
                        null,
                        fileManager,
                        diags::add,
                        options(sourcePath, classPath),
                        Collections.emptyList(),
                        Collections.singletonList(new StringFileObject(contents, file)));
    }

    private JavacTask batchTask(Collection<Path> paths) {
        diags.clear();
        List<File> files = paths.stream().map(Path::toFile).collect(Collectors.toList());
        return (JavacTask)
                compiler.getTask(
                        null,
                        fileManager,
                        diags::add,
                        options(sourcePath, classPath),
                        Collections.emptyList(),
                        fileManager.getJavaFileObjectsFromFiles(files));
    }

    List<Diagnostic<? extends JavaFileObject>> lint(Collection<Path> files) {
        JavacTask task = batchTask(files);
        try {
            task.parse();
            task.analyze();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return Collections.unmodifiableList(new ArrayList<>(diags));
    }

    /** Stores the compiled version of a single file */
    class Cache {
        final String contents;
        final URI file;
        final CompilationUnitTree root;
        final JavacTask task;
        final int line, character;

        Cache(URI file, String contents, int line, int character) {
            // If `line` is -1, recompile the entire file
            if (line == -1) {
                this.contents = contents;
            }
            // Otherwise, focus on the block surrounding line:character,
            // erasing all other block bodies and everything after the cursor in its own block
            else {
                Pruner p = new Pruner(file, contents);
                p.prune(line, character);
                this.contents = p.contents();
            }
            this.file = file;
            this.task = singleFileTask(file, contents);
            try {
                this.root = task.parse().iterator().next();
                // The results of task.analyze() are unreliable when errors are present
                // You can get at `Element` values using `Trees`
                task.analyze();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            this.line = line;
            this.character = character;
        }
    }

    /** Recompile if the active file has been edited, or if the active file has changed */
    private void recompile(URI file, String contents, int line, int character) {
        if (cache == null
                || !cache.file.equals(file)
                || !cache.contents.equals(contents)
                || cache.line != line
                || cache.character != character) {
            cache = new Cache(file, contents, line, character);
        }
    }

    /** Find the smallest tree that includes the cursor */
    private TreePath path(URI file, int line, int character) {
        Trees trees = Trees.instance(cache.task);
        SourcePositions pos = trees.getSourcePositions();
        long cursor = cache.root.getLineMap().getPosition(line, character);

        // Search for the smallest element that encompasses line:column
        class FindSmallest extends TreePathScanner<Void, Void> {
            TreePath found = null;

            boolean containsCursor(Tree tree) {
                long start = pos.getStartPosition(cache.root, tree), end = pos.getEndPosition(cache.root, tree);
                // If element has no position, give up
                if (start == -1 || end == -1) return false;
                // Check if `tree` contains line:column
                return start <= cursor && cursor <= end;
            }

            @Override
            public Void scan(Tree tree, Void nothing) {
                // This is pre-order traversal, so the deepest element will be the last one remaining in `found`
                if (containsCursor(tree)) found = new TreePath(getCurrentPath(), tree);
                super.scan(tree, nothing);
                return null;
            }

            @Override
            public Void visitErroneous(ErroneousTree node, Void nothing) {
                for (Tree t : node.getErrorTrees()) {
                    t.accept(this, nothing);
                }
                return null;
            }

            Optional<TreePath> find(Tree root) {
                scan(root, null);
                return Optional.ofNullable(found);
            }
        }
        Supplier<RuntimeException> notFound =
                () -> {
                    String m = String.format("No TreePath to %s %d:%d", file, line, character);
                    return new RuntimeException(m);
                };
        return new FindSmallest().find(cache.root).orElseThrow(notFound);
    }

    /** Find all identifiers in scope at line:character */
    public List<Element> scopeMembers(URI file, String contents, int line, int character) {
        recompile(file, contents, line, character);

        Trees trees = Trees.instance(cache.task);
        Types types = cache.task.getTypes();
        TreePath path = path(file, line, character);
        Scope start = trees.getScope(path);

        class Walk {
            List<Element> result = new ArrayList<>();

            boolean isStatic(Scope s) {
                ExecutableElement method = s.getEnclosingMethod();
                if (method != null) {
                    return method.getModifiers().contains(Modifier.STATIC);
                } else return false;
            }

            boolean isStatic(Element e) {
                return e.getModifiers().contains(Modifier.STATIC);
            }

            boolean isThisOrSuper(VariableElement ve) {
                String name = ve.getSimpleName().toString();
                return name.equals("this") || name.equals("super");
            }

            // Place each member of `this` or `super` directly into `results`
            void unwrapThisSuper(VariableElement ve) {
                TypeMirror thisType = ve.asType();
                // `this` and `super` should always be instances of DeclaredType, which we'll use to check accessibility
                if (!(thisType instanceof DeclaredType)) {
                    LOG.warning(String.format("%s is not a DeclaredType", thisType));
                    return;
                }
                DeclaredType thisDeclaredType = (DeclaredType) thisType;
                Element thisElement = types.asElement(thisDeclaredType);
                for (Element thisMember : thisElement.getEnclosedElements()) {
                    if (isStatic(start) && !isStatic(thisMember)) continue;
                    if (thisMember.getSimpleName().contentEquals("<init>")) continue;

                    // Check if member is accessible from original scope
                    if (trees.isAccessible(start, thisMember, thisDeclaredType)) {
                        result.add(thisMember);
                    }
                }
            }

            // Place each member of `s` into results, and unwrap `this` and `super`
            void walkLocals(Scope s) {
                for (Element e : s.getLocalElements()) {
                    if (e instanceof TypeElement) {
                        TypeElement te = (TypeElement) e;
                        if (trees.isAccessible(start, te)) result.add(te);
                    } else if (e instanceof VariableElement) {
                        VariableElement ve = (VariableElement) e;
                        if (isThisOrSuper(ve)) {
                            unwrapThisSuper(ve);
                            if (!isStatic(s)) result.add(ve);
                        } else {
                            result.add(ve);
                        }
                    } else {
                        result.add(e);
                    }
                }
            }

            // Walk each enclosing scope, placing its members into `results`
            List<Element> walkScopes() {
                for (Scope s = start; s != null; s = s.getEnclosingScope()) {
                    walkLocals(s);
                }

                return result;
            }
        }
        return new Walk().walkScopes();
    }

    private List<TypeMirror> supersWithSelf(TypeMirror t) {
        Elements elements = cache.task.getElements();
        Types types = cache.task.getTypes();
        List<TypeMirror> result = new ArrayList<>();
        result.add(t);
        // Add members of superclasses and interfaces
        result.addAll(types.directSupertypes(t));
        // Object type is not included by default
        // We need to add it to get members like .equals(other) and .hashCode()
        // TODO this may add things twice for interfaces with no super-interfaces
        result.add(elements.getTypeElement("java.lang.Object").asType());
        return result;
    }

    private boolean hasMembers(TypeMirror type) {
        switch (type.getKind()) {
            case ARRAY:
            case DECLARED:
            case ERROR:
            case TYPEVAR:
            case WILDCARD:
            case UNION:
            case INTERSECTION:
                return true;
            case BOOLEAN:
            case BYTE:
            case SHORT:
            case INT:
            case LONG:
            case CHAR:
            case FLOAT:
            case DOUBLE:
            case VOID:
            case NONE:
            case NULL:
            case PACKAGE:
            case EXECUTABLE:
            case OTHER:
            default:
                return false;
        }
    }

    /** Find all members of expression ending at line:character */
    public List<Completion> members(URI file, String contents, int line, int character) {
        recompile(file, contents, line, character);

        Trees trees = Trees.instance(cache.task);
        Types types = cache.task.getTypes();
        TreePath path = path(file, line, character);
        Scope scope = trees.getScope(path);
        Element element = trees.getElement(path);

        if (element instanceof PackageElement) {
            List<Completion> result = new ArrayList<>();
            PackageElement p = (PackageElement) element;

            // Add class-names resolved as Element by javac
            for (Element member : p.getEnclosedElements()) {
                // If the package member is a TypeElement, like a class or interface, check if it's accessible
                if (member instanceof TypeElement) {
                    if (trees.isAccessible(scope, (TypeElement) member)) {
                        result.add(Completion.ofElement(member));
                    }
                }
                // Otherwise, just assume it's accessible and add it to the list
                else result.add(Completion.ofElement(member));
            }
            // Add sub-package names resolved as String by guava ClassPath
            String parent = p.getQualifiedName().toString();
            Set<String> subs = subPackages(parent);
            for (String sub : subs) {
                result.add(Completion.ofPackagePart(sub, Parser.lastName(sub)));
            }

            return result;
        } else if (element instanceof TypeElement) {
            List<Completion> result = new ArrayList<>();
            TypeElement t = (TypeElement) element;

            // Add static members
            for (Element member : t.getEnclosedElements()) {
                if (member.getModifiers().contains(Modifier.STATIC)
                        && trees.isAccessible(scope, member, (DeclaredType) t.asType())) {
                    result.add(Completion.ofElement(member));
                }
            }

            // Add .class
            result.add(Completion.ofClassSymbol(t));

            return result;
        } else {
            TypeMirror type = trees.getTypeMirror(path);
            if (hasMembers(type)) {
                List<Completion> result = new ArrayList<>();
                List<TypeMirror> ts = supersWithSelf(type);
                Set<String> alreadyAdded = new HashSet<>();
                for (TypeMirror t : ts) {
                    Element e = types.asElement(t);
                    for (Element member : e.getEnclosedElements()) {
                        // Don't add statics
                        if (member.getModifiers().contains(Modifier.STATIC)) continue;
                        // Don't add constructors
                        if (member.getSimpleName().contentEquals("<init>")) continue;
                        // Skip overridden members from superclass
                        if (alreadyAdded.contains(member.toString())) continue;

                        // If type is a DeclaredType, check accessibility of member
                        if (t instanceof DeclaredType) {
                            if (trees.isAccessible(scope, member, (DeclaredType) t)) {
                                result.add(Completion.ofElement(member));
                            }
                        }
                        // Otherwise, accessibility rules are very complicated
                        // Give up and just declare that everything is accessible
                        else result.add(Completion.ofElement(member));
                        // Remember the signature of the added method, so we don't re-add it later
                        alreadyAdded.add(member.toString());
                    }
                }
                return result;
            } else {
                LOG.warning("Don't know how to complete members of type " + type);
                return Collections.emptyList();
            }
        }
    }

    /**
     * Complete members or identifiers at the cursor. Delegates to `members` or `scopeMembers`, depending on whether the
     * expression before the cursor looks like `foo.bar` or `foo`
     */
    public CompletionResult completions(URI file, String contents, int line, int character, int limitHint) {
        LOG.info(String.format("Completing at %s[%d,%d]...", file.getPath(), line, character));
        // TODO why not just recompile? It's going to get triggered shortly anyway
        JavacTask task = singleFileTask(file, contents);
        CompilationUnitTree parse;
        try {
            parse = task.parse().iterator().next();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        SourcePositions pos = Trees.instance(task).getSourcePositions();
        LineMap lines = parse.getLineMap();
        long cursor = lines.getPosition(line, character);

        class Find extends TreeScanner<Void, Void> {
            List<Completion> result = null;
            boolean isIncomplete = false;

            boolean containsCursor(Tree node) {
                return pos.getStartPosition(parse, node) <= cursor && cursor <= pos.getEndPosition(parse, node);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void nothing) {
                super.visitMemberSelect(node, nothing);

                if (containsCursor(node) && !containsCursor(node.getExpression()) && result == null) {
                    LOG.info("...completing members of " + node.getExpression());
                    long offset = pos.getEndPosition(parse, node.getExpression()),
                            line = lines.getLineNumber(offset),
                            column = lines.getColumnNumber(offset);
                    result = members(file, contents, (int) line, (int) column);
                }
                return null;
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree node, Void nothing) {
                super.visitMemberReference(node, nothing);

                if (containsCursor(node) && !containsCursor(node.getQualifierExpression()) && result == null) {
                    LOG.info("...completing members of " + node.getQualifierExpression());
                    long offset = pos.getEndPosition(parse, node.getQualifierExpression()),
                            line = lines.getLineNumber(offset),
                            column = lines.getColumnNumber(offset);
                    result = members(file, contents, (int) line, (int) column);
                }
                return null;
            }

            @Override
            public Void visitIdentifier(IdentifierTree node, Void nothing) {
                super.visitIdentifier(node, nothing);

                if (containsCursor(node) && result == null) {
                    LOG.info("...completing identifiers");
                    result = new ArrayList<>();
                    // Does a candidate completion match the name in `node`?
                    String partialName = Objects.toString(node.getName(), "");
                    Set<String> alreadyImported = new HashSet<>();
                    // Add names that have already been imported
                    for (Element m : scopeMembers(file, contents, line, character)) {
                        if (m.getSimpleName().toString().startsWith(partialName)) {
                            result.add(Completion.ofElement(m));

                            if (m instanceof TypeElement) {
                                TypeElement t = (TypeElement) m;
                                alreadyImported.add(t.getQualifiedName().toString());
                            }
                        }
                    }
                    // Add names of classes that haven't been imported
                    String packageName = Objects.toString(parse.getPackageName(), "");
                    Predicate<String> matchesPartialName =
                            className -> Parser.lastName(className).startsWith(partialName);
                    Predicate<String> notAlreadyImported = className -> !alreadyImported.contains(className);
                    var fromJdk =
                            jdkClasses
                                    .classes()
                                    .stream()
                                    .filter(matchesPartialName)
                                    .filter(notAlreadyImported)
                                    .filter(c -> jdkClasses.isAccessibleFromPackage(c, packageName));
                    var fromClasspath =
                            classPathClasses
                                    .classes()
                                    .stream()
                                    .filter(matchesPartialName)
                                    .filter(notAlreadyImported)
                                    .filter(c -> classPathClasses.isAccessibleFromPackage(c, packageName));
                    var fromBoth = Stream.concat(fromJdk, fromClasspath).iterator();
                    while (fromBoth.hasNext() && result.size() < limitHint) {
                        var className = fromBoth.next();
                        var completion = Completion.ofNotImportedClass(className);
                        result.add(completion);
                    }
                    isIncomplete = fromBoth.hasNext();
                }
                return null;
            }

            @Override
            public Void visitErroneous(ErroneousTree node, Void nothing) {
                for (Tree t : node.getErrorTrees()) {
                    t.accept(this, null);
                }
                return null;
            }

            CompletionResult run() {
                scan(parse, null);
                if (result == null) result = Collections.emptyList();
                if (isIncomplete) LOG.info(String.format("Found %d items (incomplete)", result.size()));
                return new CompletionResult(result, isIncomplete);
            }
        }
        return new Find().run();
    }

    /** Find all overloads for the smallest method call that includes the cursor */
    public Optional<MethodInvocation> methodInvocation(URI file, String contents, int line, int character) {
        recompile(file, contents, line, character);

        Trees trees = Trees.instance(cache.task);
        TreePath start = path(file, line, character);

        for (TreePath path = start; path != null; path = path.getParentPath()) {
            if (path.getLeaf() instanceof MethodInvocationTree) {
                MethodInvocationTree invoke = (MethodInvocationTree) path.getLeaf();
                Element method = trees.getElement(trees.getPath(path.getCompilationUnit(), invoke.getMethodSelect()));
                List<ExecutableElement> results = new ArrayList<>();
                for (Element m : method.getEnclosingElement().getEnclosedElements()) {
                    if (m.getKind() == ElementKind.METHOD && m.getSimpleName().equals(method.getSimpleName())) {
                        results.add((ExecutableElement) m);
                    }
                }
                int activeParameter = invoke.getArguments().indexOf(start.getLeaf());
                Optional<ExecutableElement> activeMethod =
                        method instanceof ExecutableElement
                                ? Optional.of((ExecutableElement) method)
                                : Optional.empty();
                return Optional.of(new MethodInvocation(invoke, activeMethod, activeParameter, results));
            } else if (path.getLeaf() instanceof NewClassTree) {
                NewClassTree invoke = (NewClassTree) path.getLeaf();
                Element method = trees.getElement(path);
                List<ExecutableElement> results = new ArrayList<>();
                for (Element m : method.getEnclosingElement().getEnclosedElements()) {
                    if (m.getKind() == ElementKind.CONSTRUCTOR) {
                        results.add((ExecutableElement) m);
                    }
                }
                int activeParameter = invoke.getArguments().indexOf(start.getLeaf());
                Optional<ExecutableElement> activeMethod =
                        method instanceof ExecutableElement
                                ? Optional.of((ExecutableElement) method)
                                : Optional.empty();
                return Optional.of(new MethodInvocation(invoke, activeMethod, activeParameter, results));
            }
        }
        return Optional.empty();
    }

    /** Find the smallest element that includes the cursor */
    public Element element(URI file, String contents, int line, int character) {
        recompile(file, contents, line, character);

        Trees trees = Trees.instance(cache.task);
        TreePath path = path(file, line, character);
        return trees.getElement(path);
    }

    private Optional<TypeElement> topLevelDeclaration(Element e) {
        Element parent = e;
        TypeElement result = null;
        while (parent.getEnclosingElement() != null) {
            if (parent instanceof TypeElement) result = (TypeElement) parent;
            parent = parent.getEnclosingElement();
        }
        return Optional.ofNullable(result);
    }

    /** */
    private boolean containsTopLevelDeclaration(Path file, String simpleClassName) {
        Pattern find = Pattern.compile("\\b(class|interface|enum) +" + simpleClassName + "\\b");
        try (BufferedReader lines = Files.newBufferedReader(file)) {
            String line = lines.readLine();
            while (line != null) {
                if (find.matcher(line).find()) return true;
                line = lines.readLine();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return false;
    }

    /** Find the file `e` was declared in */
    private Optional<Path> findDeclaringFile(TypeElement e) {
        String name = e.getQualifiedName().toString();
        int lastDot = name.lastIndexOf('.');
        String packageName = lastDot == -1 ? "" : name.substring(0, lastDot);
        String className = name.substring(lastDot + 1);
        // First, look for a file named [ClassName].java
        Path packagePath = Paths.get(packageName.replace('.', File.separatorChar));
        Path publicClassPath = packagePath.resolve(className + ".java");
        for (Path root : sourcePath) {
            Path absPath = root.resolve(publicClassPath);
            if (Files.exists(absPath) && containsTopLevelDeclaration(absPath, className)) {
                return Optional.of(absPath);
            }
        }
        // Then, look for a secondary declaration in all java files in the package
        boolean isPublic = e.getModifiers().contains(Modifier.PUBLIC);
        if (!isPublic) {
            for (Path root : sourcePath) {
                Path absDir = root.resolve(packagePath);
                try {
                    Optional<Path> foundFile =
                            Files.list(absDir).filter(f -> containsTopLevelDeclaration(f, className)).findFirst();
                    if (foundFile.isPresent()) return foundFile;
                } catch (IOException err) {
                    throw new RuntimeException(err);
                }
            }
        }
        return Optional.empty();
    }

    /** Compile `file` and locate `e` in it */
    private Optional<TreePath> findIn(Element e, Path file, String contents) {
        JavacTask task = singleFileTask(file.toUri(), contents);
        CompilationUnitTree tree;
        try {
            tree = task.parse().iterator().next();
            task.analyze();
        } catch (IOException err) {
            throw new RuntimeException(err);
        }
        Trees trees = Trees.instance(task);
        class Find extends TreePathScanner<Void, Void> {
            Optional<TreePath> found = Optional.empty();

            boolean toStringEquals(Object left, Object right) {
                return Objects.equals(Objects.toString(left, ""), Objects.toString(right, ""));
            }

            /** Check if the declaration at the current path is the same symbol as `e` */
            boolean sameSymbol() {
                Element candidate = trees.getElement(getCurrentPath());
                // `e` is from a different compilation, so we have to compare qualified names
                return toStringEquals(candidate.getEnclosingElement(), e.getEnclosingElement())
                        && toStringEquals(candidate, e);
            }

            void check() {
                if (sameSymbol()) {
                    found = Optional.of(getCurrentPath());
                }
            }

            @Override
            public Void visitClass(ClassTree node, Void aVoid) {
                check();
                return super.visitClass(node, aVoid);
            }

            @Override
            public Void visitMethod(MethodTree node, Void aVoid) {
                check();
                return super.visitMethod(node, aVoid);
            }

            @Override
            public Void visitVariable(VariableTree node, Void aVoid) {
                check();
                return super.visitVariable(node, aVoid);
            }

            Optional<TreePath> run() {
                scan(tree, null);
                return found;
            }
        }
        return new Find().run();
    }

    public Optional<TreePath> definition(URI file, int line, int character, Function<URI, String> contents) {
        recompile(file, contents.apply(file), line, character);

        Trees trees = Trees.instance(cache.task);
        TreePath path = path(file, line, character);
        LOG.info("Looking for definition for " + path.getLeaf() + "...");
        Element e = trees.getElement(path);
        Optional<TypeElement> declaration = topLevelDeclaration(e);
        LOG.info("...looking for top-level declaration " + declaration);
        Optional<Path> declaringFile = declaration.flatMap(this::findDeclaringFile);
        LOG.info("...declaration is in " + declaringFile);
        return declaringFile.flatMap(f -> findIn(e, f, contents.apply(f.toUri())));
    }

    /** Look up the javadoc associated with `e` */
    public Optional<MethodDoc> methodDoc(ExecutableElement e) {
        return docs.methodDoc(e);
    }

    /** Look up the javadoc associated with `e` */
    public Optional<ClassDoc> classDoc(TypeElement e) {
        return docs.classDoc(e);
    }

    private Stream<Path> javaSourcesInDir(Path dir) {
        PathMatcher match = FileSystems.getDefault().getPathMatcher("glob:*.java");

        try {
            // TODO instead of looking at EVERY file, once you see a few files with the same source directory,
            // ignore all subsequent files in the directory
            return Files.walk(dir).filter(java -> match.matches(java.getFileName()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Stream<Path> javaSources() {
        return sourcePath.stream().flatMap(dir -> javaSourcesInDir(dir));
    }

    private List<Path> potentialReferences(Element to) {
        String name = to.getSimpleName().toString();
        Pattern word = Pattern.compile("\\b\\w+\\b");
        Predicate<String> containsWord =
                line -> {
                    Matcher m = word.matcher(line);
                    while (m.find()) {
                        if (m.group().equals(name)) return true;
                    }
                    return false;
                };
        Predicate<Path> test =
                file -> {
                    try {
                        return Files.readAllLines(file).stream().anyMatch(containsWord);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                };
        return javaSources().filter(test).collect(Collectors.toList());
    }

    /**
     * Represents a batch compilation of many files. The batch context is different that the incremental context, so
     * methods in this class should not access `cache`.
     */
    static class Batch {
        final JavacTask task;
        final List<CompilationUnitTree> roots;

        Batch(JavacTask task, List<CompilationUnitTree> roots) {
            this.task = task;
            this.roots = roots;
        }

        private boolean toStringEquals(Object left, Object right) {
            return Objects.equals(Objects.toString(left, ""), Objects.toString(right, ""));
        }

        private boolean sameSymbol(Element target, Element symbol) {
            return symbol != null
                    && target != null
                    && toStringEquals(symbol.getEnclosingElement(), target.getEnclosingElement())
                    && toStringEquals(symbol, target);
        }

        private List<TreePath> actualReferences(CompilationUnitTree from, Element to) {
            Trees trees = Trees.instance(task);

            class Finder extends TreeScanner<Void, Void> {
                List<TreePath> results = new ArrayList<>();

                @Override
                public Void scan(Tree leaf, Void nothing) {
                    if (leaf != null) {
                        TreePath path = trees.getPath(from, leaf);
                        Element found = trees.getElement(path);

                        if (sameSymbol(found, to)) results.add(path);
                        else super.scan(leaf, nothing);
                    }
                    return null;
                }

                List<TreePath> run() {
                    scan(from, null);
                    return results;
                }
            }
            return new Finder().run();
        }
    }

    private Batch compileBatch(List<Path> files) {
        JavacTask task = batchTask(files);

        List<CompilationUnitTree> result = new ArrayList<>();
        try {
            for (CompilationUnitTree t : task.parse()) result.add(t);
            task.analyze();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return new Batch(task, result);
    }

    public List<TreePath> references(URI file, String contents, int line, int character) {
        recompile(file, contents, -1, -1);

        Trees trees = Trees.instance(cache.task);
        TreePath path = path(file, line, character);
        // It's sort of odd that this works
        // `to` is part of a different batch than `batch = compileBatch(possible)`,
        // so `to.equals(...thing from batch...)` shouldn't work
        Element to = trees.getElement(path);
        List<Path> possible = potentialReferences(to);
        Batch batch = compileBatch(possible);
        List<TreePath> result = new ArrayList<>();
        for (CompilationUnitTree f : batch.roots) {
            result.addAll(batch.actualReferences(f, to));
        }
        return result;
    }

    public Stream<TreePath> findSymbols(String query) {
        return sourcePath.stream().flatMap(dir -> Parser.findSymbols(dir, query));
    }

    // TODO this is ugly, suggests something needs to be moved into JavaCompilerService
    public Trees trees() {
        return Trees.instance(cache.task);
    }

    /**
     * Figure out what imports this file should have. Star-imports like `import java.util.*` are converted to individual
     * class imports. Missing imports are inferred by looking at imports in other source files.
     */
    public FixImports fixImports(URI file, String contents) {
        LOG.info("Fix imports in " + file);
        // Compile a single file
        JavacTask task = singleFileTask(file, contents);
        CompilationUnitTree tree;
        try {
            tree = task.parse().iterator().next();
            task.analyze();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // Check diagnostics for missing imports
        Set<String> unresolved = new HashSet<>();
        for (Diagnostic<? extends JavaFileObject> d : diags) {
            if (d.getCode().equals("compiler.err.cant.resolve.location") && d.getSource().toUri().equals(file)) {
                long start = d.getStartPosition(), end = d.getEndPosition();
                String id = contents.substring((int) start, (int) end);
                if (id.matches("[A-Z]\\w+")) {
                    unresolved.add(id);
                } else LOG.warning(id + " doesn't look like a class");
            } else if (d.getMessage(null).contains("cannot find symbol")) {
                String[] lines = d.getMessage(null).split("\n");
                String firstLine = lines.length > 0 ? lines[0] : "";
                LOG.warning(String.format("%s %s doesn't look like symbol-not-found", d.getCode(), firstLine));
            }
        }
        // Look at imports in other classes to help us guess how to fix imports
        ExistingImports sourcePathImports = Parser.existingImports(sourcePath);
        var classes = new HashSet<String>();
        classes.addAll(jdkClasses.classes());
        classes.addAll(classPathClasses.classes());
        Map<String, String> fixes = Parser.resolveSymbols(unresolved, sourcePathImports, classes);
        // Figure out which existing imports are actually used
        Trees trees = Trees.instance(task);
        Set<String> references = new HashSet<>();
        class FindUsedImports extends TreePathScanner<Void, Void> {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void nothing) {
                Element e = trees.getElement(getCurrentPath());
                if (e instanceof TypeElement) {
                    TypeElement t = (TypeElement) e;
                    String qualifiedName = t.getQualifiedName().toString();
                    int lastDot = qualifiedName.lastIndexOf('.');
                    String packageName = lastDot == -1 ? "" : qualifiedName.substring(0, lastDot);
                    String thisPackage = Objects.toString(tree.getPackageName(), "");
                    // java.lang.* and current package are imported by default
                    if (!packageName.equals("java.lang")
                            && !packageName.equals(thisPackage)
                            && !packageName.equals("")) {
                        references.add(qualifiedName);
                    }
                }
                return null;
            }
        }
        new FindUsedImports().scan(tree, null);
        // Take the intersection of existing imports ^ existing identifiers
        Set<String> qualifiedNames = new HashSet<>();
        for (ImportTree i : tree.getImports()) {
            String imported = i.getQualifiedIdentifier().toString();
            if (references.contains(imported)) qualifiedNames.add(imported);
            else LOG.warning("There are no references to " + imported);
        }
        // Add qualified names from fixes
        qualifiedNames.addAll(fixes.values());
        return new FixImports(tree, trees.getSourcePositions(), qualifiedNames);
    }
}
