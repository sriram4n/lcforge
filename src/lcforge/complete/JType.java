package lcforge.complete;

import java.lang.reflect.*;
import java.util.*;

/**
 * A type as the completer needs to see it: a raw class when it is one we can reflect on, a bare
 * name when it is not (a class declared in your buffer, or ListNode/TreeNode), plus type arguments
 * and array depth.
 *
 * Type arguments are carried around because they are the entire point. Without them,
 * {@code map.get(k).} on a {@code Map<String, List<Integer>>} can only offer Object's members,
 * which is exactly the moment autocomplete stops being worth having.
 */
public final class JType {

    public static final JType UNKNOWN = new JType(null, "?", List.of(), 0);

    public final Class<?> raw;     // null when the type is not on our classpath
    public final String name;      // simple name, always set
    public final List<JType> args;
    public final int dims;

    private JType(Class<?> raw, String name, List<JType> args, int dims) {
        this.raw = raw;
        this.name = name;
        this.args = List.copyOf(args);
        this.dims = dims;
    }

    public static JType of(Class<?> c) { return of(c, List.of()); }

    public static JType of(Class<?> c, List<JType> args) {
        int d = 0;
        while (c.isArray()) { c = c.getComponentType(); d++; }
        return new JType(c, c.getSimpleName(), args, d);
    }

    /** By simple name: resolved against the known classes, else kept as a bare name. */
    public static JType named(String name, List<JType> args, int dims) {
        Class<?> c = Types.lookup(name);
        return new JType(c, c == null ? name : c.getSimpleName(), args, dims);
    }

    public JType withDims(int d) { return new JType(raw, name, args, d); }

    public boolean isUnknown() { return raw == null && (name.equals("?") || name.isEmpty()); }
    public boolean isArray()   { return dims > 0; }
    public JType element()     { return dims > 0 ? withDims(dims - 1) : UNKNOWN; }

    public String display() {
        StringBuilder b = new StringBuilder(name);
        if (!args.isEmpty()) {
            b.append('<');
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(args.get(i).display());
            }
            b.append('>');
        }
        return b.append("[]".repeat(dims)).toString();
    }

    /**
     * Type-variable name to argument, for substituting into a member's generic signature.
     *
     * Keyed by name rather than by position because a member is often declared on a supertype:
     * {@code ArrayList<Integer>.get} is inherited from {@code List<E>}. Matching on the letter
     * works because the collections framework names its variables consistently (E, K, V) — a
     * coincidence, but a very reliable one, and the cost of being wrong is a suggestion list that
     * falls back to Object rather than anything incorrect.
     */
    public Map<String,JType> env() {
        if (raw == null || args.isEmpty()) return Map.of();
        TypeVariable<?>[] vars = raw.getTypeParameters();
        Map<String,JType> env = new HashMap<>();
        for (int i = 0; i < vars.length && i < args.size(); i++) env.put(vars[i].getName(), args.get(i));
        return env;
    }

    /** Reflection's generic type to ours, substituting type variables out of {@code env}. */
    public static JType from(Type t, Map<String,JType> env) {
        if (t instanceof Class<?> c) return of(c);

        if (t instanceof ParameterizedType p) {
            List<JType> as = new ArrayList<>();
            for (Type a : p.getActualTypeArguments()) as.add(from(a, env));
            return of((Class<?>) p.getRawType(), as);
        }
        if (t instanceof GenericArrayType g) {
            JType c = from(g.getGenericComponentType(), env);
            return c.withDims(c.dims + 1);
        }
        if (t instanceof TypeVariable<?> v) {
            JType bound = env.get(v.getName());
            if (bound != null) return bound;
            Type[] bs = v.getBounds();
            return bs.length > 0 && bs[0] != Object.class ? from(bs[0], env) : UNKNOWN;
        }
        if (t instanceof WildcardType w) {
            Type[] up = w.getUpperBounds();
            return up.length > 0 ? from(up[0], env) : UNKNOWN;
        }
        return UNKNOWN;
    }

    /**
     * Display form for a signature. An unsubstituted variable keeps its own letter — showing
     * {@code E next()} is more informative than {@code ? next()}.
     */
    public static String label(Type t, Map<String,JType> env) {
        JType j = from(t, env);
        if (!j.isUnknown()) return j.display();
        return t instanceof TypeVariable<?> v ? v.getName() : simple(t.getTypeName());
    }

    /** Parse a written type: {@code Map<String, List<Integer>>[]}. */
    public static JType parse(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) return UNKNOWN;

        int dims = 0;
        while (s.endsWith("]")) {
            int open = s.lastIndexOf('[');
            if (open < 0) break;
            s = s.substring(0, open).trim();
            dims++;
        }

        List<JType> args = List.of();
        int lt = s.indexOf('<');
        if (lt >= 0 && s.endsWith(">")) {
            String inner = s.substring(lt + 1, s.length() - 1);
            // The diamond carries no arguments; it must not be read as one unknown argument.
            args = inner.isBlank() ? List.of() : splitArgs(inner);
            s = s.substring(0, lt).trim();
        }

        // `java.util.List` -> `List`, but only when the whole thing is not itself a known name
        // (Map.Entry is registered under its dotted form).
        if (Types.lookup(s) == null) {
            int dot = s.lastIndexOf('.');
            if (dot >= 0) s = s.substring(dot + 1);
        }

        // `var` carries no information of its own; the caller infers from the initialiser.
        if (s.isEmpty() || s.equals("var")) return UNKNOWN;
        return named(s, args, dims);
    }

    /** Split `String, List<Integer>` on top-level commas only. */
    private static List<JType> splitArgs(String s) {
        List<JType> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) { out.add(parse(s.substring(start, i))); start = i + 1; }
        }
        out.add(parse(s.substring(start)));
        return out;
    }

    private static String simple(String typeName) {
        int lt = typeName.indexOf('<');
        String head = lt < 0 ? typeName : typeName.substring(0, lt);
        int dot = head.lastIndexOf('.');
        return dot < 0 ? typeName : typeName.substring(dot + 1);
    }

    @Override public String toString() { return display(); }
}
