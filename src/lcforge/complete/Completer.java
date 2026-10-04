package lcforge.complete;

import lcforge.Json;

import java.lang.reflect.*;
import java.util.*;

/**
 * Java completion, driven by reflection.
 *
 * The question this answers is "what can I write here", and the only way to answer it honestly is
 * to know the type of what comes before the dot. So: read the receiver expression backwards out of
 * the buffer, resolve it to a type — following declarations, method returns, array indexing and
 * generic arguments — and then reflect on that type for its members.
 *
 * Reflection is the whole trick. The JDK we are running on *is* the JDK your solution compiles
 * against, so the member list is not a hand-written table that drifts: it is the truth, generics
 * included.
 *
 * Everything here fails soft. Half-written code is the normal case, and returning nothing is a
 * much better outcome than returning something wrong.
 */
public final class Completer {

    private static final int MAX_ITEMS = 300;

    /** Members of Object that are never what you meant, pushed to the bottom of the list. */
    private static final Set<String> NOISE = Set.of("wait", "notify", "notifyAll", "getClass");

    // ---------- entry point ----------

    public static Map<String,Object> complete(String code, int offset) {
        if (code == null) code = "";
        offset = Math.max(0, Math.min(offset, code.length()));

        String blanked = SourceScan.blank(code);

        // Inside a comment or a string literal, a completion list is noise. The character before
        // the cursor survives blanking only if it is real code.
        if (offset > 0 && code.charAt(offset - 1) != blanked.charAt(offset - 1))
            return reply("suppress", "", List.of());

        int wordStart = offset;
        while (wordStart > 0 && Character.isJavaIdentifierPart(blanked.charAt(wordStart - 1))) wordStart--;

        Ctx ctx = new Ctx(blanked, wordStart);

        int dot = SourceScan.skipWsBack(blanked, wordStart - 1);
        if (dot < 0 || blanked.charAt(dot) != '.') return reply("globals", "", globals(ctx));

        int start = exprStart(blanked, dot);
        if (start < 0) return reply("none", "", List.of());

        Res recv = resolve(blanked.substring(start, dot), ctx);
        if (recv == null || recv.type.isUnknown()) return reply("none", "", List.of());

        List<Map<String,Object>> items = members(recv, ctx);
        return reply("members", recv.type.display() + (recv.staticRef ? " (static)" : ""), items);
    }

    private static Map<String,Object> reply(String kind, String receiver, List<Map<String,Object>> items) {
        if (items.size() > MAX_ITEMS) items = items.subList(0, MAX_ITEMS);
        return Json.map("ok", true, "kind", kind, "receiver", receiver, "items", items);
    }

    /** What the buffer looks like around the cursor. Built once per request. */
    private static final class Ctx {
        final String src;
        final Map<String,String> decls;
        final Map<String, SourceScan.SourceClass> classes;
        final SourceScan.SourceClass enclosing;
        final int offset;

        Ctx(String blanked, int offset) {
            this.src = blanked;
            this.offset = offset;
            this.decls = SourceScan.declarations(blanked, offset);
            this.classes = SourceScan.classes(blanked);
            this.enclosing = SourceScan.enclosing(classes, offset);
        }
    }

    private static final class Res {
        final JType type;
        final boolean staticRef;   // `Math.` rather than `someObject.`
        Res(JType type, boolean staticRef) { this.type = type; this.staticRef = staticRef; }
    }

    // ---------- finding the receiver expression ----------

    /**
     * Walk backwards from the dot over one chain of calls, indexes and names, and return where it
     * began. Backwards because that is the direction the grammar is unambiguous in: a `)` always
     * has a matching `(`, and what precedes a name is either a `.` continuing the chain or the end
     * of the expression.
     */
    static int exprStart(String s, int dotIndex) {
        int i = SourceScan.skipWsBack(s, dotIndex - 1);
        if (i < 0) return -1;

        while (true) {
            while (i >= 0 && (s.charAt(i) == ')' || s.charAt(i) == ']')) {
                int o = SourceScan.matchBack(s, i);
                if (o < 0) return -1;
                i = SourceScan.skipWsBack(s, o - 1);
            }

            if (i >= 0 && (s.charAt(i) == '"' || s.charAt(i) == '\'')) {
                int o = s.lastIndexOf(s.charAt(i), i - 1);
                if (o < 0) return -1;
                i = o - 1;

            } else if (i >= 0 && Character.isJavaIdentifierPart(s.charAt(i))) {
                while (i >= 0 && Character.isJavaIdentifierPart(s.charAt(i))) i--;
                // `new Foo(...)` — the keyword belongs to the expression, and nothing can precede it.
                int k = SourceScan.skipWsBack(s, i);
                if (k >= 2 && s.startsWith("new", k - 2)
                        && (k == 2 || !Character.isJavaIdentifierPart(s.charAt(k - 3)))) return k - 2;

            } else {
                return i + 1;
            }

            int k = SourceScan.skipWsBack(s, i);
            if (k >= 0 && s.charAt(k) == '.') { i = SourceScan.skipWsBack(s, k - 1); continue; }
            return i + 1;
        }
    }

    // ---------- resolving it to a type ----------

    private static Res resolve(String expr, Ctx ctx) {
        String e = expr.trim();
        if (e.isEmpty()) return null;

        if (e.startsWith("new") && (e.length() == 3 || !Character.isJavaIdentifierPart(e.charAt(3)))) {
            String rest = e.substring(3).trim();
            int paren = rest.indexOf('(');
            int brack = rest.indexOf('[');
            if (brack >= 0 && (paren < 0 || brack < paren)) {
                String dimsText = rest.substring(brack);
                int dims = 0;
                for (int i = 0; i < dimsText.length(); i++) if (dimsText.charAt(i) == '[') dims++;
                return new Res(JType.parse(rest.substring(0, brack)).withDims(dims), false);
            }
            return new Res(JType.parse(paren >= 0 ? rest.substring(0, paren) : rest), false);
        }

        char last = e.charAt(e.length() - 1);

        if (last == '"')  return new Res(JType.of(String.class), false);
        if (last == '\'') return new Res(JType.of(char.class), false);

        if (last == ')') {
            int open = SourceScan.matchBack(e, e.length() - 1);
            if (open < 0) return null;
            String head = e.substring(0, open).trim();
            int dot = lastTopDot(head);
            String name = (dot < 0 ? head : head.substring(dot + 1)).trim();
            if (!isIdent(name)) return null;
            Res recv = dot < 0 ? self(ctx) : resolve(head.substring(0, dot), ctx);
            return methodReturn(recv, name, ctx);
        }

        if (last == ']') {
            int open = SourceScan.matchBack(e, e.length() - 1);
            if (open < 0) return null;
            Res base = resolve(e.substring(0, open), ctx);
            if (base == null || base.type.dims == 0) return null;
            return new Res(base.type.element(), false);
        }

        if (isIdent(e)) {
            if (e.equals("this")) return self(ctx);

            String declared = ctx.decls.get(e);
            if (declared != null) {
                JType t = JType.parse(declared);
                if (!t.isUnknown()) return new Res(t, false);
                Res inferred = inferVar(e, ctx);       // `var x = ...`
                if (inferred != null) return inferred;
            }

            Res field = fieldType(self(ctx), e, ctx);
            if (field != null) return field;

            Class<?> c = Types.lookup(e);
            if (c != null) return new Res(JType.of(c), true);
            if (ctx.classes.containsKey(e) || Types.isSynthetic(e))
                return new Res(JType.named(e, List.of(), 0), true);
            return null;
        }

        int dot = lastTopDot(e);
        if (dot > 0) return fieldType(resolve(e.substring(0, dot), ctx), e.substring(dot + 1).trim(), ctx);
        return null;
    }

    private static Res self(Ctx ctx) {
        return ctx.enclosing == null ? null : new Res(JType.named(ctx.enclosing.name, List.of(), 0), false);
    }

    /** `var x = new ArrayList<Integer>()` — the declaration says nothing, so read the initialiser. */
    private static Res inferVar(String name, Ctx ctx) {
        int at = -1;
        for (int i = ctx.src.indexOf(name); i >= 0 && i < ctx.offset; i = ctx.src.indexOf(name, i + 1)) {
            int after = SourceScan.skipWs(ctx.src, i + name.length());
            if (after < ctx.src.length() && ctx.src.charAt(after) == '=') at = after;
        }
        if (at < 0) return null;
        int semi = ctx.src.indexOf(';', at);
        String rhs = ctx.src.substring(at + 1, semi < 0 ? Math.min(ctx.src.length(), at + 200) : semi);
        Res r = resolve(rhs, ctx);
        return r == null ? null : new Res(r.type, false);
    }

    private static Res methodReturn(Res recv, String name, Ctx ctx) {
        if (recv == null || recv.type.isUnknown()) return null;
        JType t = recv.type;

        if (t.raw != null) {
            Map<String,JType> env = t.env();
            for (Method m : t.raw.getMethods()) {
                if (!m.getName().equals(name) || m.isSynthetic()) continue;
                JType ret = JType.from(m.getGenericReturnType(), env);
                return ret.isUnknown() ? null : new Res(ret, false);
            }
            return null;
        }

        SourceScan.SourceClass sc = ctx.classes.get(t.name);
        if (sc == null) return null;
        for (SourceScan.Member m : sc.members) {
            if (m.kind.equals("method") && m.name.equals(name)) {
                JType ret = JType.parse(m.type);
                return ret.isUnknown() ? null : new Res(ret, false);
            }
        }
        return null;
    }

    private static Res fieldType(Res recv, String name, Ctx ctx) {
        if (recv == null || recv.type.isUnknown() || !isIdent(name)) return null;
        JType t = recv.type;

        if (t.dims > 0) return name.equals("length") ? new Res(JType.of(int.class), false) : null;

        if (t.raw != null) {
            try {
                Field f = t.raw.getField(name);
                JType ft = JType.from(f.getGenericType(), t.env());
                return ft.isUnknown() ? null : new Res(ft, false);
            } catch (NoSuchFieldException e) {
                return null;
            }
        }

        String syn = Types.syntheticFields(t.name).get(name);
        if (syn != null) return new Res(JType.parse(syn), false);

        SourceScan.SourceClass sc = ctx.classes.get(t.name);
        if (sc == null) return null;
        for (SourceScan.Member m : sc.members) {
            if (m.kind.equals("field") && m.name.equals(name)) {
                JType ft = JType.parse(m.type);
                return ft.isUnknown() ? null : new Res(ft, false);
            }
        }
        return null;
    }

    // ---------- turning a type into suggestions ----------

    private static List<Map<String,Object>> members(Res r, Ctx ctx) {
        JType t = r.type;
        List<Map<String,Object>> out = new ArrayList<>();

        if (t.dims > 0) {
            out.add(item("length", "field", "int", "length", "array length", "0length", null));
            out.add(item("clone", "method", t.display(), "clone()", "", "1clone", null));
            return out;
        }

        if (t.raw != null) return reflected(t, r.staticRef);

        for (Map.Entry<String,String> f : Types.syntheticFields(t.name).entrySet())
            out.add(item(f.getKey(), "field", f.getValue(), f.getKey(), t.name, "0" + f.getKey(), null));
        if (!out.isEmpty()) return out;

        SourceScan.SourceClass sc = ctx.classes.get(t.name);
        if (sc == null) return out;
        for (SourceScan.Member m : sc.members) {
            if (r.staticRef != m.isStatic) continue;
            if (m.kind.equals("field")) {
                out.add(item(m.name, "field", m.type, m.name, "in " + sc.name, "0" + m.name, null));
            } else {
                out.add(item(m.name, "method", m.type + " (" + m.params + ")",
                             m.name + snippetArgs(paramTypes(m.params)), "in " + sc.name, "0" + m.name, null));
            }
        }
        return out;
    }

    private static List<Map<String,Object>> reflected(JType t, boolean staticRef) {
        Class<?> c = t.raw;
        Map<String,JType> env = t.env();
        List<Map<String,Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Method m : c.getMethods()) {
            if (m.isSynthetic() || m.isBridge()) continue;
            if (Modifier.isStatic(m.getModifiers()) != staticRef) continue;

            List<String> ps = new ArrayList<>();
            for (Type p : m.getGenericParameterTypes()) ps.add(JType.label(p, env));
            String sig = "(" + String.join(", ", ps) + ")";
            if (!seen.add(m.getName() + sig)) continue;

            String ret = JType.label(m.getGenericReturnType(), env);
            out.add(item(m.getName(), "method", sig + " : " + ret,
                         m.getName() + snippetArgs(ps),
                         owner(m.getDeclaringClass(), c),
                         rank(m.getDeclaringClass(), c, m.getName()) + m.getName(),
                         null));
        }

        for (Field f : c.getFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers()) != staticRef) continue;
            if (!seen.add(f.getName() + "#")) continue;
            out.add(item(f.getName(), "field", JType.label(f.getGenericType(), env), f.getName(),
                         owner(f.getDeclaringClass(), c),
                         rank(f.getDeclaringClass(), c, f.getName()) + f.getName(), null));
        }

        out.sort(Comparator.comparing(m -> String.valueOf(m.get("sort"))));
        return out;
    }

    /** Members of the type itself before inherited ones, and Object's leftovers last. */
    private static String rank(Class<?> declaring, Class<?> self, String name) {
        if (NOISE.contains(name)) return "9";
        if (declaring == Object.class) return "3";
        return declaring == self ? "1" : "2";
    }

    private static String owner(Class<?> declaring, Class<?> self) {
        return declaring == self ? declaring.getSimpleName() : "from " + declaring.getSimpleName();
    }

    /**
     * A Monaco snippet with one tab stop per argument.
     *
     * The placeholder is the parameter's type, not its name: the JDK is not compiled with
     * -parameters, so the names reflection hands back are `arg0`, `arg1`. The type is both true
     * and more useful.
     */
    private static String snippetArgs(List<String> types) {
        if (types.isEmpty()) return "()";
        StringBuilder b = new StringBuilder("(");
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) b.append(", ");
            b.append("${").append(i + 1).append(':').append(types.get(i).replace("}", "")).append('}');
        }
        return b.append(')').toString();
    }

    /** Parameter types out of a source-declared parameter list: `int[] nums, int target`. */
    private static List<String> paramTypes(String params) {
        List<String> out = new ArrayList<>();
        if (params == null || params.isBlank()) return out;
        int depth = 0, start = 0;
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<' || c == '(') depth++;
            else if (c == '>' || c == ')') depth--;
            else if (c == ',' && depth == 0) { parts.add(params.substring(start, i)); start = i + 1; }
        }
        parts.add(params.substring(start));
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            int sp = t.lastIndexOf(' ');
            out.add(sp < 0 ? t : t.substring(0, sp).trim().replaceAll("\\s+", ""));
        }
        return out;
    }

    // ---------- suggestions with no dot in front ----------

    /**
     * Locals and the enclosing class's own members. Monaco's built-in word suggestions already
     * offer the bare identifiers; these carry their types, which is the part worth having.
     */
    private static List<Map<String,Object>> globals(Ctx ctx) {
        List<Map<String,Object>> out = new ArrayList<>();

        for (Map.Entry<String,String> d : ctx.decls.entrySet())
            out.add(item(d.getKey(), "variable", d.getValue(), d.getKey(), "local", "0" + d.getKey(), null));

        if (ctx.enclosing != null) {
            for (SourceScan.Member m : ctx.enclosing.members) {
                if (ctx.decls.containsKey(m.name)) continue;
                if (m.kind.equals("field"))
                    out.add(item(m.name, "field", m.type, m.name, "in " + ctx.enclosing.name, "1" + m.name, null));
                else
                    out.add(item(m.name, "method", m.type + " (" + m.params + ")",
                                 m.name + snippetArgs(paramTypes(m.params)),
                                 "in " + ctx.enclosing.name, "1" + m.name, null));
            }
        }

        for (Map.Entry<String, Class<?>> e : Types.all().entrySet()) {
            Class<?> c = e.getValue();
            if (c.isPrimitive()) continue;
            out.add(item(e.getKey(), "class", c.getPackageName(), e.getKey(),
                         "", "3" + e.getKey(), Types.importFor(c)));
        }

        return out;
    }

    // ---------- plumbing ----------

    private static Map<String,Object> item(String label, String kind, String detail, String insert,
                                           String doc, String sort, String importName) {
        Map<String,Object> m = Json.map(
            "label", label, "kind", kind, "detail", detail, "insert", insert, "doc", doc, "sort", sort);
        if (importName != null) m.put("importName", importName);
        return m;
    }

    private static int lastTopDot(String s) {
        int depth = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ')' || c == ']' || c == '>') depth++;
            else if (c == '(' || c == '[' || c == '<') depth--;
            else if (c == '.' && depth == 0) return i;
        }
        return -1;
    }

    private static boolean isIdent(String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        return true;
    }

    private Completer() {}
}
