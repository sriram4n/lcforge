package lcforge.judge;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Compiles a workspace with the in-process javac from {@code jdk.compiler}.
 *
 * Output goes to disk rather than memory on purpose: the JDI tracer launches a separate JVM that
 * needs real .class files, and having the sources on disk means you can open the same workspace
 * in an IDE. The compile itself costs ~50ms, so nothing is gained by keeping bytecode in RAM.
 */
public final class Compile {

    public static final class Diagnostic {
        public final String kind;     // "error" | "warning" | "note"
        public final String file;
        public final long line;
        public final long col;
        public final String message;

        Diagnostic(String kind, String file, long line, long col, String message) {
            this.kind = kind; this.file = file; this.line = line; this.col = col; this.message = message;
        }

        public Map<String,Object> toMap() {
            return lcforge.Json.map("kind", kind, "file", file, "line", line, "col", col, "message", message);
        }
    }

    public static final class Result {
        public final boolean ok;
        public final List<Diagnostic> diagnostics;
        public final Path classesDir;
        public final long millis;

        Result(boolean ok, List<Diagnostic> d, Path classesDir, long millis) {
            this.ok = ok; this.diagnostics = d; this.classesDir = classesDir; this.millis = millis;
        }

        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(x -> x.kind.equals("error"));
        }

        public String errorText() {
            StringBuilder b = new StringBuilder();
            for (Diagnostic d : diagnostics) {
                if (!d.kind.equals("error")) continue;
                b.append(d.file).append(':').append(d.line).append(": ").append(d.message).append('\n');
            }
            return b.toString();
        }
    }

    /**
     * Compile every .java file in {@code sourceDir} plus the shared runtime classes
     * (ListNode/TreeNode) into {@code classesDir}.
     */
    public static Result compile(Path sourceDir, Path runtimeDir, Path classesDir) throws IOException {
        long t0 = System.nanoTime();

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IllegalStateException(
                "No Java compiler available. LCForge needs a JDK, not a JRE.\n" +
                "Current java.home = " + System.getProperty("java.home"));
        }

        Files.createDirectories(classesDir);

        List<File> sources = new ArrayList<>();
        collectJava(sourceDir, sources);
        collectJava(runtimeDir, sources);
        if (sources.isEmpty()) {
            return new Result(false,
                List.of(new Diagnostic("error", "", 0, 0, "no .java files found in " + sourceDir)),
                classesDir, 0);
        }

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        StandardJavaFileManager fm = javac.getStandardFileManager(collector, null, StandardCharsets.UTF_8);

        List<String> options = List.of(
            "-d", classesDir.toString(),
            "-g",                      // full debug info: required for local variable names in JDI
            "-encoding", "UTF-8",
            "-proc:none",              // no annotation processing; keeps compiles fast
            "-nowarn"
        );

        boolean ok;
        try (StandardJavaFileManager manager = fm) {
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromFiles(sources);
            ok = javac.getTask(null, manager, collector, options, null, units).call();
        }

        List<Diagnostic> diags = new ArrayList<>();
        for (javax.tools.Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
            String file = d.getSource() == null ? "" : new File(d.getSource().getName()).getName();
            diags.add(new Diagnostic(
                d.getKind().name().toLowerCase(Locale.ROOT).replace("mandatory_", ""),
                file, d.getLineNumber(), d.getColumnNumber(),
                d.getMessage(Locale.ROOT)));
        }

        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new Result(ok, diags, classesDir, ms);
    }

    private static void collectJava(Path dir, List<File> out) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.java")) {
            for (Path p : s) out.add(p.toFile());
        }
    }

    private Compile() {}
}
