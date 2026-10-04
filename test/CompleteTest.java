import lcforge.Json;
import lcforge.complete.Completer;

import java.util.*;

/**
 * Completion is mostly type resolution, so that is what these check: given a buffer and a cursor,
 * does the receiver resolve to the right thing, and do the members that come back belong to it.
 *
 * The cursor is written as `|` in each snippet and stripped before the call.
 */
public class CompleteTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {

        // ---------- the basics ----------

        Map<String,Object> r = at("""
            class Solution {
                public int solve(int[] nums) {
                    List<Integer> out = new ArrayList<>();
                    out.|
                }
            }""");
        eq("list: kind", r.get("kind"), "members");
        eq("list: receiver", r.get("receiver"), "List<Integer>");
        has("list: add", r, "add");
        has("list: size", r, "size");
        // A static method is not callable on an instance, so it must not be offered on one.
        hasNot("list: no static of()", r, "of");

        // The element type has to survive erasure, or the next dot in the chain is useless.
        eq("list: get returns the element type", detail(r, "get"), "(int) : Integer");

        // ---------- chaining through a generic return ----------

        r = at("""
            class Solution {
                void f() {
                    Map<String, List<Integer>> groups = new HashMap<>();
                    groups.get("a").|
                }
            }""");
        eq("map chain: receiver", r.get("receiver"), "List<Integer>");
        has("map chain: add", r, "add");

        r = at("""
            class Solution {
                void f() {
                    Map<String, List<Integer>> groups = new HashMap<>();
                    groups.get("a").get(0).|
                }
            }""");
        eq("deep chain: receiver", r.get("receiver"), "Integer");
        has("deep chain: intValue", r, "intValue");

        // ---------- arrays ----------

        eq("array: receiver", at("""
            class Solution {
                void f(int[] nums) {
                    nums.|
                }
            }""").get("receiver"), "int[]");
        has("array: length", at("""
            class Solution {
                void f(int[] nums) {
                    nums.|
                }
            }"""), "length");

        // Indexing peels exactly one dimension off.
        eq("matrix: row is still an array", at("""
            class Solution {
                void f(int[][] grid) {
                    grid[0].|
                }
            }""").get("receiver"), "int[]");

        // A collection of arrays: the element type is an array again.
        eq("deque of arrays", at("""
            class Solution {
                void f() {
                    Deque<int[]> dq = new ArrayDeque<>();
                    dq.peek().|
                }
            }""").get("receiver"), "int[]");

        // ---------- static receivers ----------

        r = at("""
            class Solution {
                void f() {
                    Math.|
                }
            }""");
        eq("Math: static receiver", r.get("receiver"), "Math (static)");
        has("Math: max", r, "max");

        r = at("""
            class Solution {
                void f() {
                    Integer.|
                }
            }""");
        has("Integer: parseInt", r, "parseInt");
        has("Integer: MAX_VALUE", r, "MAX_VALUE");
        hasNot("Integer: no instance intValue", r, "intValue");

        // ---------- chained builder ----------

        r = at("""
            class Solution {
                void f() {
                    StringBuilder sb = new StringBuilder();
                    sb.append('x').|
                }
            }""");
        eq("builder: append returns the builder", r.get("receiver"), "StringBuilder");
        has("builder: reverse", r, "reverse");

        // `var` says nothing on its own, so the initialiser has to be read.
        eq("var: inferred from the initialiser", at("""
            class Solution {
                void f() {
                    var seen = new HashSet<String>();
                    seen.|
                }
            }""").get("receiver"), "HashSet<String>");

        // ---------- the two classes that are not on our classpath ----------

        r = at("""
            class Solution {
                public ListNode reverse(ListNode head) {
                    head.|
                }
            }""");
        eq("ListNode: receiver", r.get("receiver"), "ListNode");
        has("ListNode: val", r, "val");
        has("ListNode: next", r, "next");

        eq("ListNode: next is another node", at("""
            class Solution {
                public ListNode reverse(ListNode head) {
                    head.next.|
                }
            }""").get("receiver"), "ListNode");

        r = at("""
            class Solution {
                public int depth(TreeNode root) {
                    root.left.|
                }
            }""");
        eq("TreeNode: receiver", r.get("receiver"), "TreeNode");
        has("TreeNode: right", r, "right");

        // ---------- classes declared in the buffer ----------

        String trie = """
            class Trie {
                Trie[] kids = new Trie[26];
                boolean word;
                public void insert(String w) { }
                public boolean search(String w) { return false; }
            }
            class Solution {
                void f() {
                    Trie t = new Trie();
                    t.|
                }
            }""";
        r = at(trie);
        eq("source class: receiver", r.get("receiver"), "Trie");
        has("source class: method", r, "insert");
        has("source class: field", r, "word");
        has("source class: array field", r, "kids");

        // Its own members resolve too, so the chain keeps going.
        eq("source class: field type", at(trie.replace("t.|", "t.kids[0].|")).get("receiver"), "Trie");

        // `this` means the class the cursor is sitting in.
        r = at("""
            class Solution {
                int count;
                void helper() { }
                void f() {
                    this.|
                }
            }""");
        eq("this: receiver", r.get("receiver"), "Solution");
        has("this: field", r, "count");
        has("this: method", r, "helper");

        // ---------- knowing when to say nothing ----------

        eq("unknown receiver offers nothing", at("""
            class Solution {
                void f() {
                    mystery.|
                }
            }""").get("kind"), "none");

        eq("no suggestions inside a comment", at("""
            class Solution {
                // nums.|
            }""").get("kind"), "suppress");

        eq("no suggestions inside a string", at("""
            class Solution {
                String s = "nums.|";
            }""").get("kind"), "suppress");

        // A dot inside a comment must not be mistaken for a real one on the line below.
        r = at("""
            class Solution {
                void f() {
                    // careful: sb.append
                    List<Integer> out = new ArrayList<>();
                    out.|
                }
            }""");
        eq("comment does not confuse the scan", r.get("receiver"), "List<Integer>");

        // ---------- no dot: locals and the enclosing class ----------

        r = at("""
            class Solution {
                int best;
                public int solve(int[] nums, int target) {
                    Map<String, Integer> seen = new HashMap<>();
                    ta|
                }
            }""");
        eq("globals: kind", r.get("kind"), "globals");
        eq("globals: parameter carries its type", detail(r, "target"), "int");
        eq("globals: array parameter", detail(r, "nums"), "int[]");
        eq("globals: local carries its generics", detail(r, "seen"), "Map<String,Integer>");
        has("globals: field of the enclosing class", r, "best");
        has("globals: known type name", r, "PriorityQueue");
        eq("globals: type names carry their import",
           String.valueOf(find(r, "PriorityQueue").get("importName")), "java.util.PriorityQueue");
        eq("globals: java.lang needs no import", find(r, "String").get("importName"), null);

        // ---------- snippets ----------

        eq("insert: placeholders come from the parameter types",
           String.valueOf(find(at("""
               class Solution {
                   void f() {
                       Math.|
                   }
               }"""), "abs").get("insert")).startsWith("abs(${1:"), true);
        eq("insert: no-arg methods get empty parens",
           find(at("""
               class Solution {
                   void f() {
                       List<Integer> out = new ArrayList<>();
                       out.|
                   }
               }"""), "clear").get("insert"), "clear()");

        // ---------- it must never throw ----------

        for (String junk : new String[]{"", ".", "class", "class A { void f() { a.b.c.|", "((((.|",
                                        "String s = \"unterminated", "/* unterminated", "a[.|"}) {
            try {
                Completer.complete(junk.replace("|", ""), Math.max(0, junk.indexOf('|')));
                passed++;
            } catch (Exception e) {
                failed++;
                System.out.println("  FAIL threw on junk input: " + junk + " -> " + e);
            }
        }

        System.out.println(failed == 0
            ? "Completer: all " + passed + " checks passed"
            : "Completer: " + passed + " passed, " + failed + " FAILED");
        if (failed > 0) System.exit(1);
    }

    // ---------- harness ----------

    static Map<String,Object> at(String marked) {
        int cursor = marked.indexOf('|');
        String code = marked.replace("|", "");
        return Completer.complete(code, cursor);
    }

    @SuppressWarnings("unchecked")
    static List<Object> items(Map<String,Object> r) {
        return (List<Object>) r.getOrDefault("items", List.of());
    }

    static Map<String,Object> find(Map<String,Object> r, String label) {
        for (Object o : items(r)) {
            Map<String,Object> m = Json.obj(o);
            if (label.equals(m.get("label"))) return m;
        }
        return Map.of();
    }

    static String detail(Map<String,Object> r, String label) {
        return String.valueOf(find(r, label).get("detail"));
    }

    static void has(String name, Map<String,Object> r, String label) {
        if (!find(r, label).isEmpty()) { passed++; return; }
        failed++;
        System.out.println("  FAIL " + name + "\n     " + label + " missing from "
            + items(r).size() + " items (receiver " + r.get("receiver") + ")");
    }

    static void hasNot(String name, Map<String,Object> r, String label) {
        if (find(r, label).isEmpty()) { passed++; return; }
        failed++;
        System.out.println("  FAIL " + name + "\n     " + label + " should not be offered here");
    }

    static void eq(String name, Object got, Object want) {
        if (Objects.equals(got, want)) { passed++; return; }
        failed++;
        System.out.println("  FAIL " + name + "\n     got:  " + got + "\n     want: " + want);
    }
}
