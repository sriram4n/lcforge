import lcforge.Json;
import lcforge.Store;
import lcforge.learn.Learn;

import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * The attempt log and everything derived from it. Run via verify.ps1 / verify.sh.
 *
 * The log is the foundation the whole learning layer stands on, so the checks here lean hard on the
 * two ways it could quietly betray you: losing records on reload, and miscounting a day boundary.
 */
public class AttemptLogTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("build", "attempt-test").toAbsolutePath();
        reset(root);

        // --- append and reload ---
        Store s = new Store(root);
        eq("empty log", s.attempts().size(), 0);

        long base = System.currentTimeMillis();
        for (int i = 0; i < 10_000; i++) {
            s.appendAttempt(Json.map("t", base + i, "ev", "run", "slug", "p" + (i % 7),
                                     "ok", i % 3 == 0, "passed", 1, "total", 1));
        }
        eq("in memory after append", s.attempts().size(), 10_000);

        Store reloaded = new Store(root);
        eq("survives reload", reloaded.attempts().size(), 10_000);
        eq("oldest first", Json.num(reloaded.attempts().get(0), "t", -1), base);
        eq("newest last", Json.num(reloaded.attempts().get(9_999), "t", -1), base + 9_999);
        eq("filter by slug", reloaded.attempts("p3").size(), 10_000 / 7 + (3 < 10_000 % 7 ? 1 : 0));
        eq("since cuts", reloaded.attemptsSince(base + 9_000).size(), 1_000);

        // --- a torn final line is one lost attempt, not a dead app ---
        Path file = root.resolve("data").resolve("attempts.jsonl");
        Files.writeString(file, "{\"t\":1,\"ev\":\"run\",\"slug\":\"a\"", StandardOpenOption.APPEND);
        try {
            Store torn = new Store(root);
            eq("torn last line skipped", torn.attempts().size(), 10_000);
        } catch (Exception e) {
            fail("torn last line skipped", "threw " + e);
        }

        // A blank line in the middle is the same story.
        Files.writeString(file, "\n\n", StandardOpenOption.APPEND);
        eq("blank lines skipped", new Store(root).attempts().size(), 10_000);

        // --- a missing file is a fresh start, not an error ---
        reset(root);
        eq("missing file", new Store(root).attempts().size(), 0);

        // --- day bucketing across local midnight ---
        ZoneId zone = ZoneId.systemDefault();
        long midnight = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
        LocalDate before = Learn.dayOf(midnight - 1);
        LocalDate after = Learn.dayOf(midnight + 1);
        check("1ms either side of midnight lands on different days", !before.equals(after));
        eq("after midnight is today", after, LocalDate.now(zone));
        eq("before midnight is yesterday", before, LocalDate.now(zone).minusDays(1));

        // --- streaks ---
        LocalDate today = LocalDate.of(2026, 9, 13);

        eq("no history", cur(streakOf(today, days()), today), 0L);
        eq("today only", cur(streakOf(today, days(0)), today), 1L);
        eq("three contiguous", cur(streakOf(today, days(0, 1, 2)), today), 3L);
        eq("a gap stops it", cur(streakOf(today, days(0, 1, 3, 4)), today), 2L);

        // The one that matters: it is 9am and you have not practised yet. The streak you built is
        // still yours until the day is actually over.
        eq("today not done yet", cur(streakOf(today, days(1, 2, 3)), today), 3L);
        eq("...and is flagged pending", Json.bool(streakOf(today, days(1, 2, 3)), "todayDone", true), false);
        eq("yesterday missed too", cur(streakOf(today, days(2, 3, 4)), today), 0L);

        // Best is the longest run ever, not the current one.
        Map<String,Object> st = streakOf(today, days(0, 1, 10, 11, 12, 13, 14));
        eq("current after a long-ago run", Json.num(st, "current", -1), 2L);
        eq("best remembers it", Json.num(st, "best", -1), 5L);

        // --- Run vs Submit: the one line that decides what counts as solving ---
        List<Object> onePass = List.of(Json.map("verdict", "PASS", "millis", 3));
        Map<String,Object> asRun = Learn.summarizeRun("a", Json.map("mode", "run"), 1, onePass, 40);
        eq("a Run records its mode", asRun.get("mode"), "run");
        eq("a passing Run is not ok", asRun.get("ok"), false);
        eq("...though its verdict still says what happened", asRun.get("verdict"), "PASS");
        Map<String,Object> asSubmit = Learn.summarizeRun("a", Json.map("mode", "submit"), 1, onePass, 40);
        eq("a passing Submit is ok", asSubmit.get("ok"), true);
        Map<String,Object> legacy = Learn.summarizeRun("a", Json.map(), 1, onePass, 40);
        eq("no mode means submit", legacy.get("mode"), "submit");
        eq("...and still counts, as every run did before the split", legacy.get("ok"), true);
        eq("garbage mode means submit", Learn.modeOf(Json.map("mode", "RUN!")), "submit");
        eq("a compile failure carries its mode",
           Learn.summarizeCompileFailure("a", 10, 2, "run").get("mode"), "run");

        reset(root);
        Learn learn = new Learn(new Store(root));
        Map<String,Object> prob = new LinkedHashMap<>();
        learn.observeRun(prob, asRun);
        learn.observeRun(prob, asSubmit);
        eq("runs counts both", Json.num(prob, "runs", 0), 2);
        eq("submits counts only the submit", Json.num(prob, "submits", 0), 1);
        eq("passes counts only the submit", Json.num(prob, "passes", 0), 1);

        // A problem from before the split: five runs, every one of which judged every case.
        Map<String,Object> old = new LinkedHashMap<>(Json.map("runs", 5L, "passes", 3L));
        eq("old runs read as submissions", Store.submitsOf(old), 5);
        learn.observeRun(old, asRun);
        eq("a Run after the split adds a run", Json.num(old, "runs", 0), 6);
        eq("...and carries the old submissions over untouched", Json.num(old, "submits", 0), 5);
        learn.observeRun(old, asSubmit);
        eq("a Submit after it counts on top", Json.num(old, "submits", 0), 6);
        eq("the rate stays honest", Json.num(old, "passes", 0) + "/" + Json.num(old, "submits", 0), "4/6");

        System.out.println();
        System.out.println(failed == 0
            ? "AttemptLog: all " + passed + " checks passed"
            : "AttemptLog: " + passed + " passed, " + failed + " FAILED");
        if (failed > 0) System.exit(1);
    }

    // ---------- helpers ----------

    /** A set of dates, given as "this many days before today". */
    static Set<LocalDate> days(int... agos) {
        Set<LocalDate> out = new HashSet<>();
        for (int a : agos) out.add(LocalDate.of(2026, 9, 13).minusDays(a));
        return out;
    }

    static Map<String,Object> streakOf(LocalDate today, Set<LocalDate> active) {
        return Learn.streak(active, today);
    }

    static long cur(Map<String,Object> streak, LocalDate today) {
        return Json.num(streak, "current", -1);
    }

    static void reset(Path root) throws Exception {
        if (Files.exists(root)) {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) { }
                });
            }
        }
        Files.createDirectories(root);
    }

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else { failed++; System.out.println("  FAIL " + name); }
    }

    static void eq(String name, Object got, Object want) {
        boolean ok = (got instanceof Number && want instanceof Number)
            ? ((Number) got).doubleValue() == ((Number) want).doubleValue()
            : Objects.equals(got, want);
        if (ok) passed++;
        else {
            failed++;
            System.out.println("  FAIL " + name + "\n     got:  " + got + "\n     want: " + want);
        }
    }

    static void fail(String name, String why) {
        failed++;
        System.out.println("  FAIL " + name + "\n     " + why);
    }
}
