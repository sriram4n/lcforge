import lcforge.judge.TypeCodec;

import java.lang.reflect.Method;
import java.util.*;

/** Round-trip tests for the type layer. Run via verify.ps1 / verify.sh. */
public class CodecTest {

    static int passed = 0, failed = 0;

    // Reference signatures: decoding is driven by real generic types, so we read them off
    // these methods exactly the way the judge reads them off your Solution.
    @SuppressWarnings("unused")
    static class Sigs {
        public void ints(int a, long b, double c, boolean d, char e, String f) {}
        public void arrays(int[] a, int[][] b, char[] c, char[][] d, String[] e, double[] f) {}
        public void lists(List<Integer> a, List<String> b, List<List<Integer>> c) {}
        public void nodes(ListNode a, TreeNode b) {}
    }

    public static void main(String[] args) throws Exception {
        // --- scalars ---
        Method ints = Sigs.class.getMethod("ints", int.class, long.class, double.class,
                                           boolean.class, char.class, String.class);
        eq("int",     TypeCodec.decode("42", p(ints, 0)), 42);
        eq("int neg", TypeCodec.decode("-7", p(ints, 0)), -7);
        eq("long",    TypeCodec.decode("9007199254740993", p(ints, 1)), 9007199254740993L);
        eq("double",  TypeCodec.decode("2.5", p(ints, 2)), 2.5);
        eq("bool",    TypeCodec.decode("true", p(ints, 3)), true);
        eq("char",    TypeCodec.decode("\"x\"", p(ints, 4)), 'x');
        eq("string",  TypeCodec.decode("\"hello\"", p(ints, 5)), "hello");
        eq("string bare", TypeCodec.decode("hello", p(ints, 5)), "hello");
        eq("named arg stripped", TypeCodec.decode("target = 9", p(ints, 0)), 9);

        // --- arrays ---
        Method arr = Sigs.class.getMethod("arrays", int[].class, int[][].class, char[].class,
                                          char[][].class, String[].class, double[].class);
        eq("int[]",    TypeCodec.encode(TypeCodec.decode("[2,7,11,15]", p(arr, 0))), "[2,7,11,15]");
        eq("int[] empty", TypeCodec.encode(TypeCodec.decode("[]", p(arr, 0))), "[]");
        eq("int[][]",  TypeCodec.encode(TypeCodec.decode("[[1,2],[3,4]]", p(arr, 1))), "[[1,2],[3,4]]");
        eq("char[]",   TypeCodec.encode(TypeCodec.decode("[\"a\",\"b\"]", p(arr, 2))), "[\"a\",\"b\"]");
        eq("char[] from string", TypeCodec.encode(TypeCodec.decode("\"ab\"", p(arr, 2))), "[\"a\",\"b\"]");
        eq("char[][]", TypeCodec.encode(TypeCodec.decode("[[\"a\"],[\"b\"]]", p(arr, 3))), "[[\"a\"],[\"b\"]]");
        eq("String[]", TypeCodec.encode(TypeCodec.decode("[\"ab\",\"cd\"]", p(arr, 4))), "[\"ab\",\"cd\"]");

        // --- generics survive erasure via getGenericParameterTypes ---
        Method lists = Sigs.class.getMethod("lists", List.class, List.class, List.class);
        Object li = TypeCodec.decode("[1,2,3]", p(lists, 0));
        eq("List<Integer> elem type", ((List<?>) li).get(0).getClass(), Integer.class);
        eq("List<Integer>", TypeCodec.encode(li), "[1,2,3]");
        eq("List<String>",  TypeCodec.encode(TypeCodec.decode("[\"a\"]", p(lists, 1))), "[\"a\"]");
        Object nested = TypeCodec.decode("[[1,2],[3]]", p(lists, 2));
        eq("List<List<Integer>> inner type",
           ((List<?>) ((List<?>) nested).get(0)).get(0).getClass(), Integer.class);
        eq("List<List<Integer>>", TypeCodec.encode(nested), "[[1,2],[3]]");

        // --- nodes ---
        Method nodes = Sigs.class.getMethod("nodes", ListNode.class, TreeNode.class);
        ListNode head = (ListNode) TypeCodec.decode("[1,2,4]", p(nodes, 0));
        eq("ListNode head", head.val, 1);
        eq("ListNode second", head.next.val, 2);
        eq("ListNode tail", head.next.next.next, null);
        eq("ListNode round-trip", TypeCodec.encode(head), "[1,2,4]");
        eq("ListNode empty", TypeCodec.decode("[]", p(nodes, 0)), null);

        // The classic trap: nulls do NOT reserve slots for their would-be children.
        TreeNode t = (TreeNode) TypeCodec.decode("[3,9,20,null,null,15,7]", p(nodes, 1));
        eq("TreeNode root", t.val, 3);
        eq("TreeNode left", t.left.val, 9);
        eq("TreeNode left has no kids", t.left.left, null);
        eq("TreeNode right", t.right.val, 20);
        eq("TreeNode right.left", t.right.left.val, 15);
        eq("TreeNode right.right", t.right.right.val, 7);
        eq("TreeNode round-trip", TypeCodec.encode(t), "[3,9,20,null,null,15,7]");

        TreeNode skew = (TreeNode) TypeCodec.decode("[1,null,2,null,3]", p(nodes, 1));
        eq("skewed tree", skew.right.right.val, 3);
        eq("skewed round-trip", TypeCodec.encode(skew), "[1,null,2,null,3]");
        eq("TreeNode empty", TypeCodec.decode("[]", p(nodes, 1)), null);
        eq("TreeNode [null]", TypeCodec.decode("[null]", p(nodes, 1)), null);

        // --- encoding details LeetCode cares about ---
        eq("double format", TypeCodec.encode(2.0), "2.00000");
        eq("double format frac", TypeCodec.encode(2.5), "2.50000");
        eq("bool encode", TypeCodec.encode(true), "true");
        eq("null encode", TypeCodec.encode(null), "null");

        System.out.println();
        System.out.println(failed == 0
            ? "TypeCodec: all " + passed + " checks passed"
            : "TypeCodec: " + passed + " passed, " + failed + " FAILED");
        if (failed > 0) System.exit(1);
    }

    static java.lang.reflect.Type p(Method m, int i) { return m.getGenericParameterTypes()[i]; }

    static void eq(String name, Object got, Object want) {
        boolean ok = (got == null) ? want == null : deepEq(got, want);
        if (ok) { passed++; }
        else {
            failed++;
            System.out.println("  FAIL " + name + "\n     got:  " + show(got) + "\n     want: " + show(want));
        }
    }

    static boolean deepEq(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return Objects.deepEquals(a, b);
    }

    static String show(Object o) {
        if (o == null) return "null";
        if (o.getClass().isArray()) return Arrays.deepToString(new Object[]{o});
        return o + "  (" + o.getClass().getSimpleName() + ")";
    }
}
