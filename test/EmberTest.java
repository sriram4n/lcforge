import lcforge.Json;
import lcforge.Store;
import lcforge.learn.Learn;
import lcforge.pet.Ember;
import lcforge.pet.Shop;

import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * The ember: decay, the award table, and the shop. Run via verify.ps1 / verify.sh.
 *
 * Weighted towards the arithmetic, because every bug in it is silent. Nothing throws when a pet
 * starves twice as fast as it should; it just quietly stops being fair, and by the time it is
 * obvious you have lost the ability to tell whether you deserved it.
 */
public class EmberTest {

    static int passed = 0, failed = 0;

    static final long DAY = 86_400_000L;
    static final long HOUR = 3_600_000L;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("build", "ember-test").toAbsolutePath();

        // ---------- decay ----------

        // The headline rate: twenty a day, five days from full to cold.
        near("a day costs 20", Ember.drain(0, DAY, List.of()), 20.0);
        near("half a day costs 10", Ember.drain(0, DAY / 2, List.of()), 10.0);
        near("five days empties it", Ember.drain(0, 5 * DAY, List.of()), 100.0);
        near("no time, no cost", Ember.drain(0, 0, List.of()), 0.0);
        near("backwards is free", Ember.drain(DAY, 0, List.of()), 0.0);

        // Midnight Oil halves the rate, but only while it lasts. A day of oil then a day without
        // is ten plus twenty, not fifteen twice -- which is what a single average would have given.
        List<Object> oil = List.of(Json.map("id", "oil", "until", DAY));
        near("oil halves its own day", Ember.drain(0, DAY, oil), 10.0);
        near("oil stops when it stops", Ember.drain(0, 2 * DAY, oil), 30.0);
        near("oil already expired", Ember.drain(2 * DAY, 3 * DAY, oil), 20.0);

        // Memory Leak's 15 comes back out over the following day, and then stops.
        List<Object> leak = List.of(Json.map("id", "leak", "until", DAY));
        near("leak adds 15 over its day", Ember.drain(0, DAY, leak), 35.0);
        near("leak stops after a day", Ember.drain(0, 2 * DAY, leak), 55.0);
        near("net of a leak is +25", 40 - (Ember.drain(0, DAY, leak) - Ember.drain(0, DAY, List.of())), 25.0);

        // ---------- ticking is idempotent ----------

        reset(root);
        Store s = new Store(root);
        Ember e = new Ember(s);
        long t0 = System.currentTimeMillis();

        e.tick(t0);
        double h1 = heat(e.tick(t0));
        double h2 = heat(e.tick(t0));
        double h3 = heat(e.tick(t0));
        eq("reading it does not burn it", h2, h1);
        eq("...however often you look", h3, h1);

        near("36 hours costs 30", heat(e.tick(t0 + 36 * HOUR)), 70.0);
        near("another 12 costs 10 more", heat(e.tick(t0 + 48 * HOUR)), 60.0);

        // A month away bottoms out and stays there. This is the "never dies" guarantee: however
        // long you are gone, you come back to a sad ember and not a negative number.
        near("a month away floors at zero", heat(e.tick(t0 + 30L * DAY)), 0.0);
        near("and stays floored", heat(e.tick(t0 + 60L * DAY)), 0.0);

        // ---------- states ----------

        eq("100 is blazing", Ember.stateOf(100), "blazing");
        eq("85 is blazing", Ember.stateOf(85), "blazing");
        eq("84 is bright", Ember.stateOf(84), "bright");
        eq("59 is steady", Ember.stateOf(59), "steady");
        eq("34 is guttering", Ember.stateOf(34), "guttering");
        eq("14 is cold", Ember.stateOf(14), "cold");
        eq("0 is cold", Ember.stateOf(0), "cold");

        // ---------- feeding ----------

        reset(root);
        Ember f = new Ember(new Store(root));
        long now = System.currentTimeMillis();
        f.tick(now);

        eq("broke", f.feed("anthracite", false, now).ok(), false);
        f.grant(1000);
        eq("now affordable", f.feed("anthracite", false, now).ok(), true);
        near("full is full, never more", heat(f.tick(now)), 100.0);
        eq("and it charged for it", f.sparks(), 1000L - 55);

        eq("no such fuel", f.feed("caviar", false, now).ok(), false);

        // Garbage is the way back from nothing, so it must be there at nothing and nowhere else.
        eq("garbage hidden when well fed", f.feed("garbage", false, now).ok(), false);
        long cold = now + 5 * DAY;
        f.tick(cold);
        near("five days later, cold", heat(f.tick(cold)), 0.0);
        eq("garbage on the shelf when cold", f.feed("garbage", false, cold).ok(), true);
        near("and it helps", heat(f.tick(cold)), 8.0);

        // The pellet is free and does nothing, which is the entire joke.
        long before = f.sparks();
        eq("pellet accepted", f.feed("pellet", false, cold).ok(), true);
        eq("pellet is free", f.sparks(), before);

        // The truffle is bought, set down, and only eaten once nobody is looking.
        double preTruffle = heat(f.tick(cold));
        eq("truffle set down", f.feed("truffle", false, cold).ok(), true);
        near("not eaten while watched", heat(f.tick(cold)), preTruffle);
        eq("cannot put down two", f.feed("truffle", false, cold).ok(), false);
        eq("eaten when unobserved", f.feed("truffle", true, cold).ok(), true);
        near("and then it counts", heat(f.tick(cold)), Math.min(100, preTruffle + 45));
        eq("nothing left to eat", f.feed("truffle", true, cold).ok(), false);

        // ---------- buying ----------

        reset(root);
        Ember b = new Ember(new Store(root));
        eq("cannot afford a hat", b.buy("hardhat", now).ok(), false);
        b.grant(500);
        eq("bought", b.buy("hardhat", now).ok(), true);
        eq("not twice", b.buy("hardhat", now).ok(), false);
        eq("no such thing", b.buy("sombrero", now).ok(), false);
        eq("worn on purchase", worn(b, now).contains("hardhat"), true);
        eq("can take it off", b.equip("hardhat", false, now).ok(), true);
        eq("...and it comes off", worn(b, now).contains("hardhat"), false);
        eq("cannot wear what you lack", b.equip("laurel", true, now).ok(), false);

        eq("freeze bought", b.buy("freeze", now).ok(), true);
        eq("second freeze", b.buy("freeze", now).ok(), true);
        eq("but never a third", b.buy("freeze", now).ok(), false);

        eq("named", b.rename("Cinder", now).ok(), true);
        eq("blank name refused", b.rename("   ", now).ok(), false);

        // ---------- the award table ----------

        long t = System.currentTimeMillis();
        List<Object> none = List.of();
        Set<Object> unclaimed = Set.of();

        eq("easy, cold start", Ember.award("a", "Easy", unclaimed, none), 18L);       // 12 x 1.5
        eq("medium, cold start", Ember.award("a", "Medium", unclaimed, none), 45L);   // 30 x 1.5
        eq("hard, cold start", Ember.award("a", "Hard", unclaimed, none), 105L);      // 70 x 1.5
        eq("unknown pays easy", Ember.award("a", "", unclaimed, none), 18L);
        eq("case insensitive", Ember.award("a", "hard", unclaimed, none), 105L);

        // Having already run at it costs you the first-try bonus, not the problem.
        List<Object> failedEarlier = List.of(run("a", false, t - HOUR));
        eq("no bonus after a failure", Ember.award("a", "Medium", unclaimed, failedEarlier), 30L);
        eq("no bonus after a compile error either",
           Ember.award("a", "Medium", unclaimed, List.of(compileFail("a", t - HOUR))), 30L);

        // The ledger is the whole anti-farm rule: paid once, never again.
        Set<Object> paid = Set.of("a");
        eq("a claimed problem pays nothing", Ember.award("a", "Hard", paid, none), 0L);
        eq("...not even a week later", Ember.award("a", "Hard", paid, List.of(run("a", true, t - 7 * DAY))), 0L);
        eq("...and a new problem still pays", Ember.award("b", "Medium", paid, none), 45L);

        // Someone else's history is none of this problem's business.
        List<Object> otherSlug = List.of(run("b", true, t - HOUR));
        eq("another problem does not cost the bonus", Ember.award("a", "Medium", unclaimed, otherSlug), 45L);

        // A trace on the same problem is not a run and must not spend the first-try bonus.
        List<Object> traced = List.of(Json.map("ev", "trace", "slug", "a", "t", t - HOUR));
        eq("a trace is not a run", Ember.award("a", "Medium", unclaimed, traced), 45L);

        // ---------- Run vs Submit ----------
        //
        // Everyone presses Run before they press Submit. If a Run spent the first-try bonus, the
        // bonus would be unreachable in practice -- dead code that still reads as a feature.
        List<Object> ranFirst = List.of(Json.map("t", t - HOUR, "ev", "run", "slug", "a", "mode", "run", "ok", false));
        eq("a prior Run keeps the first-submit bonus", Ember.award("a", "Medium", unclaimed, ranFirst), 45L);
        List<Object> submittedFirst = List.of(Json.map("t", t - HOUR, "ev", "run", "slug", "a", "mode", "submit", "ok", false));
        eq("a prior failed Submit spends it", Ember.award("a", "Medium", unclaimed, submittedFirst), 30L);
        // (A record from before the split has no mode, and "no bonus after a failure" above already
        // proves it still counts as the submission it was.)

        // ---------- awardRun: the gates that actually face the user ----------
        //
        // The pure table above is the arithmetic; this is the part that decides whether holding
        // Ctrl+Enter prints money. Every one of these was reported as a bug before it was a test.

        reset(root);
        Ember w = new Ember(new Store(root));

        eq("a compile failure pays nothing",
           w.awardRun("a", "Hard", Json.map("ok", false, "total", 0, "verdict", "COMPILE", "t", t)), 0L);
        eq("a partial pass pays nothing",
           w.awardRun("a", "Hard", Json.map("ok", false, "passed", 2, "total", 3, "t", t)), 0L);
        // 0 == 0 is a vacuous "all passed", and a problem with no cases would otherwise mint on
        // every press. This is the subtle one.
        eq("a run with no cases at all pays nothing",
           w.awardRun("a", "Hard", Json.map("ok", true, "passed", 0, "total", 0, "t", t)), 0L);
        eq("nothing was banked", w.sparks(), 0L);

        Map<String,Object> pass = Json.map("ok", true, "passed", 3, "total", 3, "t", t);
        eq("a real pass pays", w.awardRun("a", "Medium", pass), 45L);
        eq("the same problem again pays nothing", w.awardRun("a", "Medium", pass), 0L);
        eq("and again", w.awardRun("a", "Medium", pass), 0L);
        eq("balance is exactly one award", w.sparks(), 45L);

        eq("a different problem still pays", w.awardRun("b", "Easy", pass), 18L);
        eq("the ledger remembers both", new HashSet<>(w.claimed()), new HashSet<>(List.of("a", "b")));

        // A restart must not reopen the ledger.
        Ember w2 = new Ember(new Store(root));
        eq("claims survive a reload", w2.awardRun("a", "Medium", pass), 0L);

        // A Run cannot pay -- and, worse if it could, must not burn the one payment a problem is
        // ever worth by landing in the ledger with nothing paid. Real records, real flow: the Run
        // is logged, then the Submit arrives.
        Store rs = new Store(root);
        Ember w3 = new Ember(rs);
        List<Object> oneCase = List.of(Json.map("verdict", "PASS", "millis", 2));
        Map<String,Object> runPass = Learn.summarizeRun("c", Json.map("mode", "run"), 1, oneCase, 5);
        eq("a passing Run pays nothing", w3.awardRun("c", "Hard", runPass), 0L);
        check("...and leaves the problem unclaimed", !w3.claimed().contains("c"));
        rs.appendAttempt(runPass);
        Map<String,Object> submitPass = Learn.summarizeRun("c", Json.map("mode", "submit"), 1, oneCase, 5);
        eq("the Submit after it pays in full, bonus and all", w3.awardRun("c", "Hard", submitPass), 105L);

        // ---------- the shop's own shape ----------

        Set<String> ids = new HashSet<>();
        boolean dupes = false, badCurve = false;
        for (Shop.Fuel fu : Shop.FUEL) if (!ids.add(fu.id())) dupes = true;
        for (Shop.Cosmetic c : Shop.COSMETICS) if (!ids.add(c.id())) dupes = true;
        check("every id is unique", !dupes);

        // The staples must get cheaper per point as they get bigger, or saving up is irrational
        // and the whole economy collapses into buying splinters forever.
        String[] staples = { "splinter", "briquette", "charcoal", "oak", "anthracite" };
        double prev = Double.MAX_VALUE;
        for (String id : staples) {
            Shop.Fuel fu = Shop.fuel(id);
            double rate = fu.cost() / fu.heat();
            if (rate >= prev) badCurve = true;
            prev = rate;
        }
        check("staples reward saving up", !badCurve);
        near("the cheap end costs 0.80/heat", Shop.fuel("splinter").cost() / Shop.fuel("splinter").heat(), 0.80);
        near("the deep end costs 0.55/heat", Shop.fuel("anthracite").cost() / Shop.fuel("anthracite").heat(), 0.55);

        // A day's decay must cost noticeably less than a day's easiest work, or the pet is a tax.
        double dayCost = 20 * (Shop.fuel("charcoal").cost() / Shop.fuel("charcoal").heat());
        check("one easy problem covers more than a day", Ember.award("x", "Easy", Set.of(), List.of()) > dayCost);
        check("one hard problem covers about five", Ember.award("x", "Hard", Set.of(), List.of()) > 4 * dayCost);

        System.out.println();
        System.out.println(failed == 0
            ? "Ember: all " + passed + " checks passed"
            : "Ember: " + passed + " passed, " + failed + " FAILED");
        if (failed > 0) System.exit(1);
    }

    // ---------- helpers ----------

    static Map<String,Object> run(String slug, boolean ok, long t) {
        return Json.map("t", t, "ev", "run", "slug", slug, "ok", ok);
    }

    static Map<String,Object> compileFail(String slug, long t) {
        return Json.map("t", t, "ev", "run", "slug", slug, "ok", false, "verdict", "COMPILE");
    }

    static double heat(Map<String,Object> pet) {
        return Json.dbl(pet, "heat", -1);
    }

    static List<Object> worn(Ember e, long now) throws Exception {
        return Json.arr(Json.obj(e.view(now).get("pet")).get("equipped"));
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

    static void near(String name, double got, double want) {
        if (Math.abs(got - want) < 0.01) passed++;
        else {
            failed++;
            System.out.println("  FAIL " + name + "\n     got:  " + got + "\n     want: " + want);
        }
    }

    static void eq(String name, Object got, Object want) {
        boolean ok = (got instanceof Number && want instanceof Number)
            ? Math.abs(((Number) got).doubleValue() - ((Number) want).doubleValue()) < 1e-9
            : Objects.equals(got, want);
        if (ok) passed++;
        else {
            failed++;
            System.out.println("  FAIL " + name + "\n     got:  " + got + "\n     want: " + want);
        }
    }
}
