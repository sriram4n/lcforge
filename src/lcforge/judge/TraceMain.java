package lcforge.judge;

import lcforge.Json;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;

/**
 * Entry point of the debuggee JVM that the JDI tracer launches.
 *
 * Lives in {@code lcforge.*} so the tracer's exclusion filters keep it out of the step trace --
 * you see only your own frames.
 *
 * Takes exactly ONE argument: Base64 of a JSON object. The connector's "main" option is a single
 * string that gets split on whitespace, and both the workspace path (which can contain spaces --
 * this project sits under "My Folder") and the testcase lines (quotes, brackets, commas) would be
 * mangled by that split. One opaque token sidesteps the whole problem.
 */
public final class TraceMain {

    public static void main(String[] args) throws Exception {
        PrintStream real = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

        Object cfg = Json.parse(new String(Base64.getUrlDecoder().decode(args[0]), StandardCharsets.UTF_8));
        String classesDir = Json.str(cfg, "classesDir", "");
        String className  = Json.str(cfg, "class", "Solution");
        String entry      = Json.str(cfg, "entry", "");

        List<Object> lines = Json.arr(Json.obj(cfg).get("input"));
        String[] input = new String[lines.size()];
        for (int i = 0; i < lines.size(); i++) input[i] = String.valueOf(lines.get(i));

        URL url = Paths.get(classesDir).toUri().toURL();
        Map<String,Object> out;
        try (URLClassLoader cl = new URLClassLoader(new URL[]{ url }, TraceMain.class.getClassLoader())) {
            Class<?> cls = cl.loadClass(className);
            Thread.currentThread().setContextClassLoader(cl);

            Invoke.Result r = Invoke.run(cls, entry, input, true);

            out = Json.map(
                "ok", r.ok(),
                "output", r.output,
                "stdout", r.stdout,
                "millis", r.millis);
            if (!r.ok()) {
                out.put("error", r.error);
                out.put("stackTrace", r.stackTrace);
            }
        } catch (Throwable t) {
            out = Json.map("ok", false, "error", "trace setup failed: " + t);
        }

        // Sentinel-prefixed so the tracer can pick the result out of the process stream.
        real.println("@@LCFORGE@@" + Json.write(out));
        real.flush();
    }

    private TraceMain() {}
}
