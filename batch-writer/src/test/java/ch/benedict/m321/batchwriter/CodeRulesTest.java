package ch.benedict.m321.batchwriter;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import org.junit.jupiter.api.Test;

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
 * Prüft die Kommentarregel aus CLAUDE.md für jede Java-Datei dieses Moduls:
 * über jeder Klasse und jeder Methode steht ein Javadoc-Kommentar.
 *
 * Der Test liest die Dateien mit dem Java-Compiler selbst. Der kennt jede
 * Klasse und jede Methode, auch innere Klassen und Konstruktoren. Ein
 * Textvergleich mit regulären Ausdrücken würde hier Fälle übersehen.
 */
class CodeRulesTest {

    /** Maven startet die Tests im Modulverzeichnis, darum ist der Pfad relativ. */
    private static final File SOURCE_ROOT = new File("src");

    /**
     * Sammelt alle Stellen ohne Kommentar und meldet sie gemeinsam. So sieht
     * man bei einem Fehlschlag sofort jede Stelle und nicht nur die erste.
     */
    @Test
    void everyClassAndMethodHasJavadoc() throws IOException {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(SOURCE_ROOT, javaFiles);
        assertFalse(javaFiles.isEmpty(), "No Java files found below " + SOURCE_ROOT.getAbsolutePath());

        List<String> missingComments = findMissingComments(javaFiles);

        assertTrue(missingComments.isEmpty(), "Missing Javadoc: " + missingComments);
    }

    /** Durchsucht einen Ordner und alle Unterordner nach .java-Dateien. */
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
     * Lässt den Compiler die Dateien nur einlesen, nicht übersetzen, und geht
     * dann jede Klasse und jede Methode durch.
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
     * Läuft durch den Syntaxbaum einer Datei und notiert jede Klasse und jede
     * Methode, über der kein Javadoc steht.
     */
    private static class MissingCommentScanner extends TreePathScanner<Void, Void> {

        private final DocTrees docTrees;
        private final String fileName;
        private final List<String> missingComments;

        /** Merkt sich, wohin die Funde geschrieben werden. */
        MissingCommentScanner(DocTrees docTrees, CompilationUnitTree compilationUnit, List<String> missingComments) {
            this.docTrees = docTrees;
            JavaFileObject sourceFile = compilationUnit.getSourceFile();
            this.fileName = sourceFile.getName();
            this.missingComments = missingComments;
        }

        /** Prüft eine Klasse, ein Record oder eine innere Klasse. */
        @Override
        public Void visitClass(ClassTree classTree, Void unused) {
            if (!hasJavadoc()) {
                missingComments.add(fileName + ": class " + classTree.getSimpleName());
            }
            return super.visitClass(classTree, unused);
        }

        /** Prüft eine Methode oder einen Konstruktor. */
        @Override
        public Void visitMethod(MethodTree methodTree, Void unused) {
            if (!hasJavadoc()) {
                missingComments.add(fileName + ": method " + methodTree.getName());
            }
            return super.visitMethod(methodTree, unused);
        }

        /** Steht über dem Element, bei dem der Scanner gerade ist, ein Javadoc? */
        private boolean hasJavadoc() {
            TreePath currentPath = getCurrentPath();
            DocCommentTree docComment = docTrees.getDocCommentTree(currentPath);
            return docComment != null;
        }
    }
}
