package ch.benedict.m321.chatservice;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Name;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the comment rule from CLAUDE.md (scenario S8) for every Java file of
 * this module: every class and every method has a Javadoc comment.
 *
 * The test reads the files with the Java compiler itself. It knows every class
 * and method, including inner classes and constructors. A regular expression
 * would miss cases.
 */
class CodeRulesTest {

    /** Maven runs the tests in the module directory, so the path is relative. */
    private static final File SOURCE_ROOT = new File("src");

    /**
     * Collects every place without a comment and reports them together, so a
     * failure shows all of them at once and not only the first one.
     */
    @Test
    void everyClassAndMethodHasJavadoc() throws IOException {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(SOURCE_ROOT, javaFiles);
        assertFalse(javaFiles.isEmpty(), "No Java files found below " + SOURCE_ROOT.getAbsolutePath());

        List<String> missingComments = findMissingComments(javaFiles);

        assertTrue(missingComments.isEmpty(), "Missing Javadoc: " + missingComments);
    }

    /** Searches a directory and all its subdirectories for .java files. */
    private void collectJavaFiles(File directory, List<File> javaFiles) {
        File[] entries = directory.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (entry.isDirectory()) {
                collectJavaFiles(entry, javaFiles);
            } else if (entry.getName().endsWith(".java")) {
                javaFiles.add(entry);
            }
        }
    }

    /**
     * Lets the compiler only parse the files, not compile them, and then walks
     * through every class and method.
     */
    private List<String> findMissingComments(List<File> javaFiles) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<String> missingComments = new ArrayList<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromFiles(javaFiles);
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, null, null, null, sources);
            Iterable<? extends CompilationUnitTree> compilationUnits = task.parse();
            DocTrees docTrees = DocTrees.instance(task);
            for (CompilationUnitTree compilationUnit : compilationUnits) {
                MissingCommentScanner scanner = new MissingCommentScanner(docTrees, compilationUnit, missingComments);
                scanner.scan(compilationUnit, null);
            }
        }
        return missingComments;
    }

    /**
     * Walks through the syntax tree of one file and notes every class and
     * method without a Javadoc comment above it.
     */
    private static class MissingCommentScanner extends TreePathScanner<Void, Void> {

        private final DocTrees docTrees;
        private final String fileName;
        private final List<String> missingComments;

        /** Remembers where the findings are written to. */
        MissingCommentScanner(DocTrees docTrees, CompilationUnitTree compilationUnit, List<String> missingComments) {
            this.docTrees = docTrees;
            JavaFileObject sourceFile = compilationUnit.getSourceFile();
            this.fileName = sourceFile.getName();
            this.missingComments = missingComments;
        }

        /**
         * Checks a class, a record or an inner class. Anonymous classes such as
         * new ParameterizedTypeReference<>() {} are skipped: Java has no place
         * for a Javadoc comment above them.
         */
        @Override
        public Void visitClass(ClassTree classTree, Void unused) {
            Name className = classTree.getSimpleName();
            boolean anonymous = className.isEmpty();
            if (!anonymous && !hasJavadoc()) {
                missingComments.add(fileName + ": class " + className);
            }
            return super.visitClass(classTree, unused);
        }

        /** Checks a method or a constructor. */
        @Override
        public Void visitMethod(MethodTree methodTree, Void unused) {
            if (!hasJavadoc()) {
                missingComments.add(fileName + ": method " + methodTree.getName());
            }
            return super.visitMethod(methodTree, unused);
        }

        /** Is there a Javadoc comment above the element the scanner is at? */
        private boolean hasJavadoc() {
            TreePath currentPath = getCurrentPath();
            DocCommentTree docComment = docTrees.getDocCommentTree(currentPath);
            return docComment != null;
        }
    }
}
