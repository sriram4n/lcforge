package lcforge.trace;

import com.sun.jdi.*;
import lcforge.Json;

import java.util.*;

/**
 * Turns a live JDI {@link Value} into something displayable.
 *
 * The hard rule here: never call {@link ObjectReference#invokeMethod}. Invoking toString() on the
 * debuggee resumes its threads to run the call, which corrupts the single-step sequence the whole
 * timeline depends on. So every container is read by walking its internal fields instead --
 * ArrayList's elementData, HashMap's table, StringBuilder's byte[] value. That is why this file
 * knows more about JDK internals than it would like to.
 */
public final class ValueRenderer {

    private static final int MAX_ELEMENTS = 200;   // per array/collection
    private static final int MAX_DEPTH    = 4;
    private static final int MAX_NODES    = 300;   // per linked list / tree

    /** A rendered value: a short display string, plus a structure for the visualiser when apt. */
    public static final class Rendered {
        public final String display;
        public final Map<String,Object> struct;  // null unless this is a list/tree/matrix
        Rendered(String display, Map<String,Object> struct) {
            this.display = display; this.struct = struct;
        }
    }

    public static Rendered render(Value v) {
        return render(v, 0);
    }

    private static Rendered render(Value v, int depth) {
        if (v == null) return new Rendered("null", null);

        if (v instanceof PrimitiveValue) {
            if (v instanceof CharValue)    return new Rendered("'" + ((CharValue) v).value() + "'", null);
            if (v instanceof BooleanValue) return new Rendered(String.valueOf(((BooleanValue) v).value()), null);
            if (v instanceof DoubleValue || v instanceof FloatValue) {
                double d = ((PrimitiveValue) v).doubleValue();
                return new Rendered(d == Math.rint(d) && Math.abs(d) < 1e15
                    ? String.valueOf((long) d) + ".0" : String.valueOf(d), null);
            }
            return new Rendered(v.toString(), null);
        }

        if (v instanceof StringReference) {
            return new Rendered(Json.write(((StringReference) v).value()), null);
        }

        if (v instanceof ArrayReference) {
            return renderArray((ArrayReference) v, depth);
        }

        if (v instanceof ObjectReference) {
            ObjectReference o = (ObjectReference) v;
            ReferenceType t = o.referenceType();
            String name = t.name();

            if (hasFields(t, "val", "next"))          return renderLinkedList(o);
            if (hasFields(t, "val", "left", "right")) return renderTree(o);

            switch (name) {
                case "java.util.ArrayList":       return renderArrayList(o, depth);
                case "java.util.LinkedList":      return renderJdkLinkedList(o, depth);
                case "java.util.HashMap":
                case "java.util.LinkedHashMap":
                case "java.util.TreeMap":         return renderMapLike(o, depth, name);
                case "java.util.HashSet":
                case "java.util.LinkedHashSet":
                case "java.util.TreeSet":         return renderSet(o, depth);
                case "java.util.ArrayDeque":      return renderArrayDeque(o, depth);
                case "java.lang.StringBuilder":
                case "java.lang.StringBuffer":    return renderStringBuilder(o);
                case "java.lang.Integer": case "java.lang.Long":   case "java.lang.Double":
                case "java.lang.Float":   case "java.lang.Short":  case "java.lang.Byte":
                case "java.lang.Boolean": case "java.lang.Character":
                    return renderBox(o);
                default:
                    return renderGeneric(o, depth, name);
            }
        }

        return new Rendered(String.valueOf(v), null);
    }

    // ---------- arrays ----------

    private static Rendered renderArray(ArrayReference a, int depth) {
        int len = a.length();
        if (depth >= MAX_DEPTH) return new Rendered("[... " + len + " items]", null);

        int n = Math.min(len, MAX_ELEMENTS);
        List<Value> vals = n == 0 ? List.of() : a.getValues(0, n);
        List<String> parts = new ArrayList<>(n);
        boolean numericMatrix = true;
        List<Object> rows = new ArrayList<>();

        for (Value e : vals) {
            Rendered r = render(e, depth + 1);
            parts.add(r.display);
            if (e instanceof ArrayReference && depth == 0) {
                ArrayReference row = (ArrayReference) e;
                List<Object> cells = new ArrayList<>();
                int rn = Math.min(row.length(), MAX_ELEMENTS);
                for (Value c : (rn == 0 ? List.<Value>of() : row.getValues(0, rn))) {
                    if (c instanceof PrimitiveValue && !(c instanceof CharValue) && !(c instanceof BooleanValue)) {
                        cells.add(((PrimitiveValue) c).doubleValue());
                    } else {
                        numericMatrix = false;
                        cells.add(render(c, depth + 2).display);
                    }
                }
                rows.add(cells);
            } else {
                numericMatrix = false;
            }
        }

        String display = "[" + String.join(",", parts) + (len > n ? ",... +" + (len - n) : "") + "]";

        // A 2-D array at the top level is worth drawing as a grid (DP tables, matrices).
        Map<String,Object> struct = null;
        if (depth == 0 && !rows.isEmpty()) {
            struct = Json.map("kind", "matrix", "rows", rows, "numeric", numericMatrix);
        }
        return new Rendered(display, struct);
    }

    // ---------- LeetCode node types ----------

    private static Rendered renderLinkedList(ObjectReference head) {
        ReferenceType t = head.referenceType();
        Field valF = t.fieldByName("val"), nextF = t.fieldByName("next");

        List<Object> values = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ObjectReference cur = head;
        boolean cycle = false;

        while (cur != null && values.size() < MAX_NODES) {
            if (!seen.add(cur.uniqueID())) { cycle = true; break; }
            Value val = cur.getValue(valF);
            values.add(val instanceof PrimitiveValue ? ((PrimitiveValue) val).intValue() : 0);
            Value nx = cur.getValue(nextF);
            cur = (nx instanceof ObjectReference) ? (ObjectReference) nx : null;
        }

        StringBuilder d = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) d.append(" -> ");
            d.append(values.get(i));
        }
        if (cycle) d.append(" -> (cycle)");
        else if (values.size() >= MAX_NODES) d.append(" -> ...");

        return new Rendered(d.toString(),
            Json.map("kind", "list", "values", values, "cycle", cycle));
    }

    private static Rendered renderTree(ObjectReference root) {
        ReferenceType t = root.referenceType();
        Field valF = t.fieldByName("val"), leftF = t.fieldByName("left"), rightF = t.fieldByName("right");

        // Level-order with explicit nulls, matching LeetCode's own notation.
        List<Object> level = new ArrayList<>();
        Queue<ObjectReference> q = new LinkedList<>();
        q.add(root);
        int count = 0;
        while (!q.isEmpty() && count < MAX_NODES) {
            ObjectReference n = q.poll();
            if (n == null) { level.add(null); continue; }
            count++;
            Value val = n.getValue(valF);
            level.add(val instanceof PrimitiveValue ? ((PrimitiveValue) val).intValue() : 0);
            Value l = n.getValue(leftF), r = n.getValue(rightF);
            q.add(l instanceof ObjectReference ? (ObjectReference) l : null);
            q.add(r instanceof ObjectReference ? (ObjectReference) r : null);
        }
        while (!level.isEmpty() && level.get(level.size() - 1) == null) level.remove(level.size() - 1);

        List<String> parts = new ArrayList<>();
        for (Object o : level) parts.add(o == null ? "null" : String.valueOf(o));

        return new Rendered("[" + String.join(",", parts) + "]",
            Json.map("kind", "tree", "level", level));
    }

    // ---------- JDK containers, read by field ----------

    private static Rendered renderArrayList(ObjectReference o, int depth) {
        ReferenceType t = o.referenceType();
        int size = intField(o, t, "size", 0);
        Value data = fieldValue(o, t, "elementData");
        List<String> parts = new ArrayList<>();
        if (data instanceof ArrayReference && size > 0) {
            int n = Math.min(size, MAX_ELEMENTS);
            for (Value e : ((ArrayReference) data).getValues(0, n)) {
                parts.add(render(e, depth + 1).display);
            }
        }
        return new Rendered(listDisplay(parts, size), null);
    }

    private static Rendered renderJdkLinkedList(ObjectReference o, int depth) {
        ReferenceType t = o.referenceType();
        int size = intField(o, t, "size", 0);
        List<String> parts = new ArrayList<>();
        Value node = fieldValue(o, t, "first");
        int guard = 0;
        while (node instanceof ObjectReference && guard++ < Math.min(size, MAX_ELEMENTS)) {
            ObjectReference n = (ObjectReference) node;
            ReferenceType nt = n.referenceType();
            parts.add(render(fieldValue(n, nt, "item"), depth + 1).display);
            node = fieldValue(n, nt, "next");
        }
        return new Rendered(listDisplay(parts, size), null);
    }

    private static Rendered renderArrayDeque(ObjectReference o, int depth) {
        ReferenceType t = o.referenceType();
        Value data = fieldValue(o, t, "elements");
        int head = intField(o, t, "head", 0);
        int tail = intField(o, t, "tail", 0);
        List<String> parts = new ArrayList<>();
        if (data instanceof ArrayReference) {
            ArrayReference arr = (ArrayReference) data;
            int cap = arr.length();
            for (int i = head, c = 0; i != tail && c < MAX_ELEMENTS && cap > 0; i = (i + 1) % cap, c++) {
                parts.add(render(arr.getValue(i), depth + 1).display);
            }
        }
        return new Rendered(listDisplay(parts, parts.size()), null);
    }

    private static Rendered renderMapLike(ObjectReference o, int depth, String typeName) {
        ReferenceType t = o.referenceType();
        int size = intField(o, t, "size", 0);
        List<String> parts = new ArrayList<>();

        if (typeName.equals("java.util.TreeMap")) {
            collectTreeMap(fieldValue(o, t, "root"), parts, depth, new int[]{0});
        } else {
            Value table = fieldValue(o, t, "table");
            if (table instanceof ArrayReference) {
                ArrayReference arr = (ArrayReference) table;
                outer:
                for (int i = 0; i < arr.length(); i++) {
                    Value bucket = arr.getValue(i);
                    while (bucket instanceof ObjectReference) {
                        ObjectReference n = (ObjectReference) bucket;
                        ReferenceType nt = n.referenceType();
                        parts.add(render(fieldValue(n, nt, "key"), depth + 1).display + "="
                                + render(fieldValue(n, nt, "value"), depth + 1).display);
                        if (parts.size() >= MAX_ELEMENTS) break outer;
                        bucket = fieldValue(n, nt, "next");
                    }
                }
            }
        }
        String body = String.join(", ", parts);
        if (size > parts.size()) body += ", ... +" + (size - parts.size());
        return new Rendered("{" + body + "}  (" + size + ")", null);
    }

    private static void collectTreeMap(Value node, List<String> out, int depth, int[] count) {
        if (!(node instanceof ObjectReference) || count[0] >= MAX_ELEMENTS) return;
        ObjectReference n = (ObjectReference) node;
        ReferenceType nt = n.referenceType();
        collectTreeMap(fieldValue(n, nt, "left"), out, depth, count);
        if (count[0]++ >= MAX_ELEMENTS) return;
        out.add(render(fieldValue(n, nt, "key"), depth + 1).display + "="
              + render(fieldValue(n, nt, "value"), depth + 1).display);
        collectTreeMap(fieldValue(n, nt, "right"), out, depth, count);
    }

    private static Rendered renderSet(ObjectReference o, int depth) {
        ReferenceType t = o.referenceType();
        Value backing = fieldValue(o, t, "map");   // HashSet/LinkedHashSet wrap a HashMap
        if (backing instanceof ObjectReference) {
            ObjectReference m = (ObjectReference) backing;
            Rendered r = renderMapLike(m, depth, m.referenceType().name());
            // Turn "{a=x, b=x}  (2)" into "[a, b]  (2)" -- for a Set the values are a placeholder.
            String inner = r.display;
            int brace = inner.indexOf('{'), close = inner.lastIndexOf('}');
            if (brace >= 0 && close > brace) {
                String body = inner.substring(brace + 1, close);
                List<String> keys = new ArrayList<>();
                for (String kv : body.split(", ")) {
                    if (kv.isBlank()) continue;
                    int eq = kv.indexOf('=');
                    keys.add(eq > 0 ? kv.substring(0, eq) : kv);
                }
                return new Rendered("[" + String.join(", ", keys) + "]" + inner.substring(close + 1), null);
            }
            return r;
        }
        return new Rendered("[?]", null);
    }

    private static Rendered renderStringBuilder(ObjectReference o) {
        ReferenceType t = o.referenceType();
        int count = intField(o, t, "count", -1);
        Value val = fieldValue(o, t, "value");
        if (val instanceof ArrayReference && count >= 0) {
            ArrayReference arr = (ArrayReference) val;
            int coder = intField(o, t, "coder", 0);   // 0 = LATIN1, 1 = UTF16
            StringBuilder b = new StringBuilder();
            int bytesPerChar = (coder == 0) ? 1 : 2;
            int n = Math.min(count, MAX_ELEMENTS);
            List<Value> bytes = arr.length() == 0 ? List.<Value>of()
                : arr.getValues(0, Math.min(arr.length(), n * bytesPerChar));
            for (int i = 0; i + bytesPerChar - 1 < bytes.size(); i += bytesPerChar) {
                int c = ((PrimitiveValue) bytes.get(i)).intValue() & 0xFF;
                if (bytesPerChar == 2) c |= (((PrimitiveValue) bytes.get(i + 1)).intValue() & 0xFF) << 8;
                b.append((char) c);
            }
            String s = b.toString();
            return new Rendered(Json.write(s) + (count > n ? " ... (len " + count + ")" : ""), null);
        }
        return new Rendered("StringBuilder(len " + count + ")", null);
    }

    private static Rendered renderBox(ObjectReference o) {
        Value v = fieldValue(o, o.referenceType(), "value");
        return render(v, MAX_DEPTH);   // primitives only, no further recursion needed
    }

    private static Rendered renderGeneric(ObjectReference o, int depth, String name) {
        String simple = name.substring(name.lastIndexOf('.') + 1);
        if (depth >= MAX_DEPTH) return new Rendered(simple + "@" + o.uniqueID(), null);

        List<String> parts = new ArrayList<>();
        try {
            for (Field f : o.referenceType().fields()) {
                if (f.isStatic()) continue;
                if (parts.size() >= 12) { parts.add("..."); break; }
                parts.add(f.name() + "=" + render(o.getValue(f), depth + 1).display);
            }
        } catch (RuntimeException e) {
            return new Rendered(simple + "@" + o.uniqueID(), null);
        }
        if (parts.isEmpty()) return new Rendered(simple + "@" + o.uniqueID(), null);
        return new Rendered(simple + "{" + String.join(", ", parts) + "}", null);
    }

    // ---------- helpers ----------

    private static String listDisplay(List<String> parts, int size) {
        String body = String.join(", ", parts);
        if (size > parts.size()) body += ", ... +" + (size - parts.size());
        return "[" + body + "]";
    }

    private static boolean hasFields(ReferenceType t, String... names) {
        for (String n : names) if (t.fieldByName(n) == null) return false;
        return true;
    }

    private static Value fieldValue(ObjectReference o, ReferenceType t, String name) {
        Field f = t.fieldByName(name);
        return f == null ? null : o.getValue(f);
    }

    private static int intField(ObjectReference o, ReferenceType t, String name, int dflt) {
        Value v = fieldValue(o, t, name);
        return (v instanceof PrimitiveValue) ? ((PrimitiveValue) v).intValue() : dflt;
    }

    private ValueRenderer() {}
}
