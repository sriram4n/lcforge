package lcforge;

import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * The launcher: work out where everything lives, start the server, open the browser.
 *
 * Two ways to arrive here. From a source checkout (run.ps1 / run.sh), where the interface is the
 * web/ folder and your data sits beside the code. Or as an installed app, double-clicked, where the
 * interface is inside the jar, your data lives in the OS's app-data folder, there is no terminal to
 * print to, and so there is a small window instead: proof that it is running, and a Quit button,
 * because a server you cannot see is one you cannot stop.
 *
 * <pre>
 *   --port N       first port to try (default 7070; moves up if something else has it)
 *   --home DIR     where your problems and code live
 *   --root DIR     a source checkout's folder (web/ and runtime/)
 *   --console      no window, log to the terminal (run.ps1 uses this)
 *   --no-browser   do not open a browser tab
 * </pre>
 */
public final class App {

    static final int DEFAULT_PORT = 7070;
    static final int PORT_RANGE = 10;          // 7070..7079: the extension looks in the same range

    public static void main(String[] args) throws Exception {
        Options o = Options.parse(args);
        if (o.version) { System.out.println("LCForge " + Server.VERSION); return; }
        if (o.help) { System.out.println(USAGE); return; }

        boolean gui = !o.console && !java.awt.GraphicsEnvironment.isHeadless();

        try {
            run(o, gui);
        } catch (Fatal f) {
            if (gui) Gui.fatal(f.getMessage());
            else System.err.println("\n  " + f.getMessage().replace("\n", "\n  ") + "\n");
            System.exit(1);
        }
    }

    private static void run(Options o, boolean gui) throws Exception {
        Preflight.check();

        boolean packaged = o.root == null && System.getProperty("lcforge.root") == null && Layout.isPackaged();
        Path home = resolveHome(o, packaged);
        Files.createDirectories(home);

        // ---- one LCForge per data folder ----
        // Two servers writing the same problems.json is how a file gets corrupted, and a port check
        // alone cannot prevent it: the first copy may have moved to 7071 because something else had
        // 7070. So the lock is on the folder, and the folder says which port its owner is on.
        FileChannel lockFile = FileChannel.open(home.resolve(".lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = lockFile.tryLock();
        if (lock == null) {
            int theirs = readPort(home);
            if (theirs > 0 && isLcforge(theirs)) {
                String url = "http://localhost:" + theirs + "/";
                System.out.println("  LCForge is already running at " + url);
                if (o.browser) open(url);
                return;
            }
            throw new Fatal("Another copy of LCForge is using this data folder:\n" + home
                + "\nClose it first (check the taskbar or tray), then start LCForge again.");
        }

        Layout layout;
        if (packaged) {
            layout = Layout.packaged(home);
        } else {
            Path root = Paths.get(o.root != null ? o.root : System.getProperty("lcforge.root", "."))
                             .toAbsolutePath().normalize();
            for (String dir : new String[]{ "web", "runtime" }) {
                if (!Files.isDirectory(root.resolve(dir))) {
                    throw new Fatal("No " + dir + "/ folder in " + root
                        + "\nRun LCForge from its own folder, or pass --root.");
                }
            }
            layout = Layout.dev(root, home);
        }

        Server server = new Server(layout);
        int port = bind(server, o);
        Files.writeString(home.resolve(".port"), String.valueOf(port), StandardCharsets.UTF_8);

        String url = "http://localhost:" + port + "/";
        System.out.println();
        System.out.println("  LCForge " + Server.VERSION + " is running");
        System.out.println("  ->  " + url);
        System.out.println();
        System.out.println("  your data: " + home);
        System.out.println("  interface: " + layout.web().describe());
        if (!gui) System.out.println("  Ctrl+C to stop.");

        if (gui) Gui.statusWindow(url, home, server);
        if (o.browser) open(url);

        // Held for the life of the process; the OS releases it if we die.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { lock.release(); lockFile.close(); } catch (IOException ignored) { }
        }));
    }

    /**
     * Find a port. The one asked for if it is free; if it was only the default and something else
     * has it, the next free one in the range, so a stray dev server on 7070 does not stop LCForge
     * from starting. An explicit --port that is taken is an error: you asked for that one.
     */
    private static int bind(Server server, Options o) throws IOException {
        int last = o.port + (o.portGiven ? 0 : PORT_RANGE - 1);
        for (int port = o.port; port <= last; port++) {
            try {
                server.start(port);
                return port;
            } catch (BindException taken) {
                // try the next one
            }
        }
        throw new Fatal(o.portGiven
            ? "Port " + o.port + " is already in use. Start LCForge with a different --port."
            : "Ports " + o.port + "-" + last + " are all in use. Start LCForge with --port.");
    }

    private static Path resolveHome(Options o, boolean packaged) {
        String given = o.home != null ? o.home
                     : System.getProperty("lcforge.home") != null ? System.getProperty("lcforge.home")
                     : System.getenv("LCFORGE_HOME");
        if (given != null && !given.isBlank()) return Paths.get(given).toAbsolutePath().normalize();
        if (packaged) return Layout.defaultHome();
        // A checkout keeps its data beside the code, which is what it has always done.
        return Paths.get(o.root != null ? o.root : System.getProperty("lcforge.root", "."))
                    .toAbsolutePath().normalize();
    }

    private static int readPort(Path home) {
        try { return Integer.parseInt(Files.readString(home.resolve(".port")).trim()); }
        catch (Exception e) { return -1; }
    }

    /** Is the thing on this port LCForge? Asked the way a browser tab would ask. */
    static boolean isLcforge(int port) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/api/health").toURL().openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(1500);
            if (c.getResponseCode() != 200) return false;
            try (InputStream in = c.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("\"app\":\"lcforge\"");
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static void open(String url) {
        if (!Gui.browse(url)) System.out.println("  (open " + url + " in your browser)");
    }

    /** A startup failure with a sentence for a human, not a stack trace. */
    static final class Fatal extends IOException {
        Fatal(String message) { super(message); }
    }

    static final String USAGE = String.join("\n",
        "LCForge " + Server.VERSION + " — debug LeetCode-style problems in Java, locally.",
        "",
        "  --port N       first port to try (default 7070)",
        "  --home DIR     where your problems and code live",
        "  --root DIR     a source checkout's folder (contains web/ and runtime/)",
        "  --console      no window; log here instead",
        "  --no-browser   do not open a browser tab",
        "  --version      print the version",
        "");

    private static final class Options {
        int port = DEFAULT_PORT;
        boolean portGiven, console, browser = true, help, version;
        String home, root;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                String next = i + 1 < args.length ? args[i + 1] : null;
                switch (a) {
                    case "--port" -> { o.port = Integer.parseInt(require(a, next)); o.portGiven = true; i++; }
                    case "--home" -> { o.home = require(a, next); i++; }
                    case "--root" -> { o.root = require(a, next); i++; }
                    case "--console" -> o.console = true;
                    case "--no-browser" -> o.browser = false;
                    case "--help", "-h" -> o.help = true;
                    case "--version", "-v" -> o.version = true;
                    // jpackage on macOS can hand the launcher a -psn_ argument; anything unknown is ignored
                    default -> { }
                }
            }
            return o;
        }

        private static String require(String flag, String value) {
            if (value == null) throw new IllegalArgumentException(flag + " needs a value");
            return value;
        }
    }

    private App() {}
}
