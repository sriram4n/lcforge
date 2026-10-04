package lcforge.trace;

import com.sun.jdi.*;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import lcforge.Json;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Records a line-by-line execution trace of the user's solution using the Java Debug Interface.
 *
 * This is the feature LeetCode Premium charges for and still does worse: every line executed,
 * with every local variable's value at that moment, scrubbable after the fact.
 *
 * <p><b>On filtering:</b> the obvious move is {@code StepRequest.addClassFilter("Solution*")}, but
 * JDI ANDs multiple class filters together, so anything beyond a single pattern matches nothing --
 * and a single pattern cannot cover a solution that defines helper classes. Exclusion filters are
 * additive instead, so the trace is built by excluding the JDK and LCForge's own plumbing. That
 * leaves exactly the user's code, which is the point: no wrapper frames in your timeline.
 */
public final class Tracer {

    public static final int  MAX_STEPS    = 25_000;
    public static final long MAX_WALL_MS  = 15_000;
    public static final int  MAX_TRACE_CHARS = 2_000_000;

    private static final String SENTINEL = "@@LCFORGE@@";

    /** Packages whose internals are noise. Additive, unlike class filters. */
    private static final String[] EXCLUDES = {
        "java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*", "jakarta.*",
        "lcforge.*",          // the harness itself
        "ListNode", "TreeNode" // LeetCode scaffolding: stepping into these constructors is noise
    };

    public static final class Step {
        int index;
        int line;
        String method;
        String cls;
        int depth;
        Map<String,String> locals = new LinkedHashMap<>();
        List<String> changed = new ArrayList<>();
        Map<String,Object> structs;   // variable name -> structure for the visualiser

        public Map<String,Object> toMap() {
            Map<String,Object> m = Json.map(
                "i", index, "line", line, "method", method, "cls", cls,
                "depth", depth, "locals", locals, "changed", changed);
            if (structs != null && !structs.isEmpty()) m.put("structs", structs);
            return m;
        }
    }

    public static final class Trace {
        public boolean ok = true;
        public boolean truncated;
        public String truncateReason;
        public String error;
        public final List<Step> steps = new ArrayList<>();
        public Map<String,Object> result;   // what the solution returned
        public String stdout = "";

        public Map<String,Object> toMap() {
            List<Object> s = new ArrayList<>(steps.size());
            for (Step st : steps) s.add(st.toMap());
            Map<String,Object> m = Json.map(
                "ok", ok,
                "truncated", truncated,
                "stepCount", steps.size(),
                "steps", s,
                "stdout", stdout);
            if (truncateReason != null) m.put("truncateReason", truncateReason);
            if (error != null) m.put("error", error);
            if (result != null) m.put("result", result);
            return m;
        }
    }

    /**
     * Launch the solution under the debugger and record every line it executes.
     *
     * @param lcforgeClasses LCForge's own build directory (holds TraceMain)
     * @param workspaceClasses the compiled solution
     */
    public static Trace trace(Path lcforgeClasses, Path workspaceClasses,
                              String className, String entry, List<String> input) {
        Trace trace = new Trace();

        LaunchingConnector connector = findConnector();
        if (connector == null) {
            trace.ok = false;
            trace.error = "No launching connector available. LCForge needs a full JDK (jdk.jdi).";
            return trace;
        }

        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            Json.write(Json.map(
                "classesDir", workspaceClasses.toAbsolutePath().toString(),
                "class", className,
                "entry", entry,
                "input", input)).getBytes(StandardCharsets.UTF_8));

        String cp = lcforgeClasses.toAbsolutePath() + File.pathSeparator + workspaceClasses.toAbsolutePath();

        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("main").setValue("lcforge.judge.TraceMain " + payload);
        // Quoted: this project lives under a path with a space in it.
        args.get("options").setValue("-cp \"" + cp + "\" -Xss64m -XX:TieredStopAtLevel=1");
        if (args.containsKey("suspend")) args.get("suspend").setValue("true");

        VirtualMachine vm;
        try {
            vm = connector.launch(args);
        } catch (Exception e) {
            trace.ok = false;
            trace.error = "could not launch the debuggee JVM: " + e;
            return trace;
        }

        StringBuilder procOut = new StringBuilder();
        StringBuilder procErr = new StringBuilder();
        // The debuggee blocks once its pipes fill, so these must be drained continuously.
        Thread outPump = pump(vm.process().getInputStream(), procOut);
        Thread errPump = pump(vm.process().getErrorStream(), procErr);

        long deadline = System.currentTimeMillis() + MAX_WALL_MS;
        int approxChars = 0;

        try {
            EventRequestManager erm = vm.eventRequestManager();
            EventQueue queue = vm.eventQueue();
            boolean stepping = false;
            Map<String,String> prevLocals = new LinkedHashMap<>();
            String prevFrameKey = "";

            // Deliberately NOT resuming here. The VM is launched suspended, and resuming before
            // VMStartEvent is processed lets the whole program run before the step request exists
            // -- which yields an empty trace. The loop's set.resume() releases it at the right
            // moment, after the request is installed.

            loop:
            while (true) {
                if (System.currentTimeMillis() > deadline) {
                    trace.truncated = true;
                    trace.truncateReason = "stopped after " + MAX_WALL_MS + "ms";
                    break;
                }

                EventSet set;
                try {
                    set = queue.remove(500);
                } catch (InterruptedException e) {
                    break;
                }
                if (set == null) continue;

                boolean resume = true;
                for (Event ev : set) {

                    if (ev instanceof VMStartEvent) {
                        if (!stepping) {
                            ThreadReference t = ((VMStartEvent) ev).thread();
                            StepRequest sr = erm.createStepRequest(t, StepRequest.STEP_LINE, StepRequest.STEP_INTO);
                            for (String ex : EXCLUDES) sr.addClassExclusionFilter(ex);
                            sr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                            sr.enable();
                            stepping = true;
                        }
                        continue;
                    }

                    if (ev instanceof VMDeathEvent || ev instanceof VMDisconnectEvent) {
                        resume = false;
                        break loop;
                    }

                    if (ev instanceof StepEvent) {
                        StepEvent se = (StepEvent) ev;
                        Step step = capture(se, trace.steps.size());
                        if (step == null) continue;

                        String frameKey = step.cls + "#" + step.method + "#" + step.depth;
                        if (frameKey.equals(prevFrameKey)) {
                            for (Map.Entry<String,String> e : step.locals.entrySet()) {
                                String before = prevLocals.get(e.getKey());
                                if (before == null || !before.equals(e.getValue())) step.changed.add(e.getKey());
                            }
                        } else {
                            // New frame: everything is "new" rather than "changed", so flag nothing
                            // and let the UI show the frame boundary instead.
                            step.changed.clear();
                        }
                        prevLocals = step.locals;
                        prevFrameKey = frameKey;

                        trace.steps.add(step);
                        approxChars += 80 + step.locals.toString().length();

                        if (trace.steps.size() >= MAX_STEPS) {
                            trace.truncated = true;
                            trace.truncateReason = "stopped at the " + MAX_STEPS + "-step cap";
                            break loop;
                        }
                        if (approxChars > MAX_TRACE_CHARS) {
                            trace.truncated = true;
                            trace.truncateReason = "trace grew past " + (MAX_TRACE_CHARS / 1000) + "KB";
                            break loop;
                        }
                    }
                }
                if (resume) set.resume();
            }
        } catch (VMDisconnectedException e) {
            // Normal: the debuggee finished while we were reading it.
        } catch (Exception e) {
            trace.ok = false;
            trace.error = "tracing failed: " + e;
        } finally {
            try { vm.dispose(); } catch (Exception ignored) {}
            try { vm.process().destroyForcibly(); } catch (Exception ignored) {}
            join(outPump); join(errPump);
        }

        // Pull the solution's own result back out of the debuggee's stdout.
        String out = procOut.toString();
        int at = out.indexOf(SENTINEL);
        if (at >= 0) {
            int end = out.indexOf('\n', at);
            String json = out.substring(at + SENTINEL.length(), end < 0 ? out.length() : end).trim();
            Object parsed = Json.parseOrNull(json);
            if (parsed != null) {
                trace.result = Json.obj(parsed);
                trace.stdout = Json.str(parsed, "stdout", "");
            }
        } else if (trace.error == null && trace.steps.isEmpty()) {
            String err = procErr.toString().trim();
            trace.ok = false;
            trace.error = err.isEmpty()
                ? "the debuggee produced no result -- check that the class is named Solution"
                : err;
        }

        return trace;
    }

    /** Read one step: where we are, and every local in the top frame. */
    private static Step capture(StepEvent se, int index) {
        try {
            ThreadReference thread = se.thread();
            Location loc = se.location();
            StackFrame frame = thread.frame(0);

            Step s = new Step();
            s.index = index;
            s.line = loc.lineNumber();
            s.method = loc.method().name();
            s.cls = loc.declaringType().name();
            s.depth = thread.frameCount();

            List<LocalVariable> vars;
            try {
                vars = frame.visibleVariables();
            } catch (AbsentInformationException e) {
                // Should not happen: Compile passes -g. Degrade to a location-only step.
                return s;
            }

            if (!vars.isEmpty()) {
                // One batched call rather than one round-trip per variable.
                Map<LocalVariable, Value> values = frame.getValues(vars);
                for (LocalVariable v : vars) {
                    ValueRenderer.Rendered r = ValueRenderer.render(values.get(v));
                    s.locals.put(v.name(), r.display);
                    if (r.struct != null) {
                        if (s.structs == null) s.structs = new LinkedHashMap<>();
                        s.structs.put(v.name(), r.struct);
                    }
                }
            }

            // Instance fields matter for problems that carry state on the Solution object.
            ObjectReference self = frame.thisObject();
            if (self != null) {
                for (Field f : self.referenceType().fields()) {
                    if (f.isStatic()) continue;
                    ValueRenderer.Rendered r = ValueRenderer.render(self.getValue(f));
                    s.locals.put("this." + f.name(), r.display);
                }
            }

            return s;
        } catch (IncompatibleThreadStateException | VMDisconnectedException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LaunchingConnector findConnector() {
        for (Connector c : Bootstrap.virtualMachineManager().allConnectors()) {
            if (c instanceof LaunchingConnector && c.name().equals("com.sun.jdi.CommandLineLaunch")) {
                return (LaunchingConnector) c;
            }
        }
        return Bootstrap.virtualMachineManager().defaultConnector();
    }

    private static Thread pump(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sink) { sink.append(line).append('\n'); }
                }
            } catch (IOException ignored) {}
        }, "debuggee-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void join(Thread t) {
        try { t.join(1500); } catch (InterruptedException ignored) {}
    }

    private Tracer() {}
}
