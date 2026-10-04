package lcforge.pet;

import lcforge.Json;
import lcforge.Store;
import lcforge.learn.Learn;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * The ember: a small fire that lives at the foot of the editor and goes out if you stop practising.
 *
 * Two rules shape everything here.
 *
 * **It cannot die.** Heat floors at zero and the ember sits there looking terrible, but it is always
 * one meal away from fine and nothing is ever destroyed. A pet that can be lost permanently is a
 * powerful motivator for about three weeks and then, on the first bad week, a reason never to open
 * the app again. The point is to still be doing this in a year.
 *
 * **Time is never simulated.** There is no timer, no scheduler thread, no background tick. Heat is
 * a pure function of how long it has been since the last one, computed on read. That means the
 * server can be off for a month and the arithmetic is still right, and it means the whole decay
 * model is testable by lying about the clock.
 */
public final class Ember {

    /** Full to cold in five days. */
    static final double DECAY_PER_DAY = 20.0;
    static final double MAX_HEAT = 100.0;
    static final long DAY_MS = 86_400_000L;

    /** Memory Leak pays back 15 of its 40 over the following day. */
    static final double LEAK_PER_DAY = 15.0;

    private final Store store;
    private Map<String,Object> pet;

    public Ember(Store store) throws IOException {
        this.store = store;
        load();
    }

    // ---------- persistence ----------

    private void load() throws IOException {
        String raw = store.readDoc("pet");
        Object parsed = raw.isBlank() ? null : Json.parseOrNull(raw);
        pet = parsed == null ? fresh() : new LinkedHashMap<>(Json.obj(parsed));
    }

    private static Map<String,Object> fresh() {
        long now = System.currentTimeMillis();
        return new LinkedHashMap<>(Json.map(
            "name", "Ember",
            "born", now,
            "heat", MAX_HEAT,       // it arrives well. What happens next is up to you.
            "lastTickAt", now,
            "sparks", 0L,
            "lifetimeSparks", 0L,
            "owned", new ArrayList<>(),
            "equipped", new ArrayList<>(),
            "effects", new ArrayList<>(),
            "fedCount", 0L,
            "freezes", 0L));
    }

    private synchronized void save() throws IOException {
        store.writeDoc("pet", Json.write(pet));
    }

    // ---------- decay ----------

    /**
     * Bring heat up to date, then stamp the clock.
     *
     * Stamping is what makes this safe to call as often as anything likes: two reads a millisecond
     * apart drain a millisecond's worth between them, not a full helping each. Getting that wrong
     * would mean the ember starved fastest exactly while you were watching it, which is both a bug
     * and a small betrayal.
     */
    public synchronized Map<String,Object> tick(long now) {
        long last = Json.num(pet, "lastTickAt", now);
        if (now > last) {
            double heat = Json.dbl(pet, "heat", MAX_HEAT) - drain(last, now, effects());
            pet.put("heat", Math.max(0.0, Math.min(MAX_HEAT, heat)));
        }
        pet.put("lastTickAt", Math.max(last, now));
        expireEffects(now);
        return pet;
    }

    /**
     * Heat lost between two instants.
     *
     * Walked in segments split at every effect boundary rather than with one multiplication,
     * because an effect that expires halfway through the gap has to stop applying halfway through
     * the gap. Close the laptop on Friday with Midnight Oil running and the slow burn should cover
     * Saturday and no more.
     */
    public static double drain(long from, long to, List<Object> effects) {
        // Time running backwards costs nothing. It should not be possible -- tick only calls this
        // when the clock has moved on -- but an NTP correction or someone fixing their timezone can
        // hand you a negative interval, and sorting the ends would silently turn that into a full
        // day's burn for a moment that never happened.
        if (to <= from) return 0;

        List<Long> marks = new ArrayList<>(List.of(from, to));
        for (Object e : effects) {
            long until = Json.num(e, "until", 0);
            if (until > from && until < to) marks.add(until);
        }
        Collections.sort(marks);

        double total = 0;
        for (int i = 0; i + 1 < marks.size(); i++) {
            long a = marks.get(i), b = marks.get(i + 1);
            if (b <= a) continue;
            double days = (b - a) / (double) DAY_MS;

            double rate = DECAY_PER_DAY;
            if (activeAt(effects, "oil", a)) rate /= 2;
            if (activeAt(effects, "leak", a)) rate += LEAK_PER_DAY;
            total += rate * days;
        }
        return total;
    }

    private static boolean activeAt(List<Object> effects, String id, long at) {
        for (Object e : effects) {
            if (Json.str(e, "id", "").equals(id) && Json.num(e, "until", 0) > at) return true;
        }
        return false;
    }

    private List<Object> effects() {
        return Json.arr(pet.get("effects"));
    }

    private void expireEffects(long now) {
        List<Object> live = new ArrayList<>();
        for (Object e : effects()) if (Json.num(e, "until", 0) > now) live.add(e);
        pet.put("effects", live);
    }

    private void addEffect(String id, long until) {
        List<Object> live = new ArrayList<>(effects());
        live.removeIf(e -> Json.str(e, "id", "").equals(id));   // re-feeding restarts the clock
        live.add(Json.map("id", id, "until", until));
        pet.put("effects", live);
    }

    // ---------- earning ----------

    static final long EASY = 12, MEDIUM = 30, HARD = 70;

    static long base(String difficulty) {
        // An imported problem always carries its difficulty. A pasted one often does not, and
        // paying the Easy rate for an unknown is the honest way to be wrong.
        return switch (difficulty == null ? "" : difficulty.trim().toLowerCase(Locale.ROOT)) {
            case "hard" -> HARD;
            case "medium" -> MEDIUM;
            default -> EASY;
        };
    }

    /**
     * What a passing run is worth.
     *
     * Pure, and given the ledger and the log rather than reading them, so the whole table is
     * testable without a store: pass in the history you want to have happened.
     *
     * **One payment per problem, ever.** Not one per day, not a reduced rate for a re-solve --
     * once. Anything else makes pressing Run the profitable act, and the whole design falls over
     * the moment you notice that: a tool meant to get you solving problems must not pay you for
     * operating the tool. The only way to earn is to pass something you have never passed.
     *
     * The one bonus left is x1.5 for getting it right on your very first SUBMISSION of that
     * problem, which cannot be farmed because you cannot un-submit a submission. Runs do not spend
     * it: everyone presses Run before Submit, and counting those would have made the bonus
     * unreachable -- dead code -- on the day Run and Submit were split.
     */
    public static long award(String slug, String difficulty, Collection<Object> claimed,
                             List<Object> priorAttempts) {
        if (claimed.contains(slug)) return 0;

        boolean ranBefore = false;
        for (Object a : priorAttempts) {
            if (!Json.str(a, "ev", "").equals("run")) continue;
            // A record from before the split has no mode and counts as a submission, because that
            // is what it was -- so no history is reinterpreted into a bonus it did not earn.
            if (!Learn.modeOf(a).equals(Learn.MODE_SUBMIT)) continue;
            if (Json.str(a, "slug", "").equals(slug)) { ranBefore = true; break; }
        }

        double sparks = base(difficulty);
        if (!ranBefore) sparks *= 1.5;
        return Math.max(1, Math.round(sparks));
    }

    /** The problems that have already been paid for. Mutable: it is the pet's own ledger. */
    public synchronized List<Object> claimed() {
        Object c = pet.get("claimed");
        List<Object> list = (c instanceof List) ? new ArrayList<>((List<?>) c) : new ArrayList<>();
        return list;
    }

    /**
     * Award a finished run and persist. Returns the Sparks paid, 0 if none.
     *
     * Three gates, and all three matter. A run that did not compile has `ok` false. A run with no
     * testcases at all passes "all" of them vacuously, so it is refused on `total`. And a problem
     * already in the ledger pays nothing however many times you run it.
     */
    public synchronized long awardRun(String slug, String difficulty, Map<String,Object> summary)
            throws IOException {
        if (!Json.bool(summary, "ok", false)) return 0;
        if (Json.num(summary, "total", 0) <= 0) return 0;

        List<Object> claimed = claimed();
        // The log is read before the run is appended to it, so "prior" means genuinely prior.
        long sparks = award(slug, difficulty, claimed, store.attempts());
        if (sparks <= 0) return 0;

        long now = Json.num(summary, "t", System.currentTimeMillis());
        tick(now);
        claimed.add(slug);
        pet.put("claimed", claimed);
        pet.put("sparks", Json.num(pet, "sparks", 0) + sparks);
        pet.put("lifetimeSparks", Json.num(pet, "lifetimeSparks", 0) + sparks);
        save();
        return sparks;
    }

    public synchronized void grant(long sparks) throws IOException {
        if (sparks <= 0) return;
        pet.put("sparks", Json.num(pet, "sparks", 0) + sparks);
        pet.put("lifetimeSparks", Json.num(pet, "lifetimeSparks", 0) + sparks);
        save();
    }

    // ---------- feeding ----------

    public record Bite(boolean ok, String note, Map<String,Object> pet) {}

    public synchronized Bite feed(String fuelId, boolean unobserved, long now) throws IOException {
        Shop.Fuel f = Shop.fuel(fuelId);
        if (f == null) return new Bite(false, "No such fuel.", tick(now));
        if (!available(f, now)) return new Bite(false, "Not on the shelf right now.", tick(now));

        tick(now);

        // The truffle is bought, set down, and then ignored until you are. Resolving it is a second
        // call from the page when the window loses focus -- which is the joke, and also the only
        // way a server can know whether anyone was looking.
        if (f.id().equals("truffle") && !unobserved) {
            if (Json.str(pet, "pending", "").equals("truffle")) {
                return new Bite(false, "It is still sitting there. Look away.", pet);
            }
            if (!spend(f.cost())) return new Bite(false, "Not enough Sparks.", pet);
            pet.put("pending", "truffle");
            save();
            return new Bite(true, "Set down. It will not touch it while you are watching.", pet);
        }
        if (f.id().equals("truffle")) {
            if (!Json.str(pet, "pending", "").equals("truffle")) {
                return new Bite(false, "There is no truffle to eat.", pet);
            }
            pet.remove("pending");
            eat(f, now);
            save();
            return new Bite(true, "Gone. It was gone when you turned round.", pet);
        }

        if (!spend(f.cost())) return new Bite(false, "Not enough Sparks.", pet);
        eat(f, now);
        save();
        return new Bite(true, note(f), pet);
    }

    private void eat(Shop.Fuel f, long now) {
        double heat = f.heat();
        // The only random item on the board, and it is random by one point in either direction,
        // which is exactly as much gambling as a study tool should contain.
        if (f.id().equals("offbyone")) heat += new Random().nextBoolean() ? 1 : -1;

        pet.put("heat", Math.min(MAX_HEAT, Json.dbl(pet, "heat", 0) + heat));
        pet.put("fedCount", Json.num(pet, "fedCount", 0) + 1);
        pet.put("lastFedAt", now);
        if (f.effect() != null) addEffect(f.effect(), now + f.effectMs());
    }

    private static String note(Shop.Fuel f) {
        return switch (f.id()) {
            case "pellet" -> "It has decided not to dignify that.";
            case "garbage" -> "Eaten without comment.";
            case "leak" -> "Enthusiastic now. Less so tomorrow.";
            case "oil" -> "Settling in for a long, slow burn.";
            case "overflow" -> "Oh no.";
            default -> null;
        };
    }

    private boolean spend(long cost) {
        long have = Json.num(pet, "sparks", 0);
        if (have < cost) return false;
        pet.put("sparks", have - cost);
        return true;
    }

    /**
     * Whether an item is on the shelf at all.
     *
     * Garbage is the important one: it appears only when things are already bad, so it reads as a
     * last resort rather than as the efficient play. At 0.25 Sparks per heat it is four times
     * better value than anything else on the board, and if it were always available nobody would
     * ever buy anything else.
     */
    private boolean available(Shop.Fuel f, long now) {
        return switch (f.id()) {
            case "garbage" -> Json.dbl(pet, "heat", 0) < 15;
            case "greenlog" -> passedToday(now);
            default -> true;
        };
    }

    private boolean passedToday(long now) {
        LocalDate today = Learn.dayOf(now);
        for (Object a : store.attempts()) {
            if (!Json.str(a, "ev", "").equals("run") || !Json.bool(a, "ok", false)) continue;
            if (Learn.dayOf(Json.num(a, "t", 0)).equals(today)) return true;
        }
        return false;
    }

    // ---------- buying ----------

    public synchronized Bite buy(String itemId, long now) throws IOException {
        tick(now);

        if (itemId.equals("freeze")) {
            if (Json.num(pet, "freezes", 0) >= Shop.MAX_FREEZES) {
                return new Bite(false, "You are already holding two.", pet);
            }
            if (!spend(Shop.FREEZE_COST)) return new Bite(false, "Not enough Sparks.", pet);
            pet.put("freezes", Json.num(pet, "freezes", 0) + 1);
            save();
            return new Bite(true, "Put aside for a day you know you will miss.", pet);
        }

        Shop.Cosmetic c = Shop.cosmetic(itemId);
        if (c == null) return new Bite(false, "No such thing.", pet);

        List<Object> owned = new ArrayList<>(Json.arr(pet.get("owned")));
        if (owned.contains(c.id())) return new Bite(false, "Already owned.", pet);
        if (!spend(c.cost())) return new Bite(false, "Not enough Sparks.", pet);

        owned.add(c.id());
        pet.put("owned", owned);
        // Wear it immediately -- nobody buys a hat in order to keep it in a drawer.
        List<Object> worn = new ArrayList<>(Json.arr(pet.get("equipped")));
        if (!worn.contains(c.id())) worn.add(c.id());
        pet.put("equipped", worn);
        save();
        return new Bite(true, "Suits it.", pet);
    }

    public synchronized Bite equip(String itemId, boolean on, long now) throws IOException {
        tick(now);
        if (!Json.arr(pet.get("owned")).contains(itemId)) {
            return new Bite(false, "You do not own that.", pet);
        }
        List<Object> worn = new ArrayList<>(Json.arr(pet.get("equipped")));
        worn.remove(itemId);
        if (on) worn.add(itemId);
        pet.put("equipped", worn);
        save();
        return new Bite(true, null, pet);
    }

    public synchronized Bite rename(String name, long now) throws IOException {
        tick(now);
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) return new Bite(false, "It needs some kind of name.", pet);
        if (clean.length() > 24) clean = clean.substring(0, 24);
        pet.put("name", clean);
        save();
        return new Bite(true, null, pet);
    }

    // ---------- the view the page gets ----------

    public static String stateOf(double heat) {
        if (heat >= 85) return "blazing";
        if (heat >= 60) return "bright";
        if (heat >= 35) return "steady";
        if (heat >= 15) return "guttering";
        return "cold";
    }

    public synchronized Map<String,Object> view(long now) throws IOException {
        tick(now);
        save();   // the clock moved, so the file is stale even if nothing else happened

        double heat = Json.dbl(pet, "heat", 0);
        Map<String,Object> out = new LinkedHashMap<>(pet);
        out.put("heat", Math.round(heat * 10) / 10.0);
        out.put("state", stateOf(heat));
        out.put("ageDays", (now - Json.num(pet, "born", now)) / DAY_MS);

        List<Object> shelf = new ArrayList<>();
        for (Shop.Fuel f : Shop.FUEL) {
            Map<String,Object> m = f.toMap();
            m.put("available", available(f, now));
            shelf.add(m);
        }
        List<Object> wares = new ArrayList<>();
        for (Shop.Cosmetic c : Shop.COSMETICS) wares.add(c.toMap());

        return Json.map("ok", true, "pet", out, "fuel", shelf, "cosmetics", wares,
                        "freezeCost", Shop.FREEZE_COST, "maxFreezes", Shop.MAX_FREEZES);
    }

    public synchronized Map<String,Object> wrap(Bite b, long now) {
        Map<String,Object> p = new LinkedHashMap<>(pet);
        double heat = Json.dbl(pet, "heat", 0);
        p.put("heat", Math.round(heat * 10) / 10.0);
        p.put("state", stateOf(heat));
        Map<String,Object> out = Json.map("ok", b.ok(), "pet", p);
        if (b.note() != null) out.put("note", b.note());
        if (!b.ok() && b.note() != null) out.put("error", b.note());
        return out;
    }

    public synchronized long sparks() { return Json.num(pet, "sparks", 0); }
}
