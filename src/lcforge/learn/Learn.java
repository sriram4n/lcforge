package lcforge.learn;

import lcforge.Json;
import lcforge.Store;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * The learning layer's single entry point.
 *
 * Everything the app knows about your practice over time is derived here, from one append-only log
 * of events. The design rule is that Server.doRun calls exactly two methods -- summarizeRun to build
 * the record, observeRun to fold it into the problem -- and nothing else in the codebase ever needs
 * to learn about streaks, heatmaps or personal bests.
 *
 * Two kinds of state, on purpose:
 *
 *   - the LOG is the truth. Every run, trace and hunt, forever, and anything historical is computed
 *     from it. It is never rewritten.
 *   - a handful of HOT SCALARS live on the problem record itself (runs, passes, bestSolveMs...).
 *     These are pure derivations of the log, cached only because the library page wants them for
 *     every row and walking forty thousand records on every page load to recompute a number that
 *     changed once would be absurd.
 */
public final class Learn {

    /**
     * Run tries the case in front of you. Submit judges every case, and is the only thing that can
     * count as solving the problem. A request that does not say is a Submit: that is what every run
     * was before the two were split, so old clients, the extension and the whole existing log all
     * keep meaning exactly what they meant.
     */
    public static final String MODE_RUN = "run";
    public static final String MODE_SUBMIT = "submit";

    /** The mode a request or a record is in. Anything but an explicit "run" is a submission. */
    public static String modeOf(Object o) {
        return Json.str(o, "mode", MODE_SUBMIT).equals(MODE_RUN) ? MODE_RUN : MODE_SUBMIT;
    }

    private final Store store;

    public Learn(Store store) {
        this.store = store;
    }

    // ---------- recording ----------

    /**
     * Build the log record for a finished run.
     *
     * Pure and static: given the same run it produces the same record, which is what makes it
     * testable without a Store, a compiler or a JVM.
     */
    public static Map<String,Object> summarizeRun(String slug, Object body, int passed,
                                                  List<Object> results, long compileMs) {
        List<Object> caseMs = new ArrayList<>();
        long runMs = 0;
        boolean timedOut = false, errored = false;
        for (Object r : results) {
            long ms = Json.num(r, "millis", 0);
            caseMs.add(ms);
            runMs += ms;
            String v = Json.str(r, "verdict", "");
            if (v.equals("TIMEOUT")) timedOut = true;
            else if (v.equals("ERROR")) errored = true;
        }

        int total = results.size();
        String mode = modeOf(body);
        boolean submit = mode.equals(MODE_SUBMIT);
        // The rich rollup, for later analysis. The problem record keeps its own two-state
        // lastVerdict, which the library's status dot has always been styled against.
        String verdict = total == 0 ? ""
                       : timedOut ? "TIMEOUT"
                       : errored ? "ERROR"
                       : passed == total ? "PASS"
                       : "FAIL";

        Map<String,Object> rec = Json.map(
            "t", System.currentTimeMillis(),
            "ev", "run",
            "slug", slug,
            "mode", mode,
            // THE line. Every consumer of "did this solve the problem" -- the Spark award, passes,
            // solved, the library's status dot -- reads `ok`, so "a Run that passed the one case in
            // front of you has not solved anything" is said here, once, instead of in five places
            // that would one day disagree. `verdict` still describes the cases that did run.
            "ok", submit && passed == total && total > 0,
            "passed", passed,
            "total", total,
            "verdict", verdict,
            "compileMs", compileMs,
            "runMs", runMs,
            "caseMs", caseMs);

        // Sent by the client from phase 2 onward; absent until then rather than zero, so "we did
        // not measure this" stays distinguishable from "this took no time".
        long activeMs = Json.num(body, "activeMs", -1);
        long wallMs = Json.num(body, "wallMs", -1);
        if (activeMs >= 0) rec.put("activeMs", activeMs);
        if (wallMs >= 0) rec.put("wallMs", wallMs);

        String from = Json.str(body, "from", "");
        if (!from.isEmpty()) rec.put("from", from);
        return rec;
    }

    /** The record for a run that never got past javac. */
    public static Map<String,Object> summarizeCompileFailure(String slug, long compileMs, int errors,
                                                             String mode) {
        return Json.map(
            "t", System.currentTimeMillis(),
            "ev", "run",
            "slug", slug,
            "mode", MODE_RUN.equals(mode) ? MODE_RUN : MODE_SUBMIT,
            "ok", false,
            "passed", 0,
            "total", 0,
            "verdict", "COMPILE",
            "compileMs", compileMs,
            "errors", errors);
    }

    public static Map<String,Object> summarizeTrace(String slug, long steps, boolean truncated,
                                                    boolean correct) {
        return Json.map(
            "t", System.currentTimeMillis(),
            "ev", "trace",
            "slug", slug,
            "steps", steps,
            "truncated", truncated,
            "correct", correct);
    }

    /**
     * Fold a run into the problem record's hot scalars.
     *
     * Mutates p in place; the caller is already about to putProblem it.
     */
    public void observeRun(Map<String,Object> p, Map<String,Object> rec) {
        // What an acceptance rate divides by. Without it, a session spent pressing Run on one case
        // would quietly dilute passes/runs into a number that says you are bad at this. Read before
        // `runs` moves, so a problem from before the split carries its old runs over as submissions.
        long submits = Store.submitsOf(p);
        p.put("runs", Json.num(p, "runs", 0) + 1);
        p.put("submits", submits + (modeOf(rec).equals(MODE_SUBMIT) ? 1 : 0));
        if (!Json.bool(rec, "ok", false)) return;

        p.put("passes", Json.num(p, "passes", 0) + 1);
        p.put("lastPassAt", Json.num(rec, "t", System.currentTimeMillis()));

        // A personal best only means anything measured the same way every time, so a run that
        // did not report an active time cannot set one.
        long active = Json.num(rec, "activeMs", -1);
        if (active > 0) {
            long best = Json.num(p, "bestSolveMs", 0);
            if (best == 0 || active < best) p.put("bestSolveMs", active);
        }
    }

    /**
     * Fold a trace into the record.
     *
     * Step count is only a best when the traced run produced the right answer. Without that guard a
     * solution that returns early traces in twelve steps and books a spectacular record for being
     * wrong, which would make the number worse than useless -- it would actively reward the bug.
     */
    public void observeTrace(Map<String,Object> p, long steps, boolean truncated, boolean correct) {
        if (!correct || truncated || steps <= 0) return;
        long best = Json.num(p, "bestSteps", 0);
        if (best == 0 || steps < best) p.put("bestSteps", steps);
    }

    public void record(Map<String,Object> rec) throws IOException {
        store.appendAttempt(rec);
    }

    // ---------- stats ----------

    /** The local calendar day an event belongs to. */
    public static LocalDate dayOf(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * Everything the dashboard needs, in one response.
     *
     * One endpoint rather than one per widget: the heatmap, the streak and the totals are three
     * readings of the same walk over the same list, and three endpoints would mean three walks and
     * three chances for them to disagree about what day it is.
     */
    public Map<String,Object> stats(int days) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate from = today.minusDays(Math.max(1, days) - 1L);

        Map<String,Integer> counts = new LinkedHashMap<>();
        Set<LocalDate> activeDays = new HashSet<>();
        long runs = 0, passes = 0;
        Set<String> solved = new LinkedHashSet<>();

        for (Object a : store.attempts()) {
            if (!Json.str(a, "ev", "").equals("run")) continue;
            long t = Json.num(a, "t", 0);
            if (t <= 0) continue;
            LocalDate d = dayOf(t);

            runs++;
            activeDays.add(d);
            if (Json.bool(a, "ok", false)) {
                passes++;
                solved.add(Json.str(a, "slug", ""));
            }
            if (!d.isBefore(from)) counts.merge(d.toString(), 1, Integer::sum);
        }

        return Json.map(
            "ok", true,
            "today", today.toString(),
            "heatmap", Json.map("from", from.toString(), "counts", counts),
            "totals", Json.map("runs", runs, "passes", passes,
                               "solved", solved.size(), "daysActive", activeDays.size()),
            "streak", streak(activeDays, today));
    }

    /**
     * Consecutive days with at least one attempt.
     *
     * Today not having an attempt yet does not break the streak -- the day is not over. Anyone who
     * opens the app in the morning to a streak reading zero has been told they failed at 9am, and
     * they are right to resent it.
     *
     * (Phase 4 replaces this with the freeze-aware version, behind the same JSON.)
     */
    public static Map<String,Object> streak(Set<LocalDate> activeDays, LocalDate today) {
        boolean todayDone = activeDays.contains(today);
        LocalDate cursor = todayDone ? today : today.minusDays(1);
        long current = 0;
        while (activeDays.contains(cursor)) {
            current++;
            cursor = cursor.minusDays(1);
        }

        long best = 0, run = 0;
        LocalDate prev = null;
        List<LocalDate> sorted = new ArrayList<>(activeDays);
        Collections.sort(sorted);
        for (LocalDate d : sorted) {
            run = (prev != null && prev.plusDays(1).equals(d)) ? run + 1 : 1;
            prev = d;
            if (run > best) best = run;
        }

        return Json.map("current", current, "best", Math.max(best, current),
                        "todayDone", todayDone);
    }
}
