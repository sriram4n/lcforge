package lcforge.complete;

import java.util.*;
import java.util.regex.*;

/**
 * Reading the buffer without parsing it.
 *
 * A real parser is the wrong tool here: the text is usually half-written — that is why you are
 * asking for a completion — so anything that insists on a valid parse tree gives you nothing at
 * exactly the moment you need something. These routines scan text and give up quietly when the
 * shape is unfamiliar.
 */
public final class SourceScan {

    /** Words that can start a statement and would otherwise be mistaken for a type. */
    private static final Set<String> NOT_TYPES = Set.of(
        "return", "new", "throw", "else", "case", "break", "continue", "import", "package",
        "assert", "yield", "do", "instanceof", "null", "true", "false", "this", "super");

    // ---------- blanking ----------

    /**
     * Replace the contents of comments and literals with spaces, preserving length so every offset
     * stays valid against the original text.
     *
     * Everything downstream — brace matching, declaration scanning, deciding whether the cursor
     * sits after a real `.` — runs against the blanked copy, so a brace inside a string or a stray
     * quote inside a comment cannot throw it off.
     */
    public static String blank(String src) {
        char[] out = src.toCharArray();
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);

            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') out[i++] = ' ';

            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) out[i++] = ' ';
                if (i < n) out[i++] = ' ';

            } else if (c == '"' || c == '\'') {
                // The quotes themselves survive: the expression resolver recognises a string
                // literal by its closing quote.
                i++;
                while (i < n && src.charAt(i) != c) {
                    if (src.charAt(i) == '\\') {
                        out[i] = ' ';
                        i++;
                        if (i < n && src.charAt(i) != '\n') out[i] = ' ';
                        if (i < n) i++;
                        continue;
                    }
                    if (src.charAt(i) != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) i++;

            } else {
                i++;
            }
        }
        return new String(out);
    }

    // ---------- bracket matching ----------

    /** Index of the bracket closing the one at {@code open}, or -1. */
    public static int matchForward(String s, int open) {
        char o = s.charAt(open), c = close(o);
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == o) depth++;
            else if (ch == c && --depth == 0) return i;
        }
        return -1;
    }

    /** Index of the bracket opening the one at {@code close}, or -1. */
    public static int matchBack(String s, int close) {
        char c = s.charAt(close), o = open(c);
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char ch = s.charAt(i);
            if (ch == c) depth++;
            else if (ch == o && --depth == 0) return i;
        }
        return -1;
    }

    private static char close(char c) { return c == '(' ? ')' : c == '[' ? ']' : c == '{' ? '}' : '>'; }
    private static char open(char c)  { return c == ')' ? '(' : c == ']' ? '[' : c == '}' ? '{' : '<'; }

    public static int skipWsBack(String s, int i) {
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        return i;
    }

    public static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    // ---------- declarations ----------

    /**
     * Every variable declared before {@code before}, as name to written type.
     *
     * Found by working backwards from each identifier rather than forwards from a type pattern,
     * because a type can be arbitrarily nested — {@code Map<String, List<int[]>>} defeats any
     * regex, but reading right-to-left it is just a balanced pair and a word.
     *
     * The scan covers the whole file rather than only the enclosing method. For a file the size of
     * a LeetCode solution, the occasional out-of-scope name costs less than missing the one you
     * wanted.
     */
    public static Map<String,String> declarations(String blanked, int before) {
        Map<String,String> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\\b[A-Za-z_$][\\w$]*\\b").matcher(blanked);
        while (m.find()) {
            if (m.end() > before) break;
            if (NOT_TYPES.contains(m.group())) continue;

            int after = skipWs(blanked, m.end());
            if (after >= blanked.length()) break;
            char f = blanked.charAt(after);
            // `=` assignment or initialiser, `;` bare declaration, `:` for-each,
            // `,` and `)` parameter lists.
            if (f != '=' && f != ';' && f != ':' && f != ',' && f != ')') continue;
            if (f == '=' && after + 1 < blanked.length() && blanked.charAt(after + 1) == '=') continue;

            String type = typeBefore(blanked, m.start());
            if (type != null) out.put(m.group(), type);
        }
        return out;
    }

    /** The written type immediately before {@code pos}, or null if what is there is not one. */
    public static String typeBefore(String blanked, int pos) {
        int i = skipWsBack(blanked, pos - 1);
        if (i < 0) return null;
        int end = i + 1;

        while (i >= 0 && blanked.charAt(i) == ']') {
            int o = matchBack(blanked, i);
            if (o < 0) return null;
            i = skipWsBack(blanked, o - 1);
        }
        if (i >= 0 && blanked.charAt(i) == '>') {
            int o = matchBack(blanked, i);
            if (o < 0) return null;
            i = o - 1;
        }

        int nameEnd = i + 1;
        while (i >= 0 && (Character.isJavaIdentifierPart(blanked.charAt(i)) || blanked.charAt(i) == '.')) i--;
        int nameStart = i + 1;
        if (nameStart >= nameEnd) return null;

        String first = blanked.substring(nameStart, nameEnd);
        if (!Character.isJavaIdentifierStart(first.charAt(0))) return null;
        int dot = first.lastIndexOf('.');
        if (NOT_TYPES.contains(dot < 0 ? first : first.substring(dot + 1))) return null;

        // A `.` in front means this was a field access being read, not a type being written.
        int k = skipWsBack(blanked, nameStart - 1);
        if (k >= 0 && blanked.charAt(k) == '.') return null;

        return blanked.substring(nameStart, end).replaceAll("\\s+", "");
    }

    // ---------- classes declared in the buffer ----------

    public static final class Member {
        public final String kind;    // "method" | "field"
        public final String name;
        public final String type;    // return type, or the field's type
        public final String params;  // raw parameter text, "" for a field
        public final boolean isStatic;

        Member(String kind, String name, String type, String params, boolean isStatic) {
            this.kind = kind; this.name = name; this.type = type;
            this.params = params; this.isStatic = isStatic;
        }
    }

    public static final class SourceClass {
        public final String name;
        public final int bodyStart, bodyEnd;
        public final List<Member> members;

        SourceClass(String name, int bodyStart, int bodyEnd, List<Member> members) {
            this.name = name; this.bodyStart = bodyStart; this.bodyEnd = bodyEnd; this.members = members;
        }
    }

    private static final Pattern DECL_HEAD = Pattern.compile(
        "\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");

    private static final String MODS =
        "(?:(?:public|private|protected|static|final|abstract|synchronized|native|strictfp|default|transient|volatile)\\s+)*";

    private static final Pattern METHOD = Pattern.compile(
        "^\\s*" + MODS + "(?:<[^>]*>\\s*)?([\\w.$]+(?:\\s*<.*>)?(?:\\s*\\[\\s*\\])*)\\s+"
        + "([A-Za-z_$][\\w$]*)\\s*\\(([\\s\\S]*)\\)\\s*(?:throws[\\w.,\\s]+)?$", Pattern.DOTALL);

    private static final Pattern CTOR = Pattern.compile(
        "^\\s*" + MODS + "([A-Za-z_$][\\w$]*)\\s*\\(([\\s\\S]*)\\)\\s*(?:throws[\\w.,\\s]+)?$", Pattern.DOTALL);

    private static final Pattern FIELD = Pattern.compile(
        "^\\s*" + MODS + "([\\w.$]+(?:\\s*<.*>)?(?:\\s*\\[\\s*\\])*)\\s+([A-Za-z_$][\\w$]*)\\s*(?:=[\\s\\S]*)?$",
        Pattern.DOTALL);

    /** Classes declared in the buffer, by name. Covers helper classes and design problems. */
    public static Map<String,SourceClass> classes(String blanked) {
        Map<String,SourceClass> out = new LinkedHashMap<>();
        Matcher m = DECL_HEAD.matcher(blanked);
        while (m.find()) {
            int brace = blanked.indexOf('{', m.end());
            if (brace < 0) continue;
            int end = matchForward(blanked, brace);
            if (end < 0) end = blanked.length() - 1;
            out.put(m.group(1), new SourceClass(m.group(1), brace, end, members(blanked, brace + 1, end)));
        }
        return out;
    }

    /** The class whose body encloses {@code offset} — innermost wins. */
    public static SourceClass enclosing(Map<String,SourceClass> classes, int offset) {
        SourceClass best = null;
        for (SourceClass c : classes.values()) {
            if (offset <= c.bodyStart || offset > c.bodyEnd) continue;
            if (best == null || c.bodyStart > best.bodyStart) best = c;
        }
        return best;
    }

    private static List<Member> members(String s, int from, int to) {
        List<Member> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int i = from;
        while (i < to) {
            char c = s.charAt(i);

            if (c == ';') {
                addIfMember(out, buf.toString(), "field");
                buf.setLength(0);
                i++;

            } else if (c == '{') {
                int end = matchForward(s, i);
                if (end < 0) break;
                // `int[] a = {1, 2};` is a field with a brace in it, not a method body.
                if (buf.indexOf("=") >= 0) {
                    i = end + 1;
                    continue;
                }
                addIfMember(out, buf.toString(), "method");
                buf.setLength(0);
                i = end + 1;

            } else {
                buf.append(c);
                i++;
            }
        }
        return out;
    }

    private static void addIfMember(List<Member> out, String text, String kind) {
        String t = text.trim();
        if (t.isEmpty()) return;
        boolean isStatic = t.startsWith("static ") || t.contains(" static ");

        if (kind.equals("method")) {
            Matcher m = METHOD.matcher(t);
            if (m.matches()) {
                out.add(new Member("method", m.group(2), m.group(1).replaceAll("\\s+", ""),
                                   m.group(3).trim(), isStatic));
                return;
            }
            Matcher c = CTOR.matcher(t);
            // A constructor has no return type, so it only reaches here when METHOD did not match.
            if (c.matches()) out.add(new Member("method", c.group(1), c.group(1), c.group(2).trim(), false));
            return;
        }

        Matcher f = FIELD.matcher(t);
        if (!f.matches()) return;
        String type = f.group(1).replaceAll("\\s+", "");
        int dot = type.lastIndexOf('.');
        if (NOT_TYPES.contains(dot < 0 ? type : type.substring(dot + 1))) return;
        out.add(new Member("field", f.group(2), type, "", isStatic));
    }

    private SourceScan() {}
}
