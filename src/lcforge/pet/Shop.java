package lcforge.pet;

import lcforge.Json;

import java.util.*;

/**
 * What Sparks buy: fuel for the ember, and things to hang on it.
 *
 * The staples are priced on a deliberate bulk curve -- 0.80 Sparks per point of heat at the bottom,
 * 0.55 at the top -- so saving up for a proper load is the rational move rather than a sentimental
 * one. Everything else on the list bends that curve in some direction for a reason, and the reasons
 * are the interesting part.
 */
public final class Shop {

    /** A thing you can burn. */
    public record Fuel(String id, String name, long cost, double heat,
                       String blurb, String effect, long effectMs) {

        Fuel(String id, String name, long cost, double heat, String blurb) {
            this(id, name, cost, heat, blurb, null, 0);
        }

        public Map<String,Object> toMap() {
            Map<String,Object> m = Json.map("id", id, "name", name, "cost", cost,
                                            "heat", heat, "blurb", blurb);
            if (effect != null) m.put("effect", effect);
            return m;
        }
    }

    /** A thing you can wear. Purely decorative, on purpose -- see the note below. */
    public record Cosmetic(String id, String name, long cost, String blurb) {
        public Map<String,Object> toMap() {
            return Json.map("id", id, "name", name, "cost", cost, "blurb", blurb);
        }
    }

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    public static final List<Fuel> FUEL = List.of(
        // ---- the staples ----
        new Fuel("splinter", "Kindling Splinter", 4, 5,
                 "Snapped off a stack frame."),
        new Fuel("briquette", "Boilerplate Briquette", 11, 15,
                 "Dense, joyless, burns forever."),
        new Fuel("charcoal", "Compressed Charcoal", 20, 30,
                 "Nothing clever. Does the job."),
        new Fuel("oak", "Seasoned Oak", 31, 50,
                 "Cut from a deprecated API. It had a good run."),
        new Fuel("anthracite", "Anthracite Block", 55, 100,
                 "A full load. It will not bother you for days."),

        // ---- the interesting ones ----

        // The mercy floor. The ember cannot die, so there must always be a way back from nothing --
        // otherwise "cannot die" just means "stuck at zero", which is worse than dying.
        new Fuel("garbage", "Garbage", 2, 8,
                 "It is not proud. Neither are you."),

        new Fuel("pellet", "Null Pellet", 0, 0,
                 "The ember looks at the pellet. Then at you. Then back at the pellet."),

        new Fuel("offbyone", "Off-by-One Ember", 9, 10,
                 "Nine heat. Or eleven. Nobody has ever been sure."),

        new Fuel("overflow", "Stack Overflow", 14, 10,
                 "It will be up all night.", "hyper", HOUR),

        new Fuel("jerky", "Regex Jerky", 25, 35,
                 "Tough. Nobody remembers how it was made."),

        // The night-before-an-interview item.
        new Fuel("oil", "Midnight Oil", 26, 30,
                 "Burns half as fast for a day.", "oil", DAY),

        new Fuel("greenlog", "Green-Test Log", 18, 25,
                 "Burns green. Only sold on a day something passed.", "green", 12 * HOUR),

        // Best rate on the board, if you come back tomorrow. A trap that is not a trap for anyone
        // who has the habit -- which makes wanting it and deserving it the same thing.
        new Fuel("leak", "Memory Leak", 12, 40,
                 "Forty now. Fifteen back, slowly, over the next day.", "leak", DAY),

        new Fuel("truffle", "Heisenbug Truffle", 40, 45,
                 "It will not touch this while you are watching.")
    );

    /**
     * Cosmetics have no mechanical effect and never will.
     *
     * The moment a hat gives you a bonus, picking one stops being about which one you like and
     * starts being about which one is correct, and a wardrobe with a correct answer is a worse
     * wardrobe. The only purchase here that does anything is the streak freeze, and that is
     * deliberately not worn.
     */
    public static final List<Cosmetic> COSMETICS = List.of(
        new Cosmetic("partyhat", "Party Hat", 45, "For no reason at all."),
        new Cosmetic("hardhat", "Tiny Hard Hat", 60, "Safety first, around all this fire."),
        new Cosmetic("wirerims", "Wire-rims", 80, "Gives it an air of having read the docs."),
        new Cosmetic("scarf", "Scarf", 90, "Takes the colour of whatever palette you are on."),
        new Cosmetic("shades", "Sunglasses", 100, "It is, after all, extremely bright."),
        new Cosmetic("keyboard", "Tiny Keyboard", 120, "It types when you type."),
        new Cosmetic("labcoat", "Lab Coat", 140, "Implies a rigour not necessarily present."),
        new Cosmetic("laurel", "Laurel of Accepted", 200, "Worn by the tired and the correct."),
        new Cosmetic("child", "Child Process", 300, "A smaller ember. It follows the first one.")
    );

    public static final long FREEZE_COST = 60;
    public static final int MAX_FREEZES = 2;

    public static Fuel fuel(String id) {
        for (Fuel f : FUEL) if (f.id().equals(id)) return f;
        return null;
    }

    public static Cosmetic cosmetic(String id) {
        for (Cosmetic c : COSMETICS) if (c.id().equals(id)) return c;
        return null;
    }

    private Shop() {}
}
