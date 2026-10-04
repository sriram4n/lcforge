package lcforge.judge;

import lcforge.Json;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Parent-side manager for the runner JVM.
 *
 * Owns the timeout policy. A hung solution cannot be interrupted safely from inside the JVM
 * (Thread.stop corrupts state and was removed), so the only sound answer is to kill the whole
 * child process and start a new one -- which is exactly why the runner is a separate process.
 */
public final class RunnerPool implements Closeable {

    private static final long STARTUP_TIMEOUT_MS = 15_000;

    private final Path classpathRoot;   // where lcforge's own classes live
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "runner-reader");
        t.setDaemon(true);
        return t;
    });

    private Process proc;
    private BufferedWriter toChild;
    private BufferedReader fromChild;
    private String loadedDir = "";
    private long seq = 0;

    public RunnerPool(Path classpathRoot) {
        this.classpathRoot = classpathRoot;
    }

    public static final class Run {
        public boolean ok;
        public String output = "";
        public String stdout = "";
        public String error;
        public String stackTrace;
        public long millis;
        public boolean timedOut;

        public Map<String,Object> toMap() {
            Map<String,Object> m = Json.map(
                "ok", ok, "output", output, "stdout", stdout,
                "millis", millis, "timedOut", timedOut);
            if (error != null) m.put("error", error);
            if (stackTrace != null) m.put("stackTrace", stackTrace);
            return m;
        }
    }

    // ---------- process lifecycle ----------

    private synchronized void ensureAlive() throws IOException {
        if (proc != null && proc.isAlive()) return;

        // The runtime's own bin/java, never the current process's command: inside an installed app
        // that command is the native launcher, and LCForge.exe -cp ... RunnerDaemon starts nothing.
        java.nio.file.Path exe = lcforge.Preflight.javaExecutable();
        String java = exe != null ? exe.toString()
                : ProcessHandle.current().info().command()
                    .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");

        ProcessBuilder pb = new ProcessBuilder(
            java,
            "-XX:+UseSerialGC",        // tiny heaps, short runs: serial GC starts faster
            "-XX:TieredStopAtLevel=1", // C1 only; these runs are far too short for C2 to pay off
            "-Xss64m",                 // deep recursion is normal in these problems
            "-cp", classpathRoot.toString(),
            "lcforge.judge.RunnerDaemon");
        pb.redirectErrorStream(false);
        proc = pb.start();
        toChild = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
        fromChild = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        loadedDir = "";

        Object pong = request(Json.map("cmd", "ping"), STARTUP_TIMEOUT_MS);
        if (pong == null || !Json.bool(pong, "ok", false)) {
            throw new IOException("runner JVM failed to start");
        }
    }

    private synchronized void kill() {
        if (proc != null) {
            proc.destroyForcibly();
            try { proc.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        proc = null;
        toChild = null;
        fromChild = null;
        loadedDir = "";
    }

    @Override
    public void close() {
        kill();
        reader.shutdownNow();
    }

    // ---------- protocol ----------

    /** Send one command, wait for its reply. Returns null on timeout (caller decides what that means). */
    private Object request(Map<String,Object> cmd, long timeoutMs) throws IOException {
        cmd.put("id", ++seq);
        toChild.write(Json.write(cmd));
        toChild.write("\n");
        toChild.flush();

        Future<String> f = reader.submit(fromChild::readLine);
        try {
            String line = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            return line == null ? null : Json.parse(line);
        } catch (TimeoutException e) {
            f.cancel(true);
            return null;
        } catch (ExecutionException | InterruptedException e) {
            return null;
        }
    }

    // ---------- public API ----------

    /** Make a freshly-compiled directory the active one. */
    public synchronized void load(Path classesDir) throws IOException {
        ensureAlive();
        String dir = classesDir.toAbsolutePath().toString();
        Object r = request(Json.map("cmd", "load", "classesDir", dir), 10_000);
        if (r == null) { kill(); throw new IOException("runner stopped responding while loading classes"); }
        loadedDir = dir;
    }

    /** Force a reload even if the directory is unchanged -- call after every recompile. */
    public synchronized void reload(Path classesDir) throws IOException {
        loadedDir = "";
        load(classesDir);
    }

    public synchronized List<String> entries(String className) throws IOException {
        ensureAlive();
        Object r = request(Json.map("cmd", "entries", "class", className), 10_000);
        if (r == null) { kill(); return List.of(); }
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(Json.obj(r).get("entries"))) out.add(String.valueOf(o));
        return out;
    }

    public synchronized Run run(Path classesDir, String className, String entry,
                                List<String> input, long timeoutMs) {
        Run run = new Run();
        try {
            ensureAlive();
            String dir = classesDir.toAbsolutePath().toString();
            if (!dir.equals(loadedDir)) load(classesDir);

            Object r = request(
                Json.map("cmd", "run", "class", className, "entry", entry, "input", input),
                timeoutMs);

            if (r == null) {
                // Either the solution is looping forever or it crashed the JVM. Same remedy.
                kill();
                run.ok = false;
                run.timedOut = true;
                run.error = "Time Limit Exceeded (over " + timeoutMs + "ms). "
                          + "The runner was restarted -- an infinite loop is the usual cause.";
                return run;
            }

            run.ok = Json.bool(r, "ok", false);
            run.output = Json.str(r, "output", "");
            run.stdout = Json.str(r, "stdout", "");
            run.millis = Json.num(r, "millis", 0);
            if (!run.ok) {
                run.error = Json.str(r, "error", "unknown error");
                Object st = Json.obj(r).get("stackTrace");
                run.stackTrace = st == null ? null : String.valueOf(st);
            }
        } catch (IOException e) {
            kill();
            run.ok = false;
            run.error = "runner unavailable: " + e.getMessage();
        }
        return run;
    }
}
