package lcforge.judge;

import lcforge.Json;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Converts between LeetCode's testcase literals and real Java values.
 *
 * This is the load-bearing file of the whole judge. Everything else assumes that a line like
 * {@code [3,9,20,null,null,15,7]} becomes a correctly-shaped {@code TreeNode}, and that whatever
 * the solution returns becomes the exact string LeetCode would have printed.
 *
 * ListNode/TreeNode are handled reflectively rather than by direct reference: they live in the
 * default package inside the user's child classloader, so this class cannot name them at compile
 * time. Detection is by simple name plus field shape, which also picks up hand-written variants.
 */
public final class TypeCodec {

    // ---------- decode: literal -> Java ----------

    /** Decode one testcase line into the declared parameter type. */
    public static Object decode(String line, Type target) {
        String trimmed = line == null ? "" : line.trim();

        // Strip a "name = " prefix if the user pasted the raw LeetCode example line.
        int eq = leadingAssignment(trimmed);
        if (eq >= 0) trimmed = trimmed.substring(eq + 1).trim();

        Object parsed;
        try {
            parsed = Json.parse(trimmed);
        } catch (RuntimeException e) {
            // Unquoted bare string is the common paste mistake; accept it for String targets.
            if (rawClass(target) == String.class) return trimmed;
            throw new CodecException("could not parse input: " + trimmed);
        }
        return coerce(parsed, target);
    }

    /** Index of a top-level '=' in "nums = [1,2]", or -1. Ignores '=' inside quotes/brackets. */
    private static int leadingAssignment(String s) {
        int depth = 0;
        boolean inStr = false;
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == q) inStr = false;
                continue;
            }
            if (c == '"' || c == '\'') { inStr = true; q = c; continue; }
            if (c == '[' || c == '{' || c == '(') depth++;
            else if (c == ']' || c == '}' || c == ')') depth--;
            else if (c == '=' && depth == 0) {
                // Reject ==, <=, >= so expressions are not mistaken for assignments.
                if (i + 1 < s.length() && s.charAt(i + 1) == '=') return -1;
                if (i > 0 && "<>!=".indexOf(s.charAt(i - 1)) >= 0) return -1;
                // Everything before it must look like an identifier.
                String name = s.substring(0, i).trim();
                if (name.isEmpty() || !name.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_' || ch == '$')) return -1;
                return i;
            }
        }
        return -1;
    }

    public static Object coerce(Object v, Type target) {
        Class<?> raw = rawClass(target);

        if (raw == Object.class) return v;

        // --- primitives and their boxes ---
        if (raw == int.class     || raw == Integer.class)   return v == null ? null : (int)   num(v);
        if (raw == long.class    || raw == Long.class)      return v == null ? null : (long)  num(v);
        if (raw == double.class  || raw == Double.class)    return v == null ? null : num(v);
        if (raw == float.class   || raw == Float.class)     return v == null ? null : (float) num(v);
        if (raw == short.class   || raw == Short.class)     return v == null ? null : (short) num(v);
        if (raw == byte.class    || raw == Byte.class)      return v == null ? null : (byte)  num(v);
        if (raw == boolean.class || raw == Boolean.class)   return toBool(v);
        if (raw == char.class    || raw == Character.class) return toChar(v);
        if (raw == String.class) return v == null ? null : String.valueOf(v);

        // --- arrays ---
        if (raw.isArray()) {
            if (v == null) return null;
            List<Object> items = asList(v);
            Class<?> comp = raw.getComponentType();
            Object out = Array.newInstance(comp, items.size());
            for (int i = 0; i < items.size(); i++) Array.set(out, i, coerce(items.get(i), comp));
            return out;
        }

        // --- collections ---
        if (List.class.isAssignableFrom(raw) || Collection.class.isAssignableFrom(raw)) {
            if (v == null) return null;
            Type elem = typeArg(target, 0);
            List<Object> items = asList(v);
            List<Object> out = new ArrayList<>(items.size());
            for (Object it : items) out.add(coerce(it, elem));
            if (Set.class.isAssignableFrom(raw)) return new LinkedHashSet<>(out);
            return out;
        }

        // --- LeetCode node types (reflective) ---
        if (isListNode(raw)) return buildList(v, raw);
        if (isTreeNode(raw)) return buildTree(v, raw);

        if (v == null) return null;
        throw new CodecException("unsupported parameter type: " + target.getTypeName()
                + " (supported: primitives, String, their arrays, List, ListNode, TreeNode)");
    }

    private static double num(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.length() == 1 && !Character.isDigit(s.charAt(0))) return s.charAt(0);
            return Double.parseDouble(s);
        }
        throw new CodecException("expected a number, got " + describe(v));
    }

    private static Object toBool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean) return v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        String s = String.valueOf(v).trim();
        if (s.equalsIgnoreCase("true") || s.equals("1")) return Boolean.TRUE;
        if (s.equalsIgnoreCase("false") || s.equals("0")) return Boolean.FALSE;
        throw new CodecException("expected a boolean, got " + describe(v));
    }

    private static Object toChar(Object v) {
        if (v == null) return null;
        if (v instanceof Character) return v;
        if (v instanceof Number) return (char) ((Number) v).intValue();
        String s = String.valueOf(v);
        if (s.length() != 1) throw new CodecException("expected a single character, got \"" + s + "\"");
        return s.charAt(0);
    }

    private static List<Object> asList(Object v) {
        if (v instanceof List) return Json.arr(v);
        // A quoted string standing in for char[]: "abc" -> ['a','b','c']
        if (v instanceof String) {
            List<Object> out = new ArrayList<>();
            for (char c : ((String) v).toCharArray()) out.add(String.valueOf(c));
            return out;
        }
        throw new CodecException("expected an array, got " + describe(v));
    }

    // ---------- node construction ----------

    /** LeetCode linked-list format: [1,2,4] */
    private static Object buildList(Object v, Class<?> nodeCls) {
        if (v == null) return null;
        List<Object> items = asList(v);
        Object head = null, tail = null;
        try {
            Field valF = nodeCls.getField("val");
            Field nextF = nodeCls.getField("next");
            for (Object item : items) {
                Object node = nodeCls.getDeclaredConstructor().newInstance();
                valF.setInt(node, (int) num(item));
                if (head == null) { head = node; tail = node; }
                else { nextF.set(tail, node); tail = node; }
            }
        } catch (ReflectiveOperationException e) {
            throw new CodecException("cannot construct " + nodeCls.getSimpleName() + ": " + e);
        }
        return head;
    }

    /**
     * LeetCode binary-tree format: level-order with explicit nulls for missing children,
     * e.g. [3,9,20,null,null,15,7]. Note this is NOT a heap layout -- nulls do not reserve
     * slots for their would-be children, which is the classic place to get this wrong.
     */
    private static Object buildTree(Object v, Class<?> nodeCls) {
        if (v == null) return null;
        List<Object> items = asList(v);
        if (items.isEmpty() || items.get(0) == null) return null;
        try {
            Field valF   = nodeCls.getField("val");
            Field leftF  = nodeCls.getField("left");
            Field rightF = nodeCls.getField("right");

            Object root = nodeCls.getDeclaredConstructor().newInstance();
            valF.setInt(root, (int) num(items.get(0)));

            Deque<Object> queue = new ArrayDeque<>();
            queue.add(root);
            int i = 1;
            while (!queue.isEmpty() && i < items.size()) {
                Object parent = queue.poll();
                // left child
                if (i < items.size()) {
                    Object item = items.get(i++);
                    if (item != null) {
                        Object child = nodeCls.getDeclaredConstructor().newInstance();
                        valF.setInt(child, (int) num(item));
                        leftF.set(parent, child);
                        queue.add(child);
                    }
                }
                // right child
                if (i < items.size()) {
                    Object item = items.get(i++);
                    if (item != null) {
                        Object child = nodeCls.getDeclaredConstructor().newInstance();
                        valF.setInt(child, (int) num(item));
                        rightF.set(parent, child);
                        queue.add(child);
                    }
                }
            }
            return root;
        } catch (ReflectiveOperationException e) {
            throw new CodecException("cannot construct " + nodeCls.getSimpleName() + ": " + e);
        }
    }

    // ---------- encode: Java -> canonical literal ----------

    /** Render a returned value exactly as LeetCode would print it. */
    public static String encode(Object v) {
        StringBuilder b = new StringBuilder();
        enc(v, b, new IdentityHashMap<>());
        return b.toString();
    }

    private static void enc(Object v, StringBuilder b, IdentityHashMap<Object,Boolean> seen) {
        if (v == null) { b.append("null"); return; }

        Class<?> c = v.getClass();

        if (v instanceof String)    { b.append(Json.write(v)); return; }
        if (v instanceof Character) { b.append(Json.write(String.valueOf(v))); return; }
        if (v instanceof Boolean)   { b.append(v.toString()); return; }
        if (v instanceof Double || v instanceof Float) { b.append(formatDouble(((Number) v).doubleValue())); return; }
        if (v instanceof Number)    { b.append(v.toString()); return; }

        if (isListNode(c)) { encListNode(v, b); return; }
        if (isTreeNode(c)) { encTreeNode(v, b); return; }

        if (c.isArray()) {
            b.append('[');
            int n = Array.getLength(v);
            for (int i = 0; i < n; i++) {
                if (i > 0) b.append(',');
                enc(Array.get(v, i), b, seen);
            }
            b.append(']');
            return;
        }

        if (v instanceof Collection) {
            b.append('[');
            boolean first = true;
            for (Object e : (Collection<?>) v) {
                if (!first) b.append(',');
                first = false;
                enc(e, b, seen);
            }
            b.append(']');
            return;
        }

        if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?,?> e : ((Map<?,?>) v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append(Json.write(String.valueOf(e.getKey()))).append(':');
                enc(e.getValue(), b, seen);
            }
            b.append('}');
            return;
        }

        b.append(Json.write(String.valueOf(v)));
    }

    private static void encListNode(Object head, StringBuilder b) {
        b.append('[');
        try {
            Field valF = head.getClass().getField("val");
            Field nextF = head.getClass().getField("next");
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Object cur = head;
            boolean first = true;
            while (cur != null) {
                if (!seen.add(cur)) { b.append(first ? "" : ",").append("\"...cycle\""); break; }
                if (!first) b.append(',');
                first = false;
                b.append(valF.getInt(cur));
                cur = nextF.get(cur);
            }
        } catch (ReflectiveOperationException e) {
            throw new CodecException("cannot read linked list: " + e);
        }
        b.append(']');
    }

    /** Level-order with interior nulls preserved and trailing nulls trimmed, like LeetCode. */
    private static void encTreeNode(Object root, StringBuilder b) {
        List<String> out = new ArrayList<>();
        try {
            Field valF   = root.getClass().getField("val");
            Field leftF  = root.getClass().getField("left");
            Field rightF = root.getClass().getField("right");

            // LinkedList, not ArrayDeque: nulls are enqueued on purpose to mark absent children,
            // and ArrayDeque rejects them.
            Queue<Object> q = new LinkedList<>();
            q.add(root);
            int guard = 0;
            while (!q.isEmpty() && guard++ < 100000) {
                Object n = q.poll();
                if (n == null) { out.add("null"); continue; }
                out.add(String.valueOf(valF.getInt(n)));
                q.add(leftF.get(n));
                q.add(rightF.get(n));
            }
        } catch (ReflectiveOperationException e) {
            throw new CodecException("cannot read tree: " + e);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).equals("null")) out.remove(out.size() - 1);
        b.append('[').append(String.join(",", out)).append(']');
    }

    private static String formatDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            long l = (long) d;
            return l + ".00000";
        }
        return String.format(Locale.ROOT, "%.5f", d);
    }

    // ---------- type helpers ----------

    public static Class<?> rawClass(Type t) {
        if (t instanceof Class) return (Class<?>) t;
        if (t instanceof ParameterizedType) return (Class<?>) ((ParameterizedType) t).getRawType();
        if (t instanceof java.lang.reflect.GenericArrayType) {
            Class<?> comp = rawClass(((java.lang.reflect.GenericArrayType) t).getGenericComponentType());
            return Array.newInstance(comp, 0).getClass();
        }
        if (t instanceof java.lang.reflect.WildcardType) {
            Type[] up = ((java.lang.reflect.WildcardType) t).getUpperBounds();
            return up.length > 0 ? rawClass(up[0]) : Object.class;
        }
        if (t instanceof java.lang.reflect.TypeVariable) return Object.class;
        return Object.class;
    }

    private static Type typeArg(Type t, int i) {
        if (t instanceof ParameterizedType) {
            Type[] args = ((ParameterizedType) t).getActualTypeArguments();
            if (i < args.length) return args[i];
        }
        return Object.class;
    }

    public static boolean isListNode(Class<?> c) {
        return hasFields(c, "val", "next");
    }

    public static boolean isTreeNode(Class<?> c) {
        return hasFields(c, "val", "left", "right");
    }

    private static boolean hasFields(Class<?> c, String... names) {
        if (c == null || c.isPrimitive() || c.isArray()) return false;
        for (String n : names) {
            try { c.getField(n); } catch (NoSuchFieldException e) { return false; }
        }
        return true;
    }

    private static String describe(Object v) {
        if (v == null) return "null";
        return v.getClass().getSimpleName() + " " + Json.write(v);
    }

    public static class CodecException extends RuntimeException {
        public CodecException(String m) { super(m); }
    }

    private TypeCodec() {}
}
