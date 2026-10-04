import lcforge.Json;
import lcforge.judge.Compile;
import lcforge.trace.Tracer;

import java.nio.file.*;
import java.util.*;

/** End-to-end check of compile -> trace. Asserts the trace is real and contains only user frames. */
public class TracerTest {

    static int passed = 0, failed = 0;

    /** A solution with a HashMap, a loop and an early return -- enough to exercise the renderer. */
    static final String FIXTURE = """
        import java.util.*;

        class Solution {
            public int[] twoSum(int[] nums, int target) {
                Map<Integer, Integer> seen = new HashMap<>();
                for (int i = 0; i < nums.length; i++) {
                    int need = target - nums[i];
                    if (seen.containsKey(need)) {
                        return new int[]{ seen.get(need), i };
                    }
                    seen.put(nums[i], i);
                }
                return new int[]{};
            }
        }
        """;

    public static void main(String[] args) throws Exception {
        Path root      = Paths.get(".").toAbsolutePath().normalize();
        Path build     = root.resolve("build");
        Path runtime   = root.resolve("runtime");
        // Its own fixture under build/, not workspace/: the workspace holds your real solutions,
        // and a test that breaks when you edit your own code is a test you would learn to ignore.
        Path ws        = build.resolve("tracer-fixture");
        Path classes   = ws.resolve("classes");

        Files.createDirectories(ws);
        Files.writeString(ws.resolve("Solution.java"), FIXTURE);

        Compile.Result c = Compile.compile(ws, runtime, classes);
        check("compiles cleanly", c.ok, true);
        if (!c.ok) { System.out.println(c.errorText()); System.exit(1); }

        long t0 = System.currentTimeMillis();
        Tracer.Trace tr = Tracer.trace(build, classes, "Solution", "twoSum",
                                       List.of("[2,7,11,15]", "9"));
        long ms = System.currentTimeMillis() - t0;

        if (tr.error != null) System.out.println("  tracer error: " + tr.error);

        check("trace ok", tr.ok, true);
        check("trace is non-empty", tr.steps.size() > 0, true);

        // The exclusion filters must leave only the user's own code.
        boolean onlyUserFrames = true;
        Set<String> classesSeen = new LinkedHashSet<>();
        for (Tracer.Step s : tr.steps) {
            classesSeen.add(s.toMap().get("cls").toString());
        }
        for (String cn : classesSeen) {
            if (cn.startsWith("java.") || cn.startsWith("jdk.") || cn.startsWith("sun.")
                || cn.startsWith("lcforge.")) onlyUserFrames = false;
        }
        check("only user frames in trace", onlyUserFrames, true);
        System.out.println("  classes traced: " + classesSeen);

        // The loop variable must be visible with a real value.
        boolean sawI = false, sawSeen = false, sawChanged = false;
        for (Tracer.Step s : tr.steps) {
            Map<String,Object> m = s.toMap();
            @SuppressWarnings("unchecked")
            Map<String,String> locals = (Map<String,String>) m.get("locals");
            @SuppressWarnings("unchecked")
            List<String> changed = (List<String>) m.get("changed");
            if (locals.containsKey("i")) sawI = true;
            if (locals.containsKey("seen")) sawSeen = true;
            if (changed != null && !changed.isEmpty()) sawChanged = true;
        }
        check("loop variable 'i' captured", sawI, true);
        check("HashMap local 'seen' captured", sawSeen, true);
        check("change detection populated", sawChanged, true);

        check("result returned", tr.result != null, true);
        if (tr.result != null) {
            check("correct output", Json.str(tr.result, "output", ""), "[0,1]");
        }

        // Show a slice of the timeline so the trace is visibly real, not just non-empty.
        System.out.println("\n  --- first 12 steps ---");
        for (int i = 0; i < Math.min(12, tr.steps.size()); i++) {
            Map<String,Object> m = tr.steps.get(i).toMap();
            System.out.printf("  line %-3s %-10s %s%n", m.get("line"), m.get("method"), m.get("locals"));
        }

        System.out.println();
        System.out.println("  " + tr.steps.size() + " steps in " + ms + "ms"
            + (tr.truncated ? " (TRUNCATED: " + tr.truncateReason + ")" : ""));
        System.out.println(failed == 0
            ? "Tracer: all " + passed + " checks passed"
            : "Tracer: " + passed + " passed, " + failed + " FAILED");
        if (failed > 0) System.exit(1);
    }

    static void check(String name, Object got, Object want) {
        if (Objects.equals(got, want)) { passed++; }
        else { failed++; System.out.println("  FAIL " + name + " -- got " + got + ", want " + want); }
    }
}
