package lcforge.judge;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Calls the user's solution by reflection -- no generated harness.
 *
 * The alternative (template a Main.java that parses input and calls the method) means
 * string-building Java, an extra compile, and wrapper frames polluting the debugger timeline.
 * Reflecting over {@link Method#getGenericParameterTypes()} keeps generic information intact
 * ({@code List<List<Integer>>} survives) and means the code you step through is exactly the code
 * you wrote.
 */
public final class Invoke {

    public static final class Result {
        public String output;      // canonical encoding of the return value
        public String stdout = "";
        public String error;       // null when the call succeeded
        public String stackTrace;
        public long millis;

        public boolean ok() { return error == null; }
    }

    /**
     * Find the method to call. LeetCode solutions carry exactly one public instance method;
     * when a solution also has public helpers, {@code preferred} disambiguates.
     */
    public static Method findEntry(Class<?> cls, String preferred) {
        List<Method> candidates = new ArrayList<>();
        for (Method m : cls.getDeclaredMethods()) {
            if (m.isSynthetic() || m.isBridge()) continue;
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getName().equals("main")) continue;
            candidates.add(m);
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                "class " + cls.getSimpleName() + " has no public instance method to call");
        }
        if (preferred != null && !preferred.isBlank()) {
            for (Method m : candidates) if (m.getName().equals(preferred)) return m;
        }
        if (candidates.size() > 1) {
            // Deterministic: the method declared first in the source wins. getDeclaredMethods()
            // has no guaranteed order, so sort by name to at least be stable across runs.
            candidates.sort(Comparator.comparing(Method::getName));
        }
        return candidates.get(0);
    }

    /** Names of all callable entry points, for the UI to offer when there is more than one. */
    public static List<String> entryNames(Class<?> cls) {
        List<String> out = new ArrayList<>();
        for (Method m : cls.getDeclaredMethods()) {
            if (m.isSynthetic() || m.isBridge()) continue;
            if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            if (m.getName().equals("main")) continue;
            out.add(m.getName());
        }
        Collections.sort(out);
        return out;
    }

    /** Decode the testcase, call the method, encode whatever came back. */
    public static Result run(Class<?> cls, String entry, String[] inputLines, boolean captureStdout) {
        Result r = new Result();

        Method m;
        Object instance;
        Object[] args;
        try {
            m = findEntry(cls, entry);
            m.setAccessible(true);

            Type[] params = m.getGenericParameterTypes();
            if (inputLines.length < params.length) {
                r.error = "testcase has " + inputLines.length + " line(s) but "
                        + m.getName() + " takes " + params.length + " argument(s). "
                        + "LeetCode format is one argument per line.";
                return r;
            }
            args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                args[i] = TypeCodec.decode(inputLines[i], params[i]);
            }
            // LeetCode's snippet is `class Solution`, not `public class Solution` -- the class and
            // its default constructor are package-private, so reflecting from lcforge.judge needs
            // this. Without it every single pasted solution fails with IllegalAccessException.
            Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            instance = ctor.newInstance();
        } catch (TypeCodec.CodecException e) {
            r.error = e.getMessage();
            return r;
        } catch (Throwable e) {
            r.error = "could not prepare the call: " + e;
            r.stackTrace = trace(e);
            return r;
        }

        PrintStream realOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (captureStdout) System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));

        long t0 = System.nanoTime();
        try {
            Object ret = m.invoke(instance, args);
            r.millis = (System.nanoTime() - t0) / 1_000_000;

            if (m.getReturnType() == void.class) {
                // In-place problems (Rotate Array, Move Zeroes, Sort Colors...): the answer is the
                // argument the method mutated, so serialize the first array/List parameter.
                Object mutated = firstMutableArg(args);
                r.output = mutated == null ? "null" : TypeCodec.encode(mutated);
            } else {
                r.output = TypeCodec.encode(ret);
            }
        } catch (InvocationTargetException e) {
            r.millis = (System.nanoTime() - t0) / 1_000_000;
            Throwable cause = e.getCause() == null ? e : e.getCause();
            r.error = cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
            r.stackTrace = userFrames(cause);
        } catch (Throwable e) {
            r.millis = (System.nanoTime() - t0) / 1_000_000;
            r.error = String.valueOf(e);
            r.stackTrace = trace(e);
        } finally {
            if (captureStdout) {
                System.out.flush();
                System.setOut(realOut);
                r.stdout = buf.toString(StandardCharsets.UTF_8);
            }
        }
        return r;
    }

    private static Object firstMutableArg(Object[] args) {
        for (Object a : args) {
            if (a == null) continue;
            if (a.getClass().isArray() || a instanceof Collection) return a;
        }
        return args.length > 0 ? args[0] : null;
    }

    private static String trace(Throwable t) {
        java.io.StringWriter w = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }

    /** Trim the stack to the user's own frames -- reflection plumbing is noise. */
    private static String userFrames(Throwable t) {
        StringBuilder b = new StringBuilder();
        b.append(t).append('\n');
        for (StackTraceElement e : t.getStackTrace()) {
            String cn = e.getClassName();
            if (cn.startsWith("java.") || cn.startsWith("jdk.") || cn.startsWith("sun.")
                    || cn.startsWith("lcforge.")) break;
            b.append("    at ").append(e).append('\n');
        }
        return b.toString();
    }

    private Invoke() {}
}
