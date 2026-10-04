package lcforge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * Where everything is.
 *
 * Four places, and the whole difference between a source checkout and an installed app is which
 * four:
 *
 *   web        the interface's files       a folder in a checkout; inside the jar once packaged
 *   runtimeSrc ListNode.java, TreeNode.java compiled alongside every solution, so javac needs files
 *   home       your problems, code, history next to the code in a checkout; the OS's app-data
 *                                            folder when installed, because Program Files is not
 *                                            yours to write in and your work must survive an update
 *   code       LCForge's own classes        the runner and tracer JVMs need them on a classpath
 */
public record Layout(Assets web, Path runtimeSrc, Path home, Path code) {

    /** The two shared classes every solution may use. Shipped as source; compiled with your code. */
    static final List<String> RUNTIME_SOURCES = List.of("ListNode.java", "TreeNode.java");

    /** A source checkout: web/ and runtime/ under root. */
    public static Layout dev(Path root, Path home) {
        return new Layout(Assets.dir(root.resolve("web")), root.resolve("runtime"), home, codeLocation(root));
    }

    /**
     * An installed copy: everything served from the jar, and the runtime sources written out into
     * the data folder -- overwritten on every start, so an update that changes them is never shadowed
     * by the copy an older version left behind.
     */
    public static Layout packaged(Path home) throws IOException {
        Path runtime = home.resolve("runtime");
        Files.createDirectories(runtime);
        for (String name : RUNTIME_SOURCES) {
            try (InputStream in = Layout.class.getResourceAsStream("/runtime/" + name)) {
                if (in == null) throw new IOException("this build is missing runtime/" + name);
                Files.copy(in, runtime.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return new Layout(Assets.classpath("web"), runtime, home, codeLocation(home));
    }

    /** Running from a built jar that carries the interface inside it -- as opposed to a checkout. */
    public static boolean isPackaged() {
        return Files.isRegularFile(codeLocation(Paths.get(".")))
            && Layout.class.getResource("/web/index.html") != null;
    }

    /**
     * Where LCForge's own classes were loaded from: a directory in a source checkout, the jar itself
     * once packaged.
     *
     * The runner and the tracer both start a second JVM that needs RunnerDaemon and TraceMain on its
     * classpath. `root/build` was the right answer only when launched by run.ps1; asking the JVM where
     * it actually found this class is right in every layout, including ones that do not exist yet.
     */
    public static Path codeLocation(Path fallbackRoot) {
        try {
            return Paths.get(Layout.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception | Error e) {
            return fallbackRoot.resolve("build");
        }
    }

    /**
     * The OS's own place for an application's data.
     *
     * Windows gets LOCALAPPDATA rather than APPDATA: a roaming profile would sync every compiled
     * .class file to a domain server on every logoff, for no benefit to anyone.
     */
    public static Path defaultHome() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String user = System.getProperty("user.home");
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            return Paths.get(local != null && !local.isBlank() ? local : user + "\\AppData\\Local", "LCForge");
        }
        if (os.contains("mac")) {
            return Paths.get(user, "Library", "Application Support", "LCForge");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        return xdg != null && !xdg.isBlank() ? Paths.get(xdg, "lcforge") : Paths.get(user, ".local", "share", "lcforge");
    }
}
