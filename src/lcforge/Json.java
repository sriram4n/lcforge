package lcforge;

import java.util.*;

/**
 * Minimal JSON parser / emitter.
 *
 * Exists instead of a dependency because LeetCode's testcase format is JSON-ish but not JSON
 * (bare tokens, single-quoted chars), so a strict library would need wrapping anyway. Parses
 * into plain Java: Map&lt;String,Object&gt;, List&lt;Object&gt;, String, Long, Double, Boolean, null.
 */
public final class Json {

    // ---------- parsing ----------

    public static Object parse(String src) {
        P p = new P(src);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length()) throw new JsonException("trailing input at " + p.i);
        return v;
    }

    /** Lenient variant: returns null instead of throwing. */
    public static Object parseOrNull(String src) {
        try { return parse(src); } catch (RuntimeException e) { return null; }
    }

    public static class JsonException extends RuntimeException {
        public JsonException(String m) { super(m); }
    }

    private static final class P {
        final String s; int i = 0;
        P(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        char peek() {
            if (i >= s.length()) throw new JsonException("unexpected end of input");
            return s.charAt(i);
        }

        Object value() {
            ws();
            char c = peek();
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string('"');
                case '\'': return string('\'');   // lenient: LeetCode sometimes shows 'a'
                case 't': lit("true");  return Boolean.TRUE;
                case 'f': lit("false"); return Boolean.FALSE;
                case 'n': lit("null");  return null;
                default:  return number();
            }
        }

        void lit(String w) {
            if (!s.startsWith(w, i)) throw new JsonException("expected " + w + " at " + i);
            i += w.length();
        }

        Map<String,Object> object() {
            Map<String,Object> m = new LinkedHashMap<>();
            i++; // consume {
            ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                char q = peek();
                if (q != '"' && q != '\'') throw new JsonException("expected key at " + i);
                String k = string(q);
                ws();
                if (peek() != ':') throw new JsonException("expected ':' at " + i);
                i++;
                m.put(k, value());
                ws();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return m; }
                throw new JsonException("expected ',' or '}' at " + i);
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; // consume [
            ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return l; }
                throw new JsonException("expected ',' or ']' at " + i);
            }
        }

        String string(char quote) {
            i++; // opening quote
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new JsonException("unterminated string");
                char c = s.charAt(i++);
                if (c == quote) return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case '"':  b.append('"');  break;
                    case '\'': b.append('\''); break;
                    case '\\': b.append('\\'); break;
                    case '/':  b.append('/');  break;
                    case 'b':  b.append('\b'); break;
                    case 'f':  b.append('\f'); break;
                    case 'n':  b.append('\n'); break;
                    case 'r':  b.append('\r'); break;
                    case 't':  b.append('\t'); break;
                    case 'u':
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new JsonException("bad escape");
                }
            }
        }

        Object number() {
            int st = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean real = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' || c == 'e' || c == 'E') {
                    real = true; i++;
                } else if ((c == '-' || c == '+') && (s.charAt(i - 1) == 'e' || s.charAt(i - 1) == 'E')) {
                    i++;
                } else {
                    break;
                }
            }
            String t = s.substring(st, i);
            if (t.isEmpty()) throw new JsonException("expected value at " + st);
            if (real) return Double.parseDouble(t);
            try { return Long.parseLong(t); }
            catch (NumberFormatException e) { return Double.parseDouble(t); }
        }
    }

    // ---------- emitting ----------

    public static String write(Object o) {
        StringBuilder b = new StringBuilder();
        emit(o, b);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void emit(Object o, StringBuilder b) {
        if (o == null) { b.append("null"); return; }
        if (o instanceof String)  { quote((String) o, b); return; }
        if (o instanceof Boolean) { b.append(o.toString()); return; }
        if (o instanceof Character) { quote(o.toString(), b); return; }
        if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
            else b.append(d);
            return;
        }
        if (o instanceof Number) { b.append(o.toString()); return; }
        if (o instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?,?> e : ((Map<?,?>) o).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), b);
                b.append(':');
                emit(e.getValue(), b);
            }
            b.append('}');
            return;
        }
        if (o instanceof Iterable) {
            b.append('[');
            boolean first = true;
            for (Object e : (Iterable<Object>) o) {
                if (!first) b.append(',');
                first = false;
                emit(e, b);
            }
            b.append(']');
            return;
        }
        if (o.getClass().isArray()) {
            b.append('[');
            int n = java.lang.reflect.Array.getLength(o);
            for (int k = 0; k < n; k++) {
                if (k > 0) b.append(',');
                emit(java.lang.reflect.Array.get(o, k), b);
            }
            b.append(']');
            return;
        }
        quote(o.toString(), b);
    }

    private static void quote(String s, StringBuilder b) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                case '\b': b.append("\\b");  break;
                case '\f': b.append("\\f");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    // ---------- convenience accessors ----------

    @SuppressWarnings("unchecked")
    public static Map<String,Object> obj(Object o) {
        return (o instanceof Map) ? (Map<String,Object>) o : new LinkedHashMap<String,Object>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return (o instanceof List) ? (List<Object>) o : new ArrayList<Object>();
    }

    public static String str(Object o, String key, String dflt) {
        Object v = obj(o).get(key);
        return v == null ? dflt : String.valueOf(v);
    }

    public static long num(Object o, String key, long dflt) {
        Object v = obj(o).get(key);
        return (v instanceof Number) ? ((Number) v).longValue() : dflt;
    }

    /**
     * Like num(), but keeps the fraction.
     *
     * The ember's heat, a pattern's decayed mastery and a review card's ease factor are all real
     * numbers, and reading them back through num() would silently floor them -- an ease of 2.5
     * becoming 2, and a decay of under a point per read rounding away to nothing at all.
     */
    public static double dbl(Object o, String key, double dflt) {
        Object v = obj(o).get(key);
        return (v instanceof Number) ? ((Number) v).doubleValue() : dflt;
    }

    public static boolean bool(Object o, String key, boolean dflt) {
        Object v = obj(o).get(key);
        return (v instanceof Boolean) ? (Boolean) v : dflt;
    }

    /** Ordered map literal: map("a", 1, "b", 2). */
    public static Map<String,Object> map(Object... kv) {
        Map<String,Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private Json() {}
}
