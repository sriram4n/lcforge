package lcforge.judge;

import lcforge.Json;

import java.util.*;

/**
 * Decides whether an answer is right.
 *
 * Deliberately does NOT accept a differently-ordered answer under the default mode -- that would
 * hide real bugs (a rotate-array solution that rotates the wrong way produces a permutation of
 * the right answer). When exact match fails but an order-insensitive match would succeed, the
 * verdict is {@link Verdict#NEAR_MISS}: still a failure, but the UI can explain why and offer to
 * switch the problem to unordered mode if order genuinely does not matter.
 */
public final class Compare {

    public enum Verdict { PASS, NEAR_MISS, FAIL }

    public static final String MODE_EXACT     = "exact";
    public static final String MODE_UNORDERED = "unordered";
    public static final String MODE_FLOAT     = "float";

    private static final double EPS = 1e-5;

    public static final class Outcome {
        public final Verdict verdict;
        public final String note;
        Outcome(Verdict v, String note) { this.verdict = v; this.note = note; }
        /**
         * Only PASS counts. NEAR_MISS is a diagnostic -- "right values, wrong order" -- and
         * must never be accepted, or a permuted answer would be marked correct in exact mode.
         */
        public boolean passed() { return verdict == Verdict.PASS; }
    }

    public static Outcome check(String actual, String expected, String mode) {
        if (expected == null || expected.isBlank()) {
            return new Outcome(Verdict.FAIL, "no expected output recorded for this case");
        }
        String a = actual == null ? "" : actual.trim();
        String e = expected.trim();

        if (MODE_FLOAT.equals(mode)) {
            return floatEqual(a, e)
                ? new Outcome(Verdict.PASS, null)
                : new Outcome(Verdict.FAIL, "differs by more than " + EPS);
        }

        if (a.equals(e)) return new Outcome(Verdict.PASS, null);

        Object pa = Json.parseOrNull(a);
        Object pe = Json.parseOrNull(e);

        // Same values, different formatting (spaces, 1 vs 1.0, true vs True).
        if (pa != null || pe != null) {
            if (deepEqual(pa, pe, false)) return new Outcome(Verdict.PASS, null);
        }

        if (MODE_UNORDERED.equals(mode)) {
            return deepEqual(pa, pe, true)
                ? new Outcome(Verdict.PASS, null)
                : new Outcome(Verdict.FAIL, null);
        }

        // Exact mode: report the near-miss rather than a bare FAIL.
        if (deepEqual(pa, pe, true)) {
            return new Outcome(Verdict.NEAR_MISS,
                "same elements, different order -- switch this problem to unordered mode if order does not matter");
        }

        if (isNumeric(a) && isNumeric(e) && floatEqual(a, e)) {
            return new Outcome(Verdict.NEAR_MISS,
                "matches within " + EPS + " -- switch to float mode for this problem");
        }

        return new Outcome(Verdict.FAIL, null);
    }

    /** Structural comparison; when {@code unordered}, lists are compared as multisets. */
    private static boolean deepEqual(Object a, Object b, boolean unordered) {
        if (a == null || b == null) return Objects.equals(a, b);

        if (a instanceof List && b instanceof List) {
            List<Object> la = Json.arr(a), lb = Json.arr(b);
            if (la.size() != lb.size()) return false;
            if (!unordered) {
                for (int i = 0; i < la.size(); i++) if (!deepEqual(la.get(i), lb.get(i), false)) return false;
                return true;
            }
            // Multiset match by canonical form, with inner lists also normalized.
            List<String> ka = new ArrayList<>(), kb = new ArrayList<>();
            for (Object o : la) ka.add(canonical(o));
            for (Object o : lb) kb.add(canonical(o));
            Collections.sort(ka);
            Collections.sort(kb);
            return ka.equals(kb);
        }

        if (a instanceof Number && b instanceof Number) {
            double da = ((Number) a).doubleValue(), db = ((Number) b).doubleValue();
            return da == db || Math.abs(da - db) <= EPS * Math.max(1.0, Math.abs(db));
        }

        if (a instanceof Boolean || b instanceof Boolean) {
            return String.valueOf(a).equalsIgnoreCase(String.valueOf(b));
        }

        return String.valueOf(a).equals(String.valueOf(b));
    }

    /** Order-insensitive canonical form, applied recursively so nested lists also normalize. */
    private static String canonical(Object o) {
        if (o instanceof List) {
            List<String> parts = new ArrayList<>();
            for (Object e : Json.arr(o)) parts.add(canonical(e));
            Collections.sort(parts);
            return "[" + String.join(",", parts) + "]";
        }
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            return (d == Math.rint(d)) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return Json.write(o);
    }

    private static boolean floatEqual(String a, String b) {
        Object pa = Json.parseOrNull(a), pb = Json.parseOrNull(b);
        if (pa instanceof Number && pb instanceof Number) {
            double da = ((Number) pa).doubleValue(), db = ((Number) pb).doubleValue();
            return Math.abs(da - db) <= EPS * Math.max(1.0, Math.abs(db));
        }
        if (pa instanceof List && pb instanceof List) {
            List<Object> la = Json.arr(pa), lb = Json.arr(pb);
            if (la.size() != lb.size()) return false;
            for (int i = 0; i < la.size(); i++) {
                if (!floatEqual(Json.write(la.get(i)), Json.write(lb.get(i)))) return false;
            }
            return true;
        }
        return a.trim().equals(b.trim());
    }

    private static boolean isNumeric(String s) {
        return Json.parseOrNull(s) instanceof Number;
    }

    private Compare() {}
}
