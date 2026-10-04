package lcforge.judge;

import lcforge.Json;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;

/**
 * Child-JVM half of the runner. Reads newline-delimited JSON commands on stdin and writes one
 * JSON response per line on stdout.
 *
 * Exists because JVM startup is ~80ms: acceptable once, fatal for the stress-testing loop
 * (5,000 iterations would be seven minutes of pure startup). Keeping one JVM alive and loading
 * each compile generation through a fresh {@link URLClassLoader} drops the per-call cost to
 * microseconds, and being a separate process means the parent can kill it outright when user
 * code runs away -- there is no safe way to stop a hung thread in-process.
 */
public final class RunnerDaemon {

    /** The real stdout, captured before anything can redirect it. The protocol writes here only. */
    private static PrintStream protocol;

    private static URLClassLoader loader;
    private static String loadedDir = "";

    public static void main(String[] args) throws Exception {
        protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        // Anything user code prints outside a captured call must not corrupt the protocol.
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            Map<String,Object> resp;
            long id = -1;
            try {
                Object cmd = Json.parse(line);
                id = Json.num(cmd, "id", -1);
                resp = handle(cmd);
            } catch (Throwable t) {
                resp = Json.map("ok", false, "error", "daemon error: " + t);
            }
            resp.put("id", id);
            protocol.println(Json.write(resp));
        }
    }

    private static Map<String,Object> handle(Object cmd) throws Exception {
        String op = Json.str(cmd, "cmd", "");
        switch (op) {
            case "ping":
                return Json.map("ok", true, "pong", true);

            case "load":
                return load(Json.str(cmd, "classesDir", ""));

            case "run":
                return run(cmd);

            case "entries": {
                Class<?> cls = loader.loadClass(Json.str(cmd, "class", "Solution"));
                return Json.map("ok", true, "entries", Invoke.entryNames(cls));
            }

            case "exit":
                protocol.println(Json.write(Json.map("ok", true, "bye", true)));
                System.exit(0);

            default:
                return Json.map("ok", false, "error", "unknown command: " + op);
        }
    }

    /**
     * Point at a compile output directory. A new classloader per load is what makes recompiles
     * visible -- reusing one would keep serving the previous generation's bytecode.
     */
    private static Map<String,Object> load(String dir) throws Exception {
        if (loader != null) {
            try { loader.close(); } catch (IOException ignored) {}
        }
        URL url = Paths.get(dir).toUri().toURL();
        loader = new URLClassLoader(new URL[]{ url }, RunnerDaemon.class.getClassLoader());
        loadedDir = dir;
        return Json.map("ok", true, "classesDir", dir);
    }

    private static Map<String,Object> run(Object cmd) throws Exception {
        if (loader == null) return Json.map("ok", false, "error", "no classes loaded");

        String className = Json.str(cmd, "class", "Solution");
        String entry = Json.str(cmd, "entry", "");
        List<Object> raw = Json.arr(Json.obj(cmd).get("input"));
        String[] input = new String[raw.size()];
        for (int i = 0; i < raw.size(); i++) input[i] = String.valueOf(raw.get(i));

        Class<?> cls;
        try {
            cls = loader.loadClass(className);
        } catch (ClassNotFoundException e) {
            return Json.map("ok", false, "error",
                "class " + className + " not found in " + loadedDir);
        }

        // The context classloader matters for solutions that use ServiceLoader or Class.forName.
        ClassLoader prev = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        Invoke.Result r;
        try {
            r = Invoke.run(cls, entry, input, true);
        } finally {
            Thread.currentThread().setContextClassLoader(prev);
        }

        Map<String,Object> out = Json.map(
            "ok", r.ok(),
            "output", r.output,
            "stdout", r.stdout,
            "millis", r.millis);
        if (!r.ok()) {
            out.put("error", r.error);
            out.put("stackTrace", r.stackTrace);
        }
        return out;
    }

    private RunnerDaemon() {}
}
