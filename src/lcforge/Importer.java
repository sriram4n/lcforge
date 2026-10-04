package lcforge;

import java.util.*;
import java.util.regex.*;

/**
 * Turns pasted LeetCode problem text into a structured problem record.
 *
 * The Chrome extension is the good path (it reads LeetCode's own GraphQL response), but pasting
 * has to work too -- for contest problems, for when the extension is not installed, and for when
 * LeetCode changes something. This parser is deliberately forgiving: it extracts what it can and
 * leaves the rest for you to fix in the UI.
 */
public final class Importer {

    private static final Pattern EXAMPLE  = Pattern.compile("(?im)^\\s*Example\\s*\\d*\\s*:?\\s*$");
    private static final Pattern INPUT    = Pattern.compile("(?im)^\\s*Input\\s*:?\\s*(.*)$");
    private static final Pattern OUTPUT   = Pattern.compile("(?im)^\\s*Output\\s*:?\\s*(.*)$");
    private static final Pattern DIFF     = Pattern.compile("(?i)\\b(Easy|Medium|Hard)\\b");
    private static final Pattern NUM_TITLE = Pattern.compile("^\\s*(\\d+)\\s*[.)]\\s*(.+?)\\s*$");
    /** A top-level ", name =" boundary between arguments. */
    private static final Pattern ARG_SPLIT = Pattern.compile(",\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*=");

    public static Map<String,Object> parsePaste(String text) {
        Map<String,Object> out = new LinkedHashMap<>();
        if (text == null) text = "";
        String[] lines = text.split("\\R");

        // --- title: first substantial line, with any leading "123." stripped ---
        String title = "";
        for (String raw : lines) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.matches("(?i)^(easy|medium|hard)$")) continue;
            Matcher m = NUM_TITLE.matcher(l);
            title = m.matches() ? m.group(2) : l;
            break;
        }
        title = title.replaceAll("^#+\\s*", "").trim();
        out.put("title", title);
        out.put("slug", slugify(title));

        Matcher d = DIFF.matcher(text);
        out.put("difficulty", d.find()
            ? d.group(1).substring(0, 1).toUpperCase(Locale.ROOT) + d.group(1).substring(1).toLowerCase(Locale.ROOT)
            : "");

        // --- statement: everything before the first Example block ---
        Matcher ex = EXAMPLE.matcher(text);
        out.put("statement", ex.find() ? text.substring(0, ex.start()).trim() : text.trim());

        // --- testcases: pair up Input:/Output: in document order ---
        List<Object> cases = new ArrayList<>();
        List<String> inputs = allMatches(INPUT, text);
        List<String> outputs = allMatches(OUTPUT, text);
        int n = Math.min(inputs.size(), outputs.size());
        for (int i = 0; i < n; i++) {
            cases.add(Json.map(
                "input", String.join("\n", splitArgs(inputs.get(i))),
                "expected", cleanValue(outputs.get(i))));
        }
        out.put("testcases", cases);
        out.put("topicTags", new ArrayList<>());
        return out;
    }

    private static List<String> allMatches(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            String v = m.group(1).trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /**
     * "nums = [2,7,11,15], target = 9"  ->  ["[2,7,11,15]", "9"]
     *
     * Split points are commas followed by "identifier =" at bracket depth zero, so commas inside
     * the arrays themselves are left alone.
     */
    public static List<String> splitArgs(String inputLine) {
        String s = inputLine.trim();
        List<String> parts = new ArrayList<>();

        List<Integer> cuts = new ArrayList<>();
        Matcher m = ARG_SPLIT.matcher(s);
        while (m.find()) {
            if (depthAt(s, m.start()) == 0) cuts.add(m.start());
        }

        int from = 0;
        for (int cut : cuts) {
            parts.add(s.substring(from, cut).trim());
            from = cut + 1;
        }
        parts.add(s.substring(from).trim());

        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String v = stripName(p);
            if (!v.isEmpty()) out.add(v);
        }
        return out.isEmpty() ? List.of(s) : out;
    }

    private static int depthAt(String s, int idx) {
        int depth = 0;
        boolean inStr = false;
        char q = 0;
        for (int i = 0; i < idx && i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == q) inStr = false;
                continue;
            }
            if (c == '"' || c == '\'') { inStr = true; q = c; }
            else if (c == '[' || c == '{' || c == '(') depth++;
            else if (c == ']' || c == '}' || c == ')') depth--;
        }
        return depth;
    }

    /** "nums = [1,2]" -> "[1,2]" */
    private static String stripName(String part) {
        Matcher m = Pattern.compile("^\\s*[A-Za-z_$][A-Za-z0-9_$]*\\s*=\\s*(.*)$", Pattern.DOTALL).matcher(part);
        return cleanValue(m.matches() ? m.group(1) : part);
    }

    private static String cleanValue(String v) {
        String s = v.trim();
        // Drop trailing prose LeetCode sometimes appends after the value.
        int expl = s.indexOf("  //");
        if (expl > 0) s = s.substring(0, expl).trim();
        return s.replaceAll("[,;]\\s*$", "").trim();
    }

    /**
     * Flatten LeetCode's statement HTML to text so the Example blocks can be parsed out of it.
     * The extension sends the statement as HTML (it renders better), but the testcases live
     * inside that markup as "<strong>Input:</strong> nums = [2,7,11,15]".
     */
    public static String htmlToText(String html) {
        if (html == null) return "";
        String s = html;
        s = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|li|pre|tr|h[1-6])\\s*>", "\n");
        s = s.replaceAll("(?i)<li[^>]*>", "\n- ");
        s = s.replaceAll("<[^>]+>", "");
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        s = s.replaceAll("[ \\t]+", " ");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    /** Pull just the Example blocks out of a statement, for imports that carry no testcases. */
    public static List<Object> testcasesFrom(String statementHtml) {
        String text = statementHtml == null ? "" : statementHtml;
        if (text.contains("<")) text = htmlToText(text);
        return Json.arr(Json.obj(parsePaste(text)).get("testcases"));
    }

    /**
     * Combine LeetCode's authoritative example inputs with the expected outputs scraped from the
     * statement.
     *
     * {@code exampleTestcaseList} is what the judge itself feeds the examples -- already one
     * argument per line, and immune to statement formatting -- but it carries inputs only. The
     * outputs only exist in the prose.
     *
     * <p>Pairing is done by matching the inputs, NOT by position. The two lists usually agree, but
     * when they do not -- LeetCode lists an example the statement does not spell out, or a
     * malformed Example block is skipped -- index pairing shifts every later case onto the wrong
     * answer. A blank expected field costs you ten seconds; a confidently wrong one costs you an
     * afternoon chasing a failure that was never real.
     */
    public static List<Object> mergeCases(List<Object> inputs, List<Object> htmlCases) {
        Map<String,String> byInput = new LinkedHashMap<>();
        for (Object c : htmlCases) {
            byInput.putIfAbsent(normalizeInput(Json.str(c, "input", "")),
                                Json.str(c, "expected", ""));
        }

        List<Object> out = new ArrayList<>();
        int matched = 0;
        for (Object in : inputs) {
            String input = String.valueOf(in).trim();
            String expected = byInput.get(normalizeInput(input));
            if (expected != null) matched++;
            out.add(Json.map("input", input, "expected", expected == null ? "" : expected));
        }

        // Nothing matched but the counts line up: the same cases written differently (spacing,
        // quoting). Position is then the best evidence available.
        if (matched == 0 && !htmlCases.isEmpty() && inputs.size() == htmlCases.size()) {
            out.clear();
            for (int i = 0; i < inputs.size(); i++) {
                out.add(Json.map("input", String.valueOf(inputs.get(i)).trim(),
                                 "expected", Json.str(htmlCases.get(i), "expected", "")));
            }
        }
        return out;
    }

    /** Compare inputs ignoring whitespace, which differs freely between the two sources. */
    private static String normalizeInput(String s) {
        StringBuilder b = new StringBuilder();
        for (String line : s.split("\\R")) b.append(line.replaceAll("\\s+", "")).append('\n');
        return b.toString();
    }

    /**
     * Read LeetCode's {@code metaData} -- the real signature, e.g.
     * {@code {"name":"twoSum","params":[{"name":"nums","type":"integer[]"}],"return":{...}}}.
     * Arrives as a JSON string nested inside the GraphQL response, so it needs its own parse.
     */
    public static Map<String,Object> parseMeta(String metaJson) {
        Map<String,Object> out = new LinkedHashMap<>();
        if (metaJson == null || metaJson.isBlank()) return out;
        Object meta = Json.parseOrNull(metaJson);
        if (meta == null) return out;

        String name = Json.str(meta, "name", "");
        if (!name.isBlank()) out.put("entry", name);

        List<Object> names = new ArrayList<>(), types = new ArrayList<>();
        for (Object p : Json.arr(Json.obj(meta).get("params"))) {
            names.add(Json.str(p, "name", ""));
            types.add(Json.str(p, "type", ""));
        }
        if (!names.isEmpty()) {
            out.put("paramNames", names);
            out.put("paramTypes", types);
        }
        Object ret = Json.obj(meta).get("return");
        if (ret != null) out.put("returnType", Json.str(ret, "type", ""));
        return out;
    }

    public static String slugify(String title) {
        String s = title == null ? "" : title.toLowerCase(Locale.ROOT);
        s = s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "untitled" : s;
    }

    /** Fallback starter when a problem arrives without LeetCode's Java snippet. */
    public static String defaultSnippet() {
        return "class Solution {\n"
             + "    public int solve(int[] nums) {\n"
             + "        \n"
             + "        return 0;\n"
             + "    }\n"
             + "}\n";
    }

    private Importer() {}
}
