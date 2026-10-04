package lcforge;

import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Startup checks that fail with an explanation instead of a stack trace.
 *
 * Worth having because the three things LCForge depends on -- the in-process compiler, the debugger
 * interface, and a `java` executable to start the runner and the debuggee with -- are all things a
 * JRE or an over-trimmed runtime image quietly lacks, and the failure mode without these checks is a
 * bare NullPointerException deep in a request handler.
 */
public final class Preflight {

    public static void check() throws App.Fatal {
        String home = System.getProperty("java.home", "?");
        StringBuilder problems = new StringBuilder();

        if (ToolProvider.getSystemJavaCompiler() == null) {
            problems.append("No Java compiler: LCForge needs a JDK, not a JRE.\n");
        }
        try {
            Class.forName("com.sun.jdi.Bootstrap");
        } catch (ClassNotFoundException e) {
            problems.append("The Java Debug Interface (jdk.jdi) is missing, so stepping through code cannot work.\n");
        }
        // The runner and the debuggee are separate JVMs. A runtime image built with
        // --strip-native-commands (jpackage's default) has no bin/java to start them with.
        if (javaExecutable() == null) {
            problems.append("No java executable under ").append(home).append("/bin.\n");
        }

        if (problems.length() > 0) {
            throw new App.Fatal(problems + "Java home: " + home
                + "\nInstall a JDK (17 or newer) and make sure it is the one on your PATH.");
        }
        System.out.println("  JDK " + System.getProperty("java.version", "?") + "  (" + home + ")");
    }

    /**
     * The `java` launcher of the runtime we are running on, or null.
     *
     * Not ProcessHandle.current().info().command(): inside an installed app the current process is
     * the native launcher, LCForge.exe, and starting *that* with JVM flags is not starting a JVM.
     */
    public static Path javaExecutable() {
        String exe = File.separatorChar == '\\' ? "java.exe" : "java";
        Path p = Paths.get(System.getProperty("java.home", ""), "bin", exe);
        return Files.isRegularFile(p) ? p : null;
    }

    private Preflight() {}
}
