import lcforge.judge.Compare;
import lcforge.judge.Compile;
import lcforge.judge.Invoke;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * The golden set: eight problems chosen to cover the risky corners of the type layer.
 *
 * Each runs twice -- once with a correct solution (must pass) and once with a realistic bug
 * (must fail). Asserting the failure matters as much as asserting the pass: a judge that accepts
 * everything would sail through a pass-only suite.
 */
public class GoldenTest {

    static int passed = 0, failed = 0;
    static Path tmpRoot, runtimeDir;

    record Case(String name, String entry, String input, String expected, String mode,
                String good, String bad) {}

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        runtimeDir = root.resolve("runtime");
        tmpRoot = root.resolve("build/golden");
        deleteTree(tmpRoot);
        Files.createDirectories(tmpRoot);

        for (Case c : cases()) {
            run(c, true);
            run(c, false);
        }

        System.out.println();
        System.out.println(failed == 0
            ? "Golden set: all " + passed + " checks passed"
            : "Golden set: " + passed + " passed, " + failed + " FAILED");
        if (failed > 0) System.exit(1);
    }

    /** Compile one variant, invoke it, and check the verdict is the one we expect. */
    static void run(Case c, boolean good) throws Exception {
        String label = c.name() + (good ? " (correct)" : " (buggy)");
        Path ws = tmpRoot.resolve(c.name().replaceAll("[^a-zA-Z0-9]", "-") + (good ? "-ok" : "-bad"));
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("Solution.java"), good ? c.good() : c.bad(), StandardCharsets.UTF_8);

        Path classes = ws.resolve("classes");
        Compile.Result cr = Compile.compile(ws, runtimeDir, classes);
        if (!cr.ok) {
            fail(label, "did not compile:\n" + cr.errorText());
            return;
        }

        try (URLClassLoader cl = new URLClassLoader(
                new URL[]{ classes.toUri().toURL() }, GoldenTest.class.getClassLoader())) {

            Class<?> cls = cl.loadClass("Solution");
            String[] lines = c.input().split("\\R");
            Invoke.Result r = Invoke.run(cls, c.entry(), lines, true);

            if (!r.ok()) {
                if (good) fail(label, "threw: " + r.error);
                else ok(label + " -> rejected (" + r.error + ")");
                return;
            }

            Compare.Outcome o = Compare.check(r.output, c.expected(), c.mode());
            boolean accepted = o.passed();

            if (good && accepted) {
                ok(label + " -> " + r.output);
            } else if (!good && !accepted) {
                ok(label + " -> correctly rejected (" + r.output + ")");
            } else if (good) {
                fail(label, "expected " + c.expected() + " but got " + r.output);
            } else {
                fail(label, "the buggy version was ACCEPTED (" + r.output + ") -- the judge is too lenient");
            }
        }
    }

    static List<Case> cases() {
        List<Case> out = new ArrayList<>();

        // int[] + int -> int[]
        out.add(new Case("two-sum", "twoSum", "[2,7,11,15]\n9", "[0,1]", Compare.MODE_EXACT,
            """
            import java.util.*;
            class Solution {
                public int[] twoSum(int[] nums, int target) {
                    Map<Integer,Integer> seen = new HashMap<>();
                    for (int i = 0; i < nums.length; i++) {
                        if (seen.containsKey(target - nums[i])) return new int[]{seen.get(target - nums[i]), i};
                        seen.put(nums[i], i);
                    }
                    return new int[]{};
                }
            }
            """,
            // classic 1-indexing slip
            """
            import java.util.*;
            class Solution {
                public int[] twoSum(int[] nums, int target) {
                    for (int i = 0; i < nums.length; i++)
                        for (int j = i + 1; j < nums.length; j++)
                            if (nums[i] + nums[j] == target) return new int[]{i + 1, j + 1};
                    return new int[]{};
                }
            }
            """));

        // ListNode in and out, with a carry that runs off the end
        out.add(new Case("add-two-numbers", "addTwoNumbers", "[9,9]\n[1]", "[0,0,1]", Compare.MODE_EXACT,
            """
            class Solution {
                public ListNode addTwoNumbers(ListNode l1, ListNode l2) {
                    ListNode head = new ListNode(0), cur = head;
                    int carry = 0;
                    while (l1 != null || l2 != null || carry != 0) {
                        int sum = carry;
                        if (l1 != null) { sum += l1.val; l1 = l1.next; }
                        if (l2 != null) { sum += l2.val; l2 = l2.next; }
                        carry = sum / 10;
                        cur.next = new ListNode(sum % 10);
                        cur = cur.next;
                    }
                    return head.next;
                }
            }
            """,
            // forgets the final carry
            """
            class Solution {
                public ListNode addTwoNumbers(ListNode l1, ListNode l2) {
                    ListNode head = new ListNode(0), cur = head;
                    int carry = 0;
                    while (l1 != null || l2 != null) {
                        int sum = carry;
                        if (l1 != null) { sum += l1.val; l1 = l1.next; }
                        if (l2 != null) { sum += l2.val; l2 = l2.next; }
                        carry = sum / 10;
                        cur.next = new ListNode(sum % 10);
                        cur = cur.next;
                    }
                    return head.next;
                }
            }
            """));

        // TreeNode parsing with null holes
        out.add(new Case("max-depth", "maxDepth", "[3,9,20,null,null,15,7]", "3", Compare.MODE_EXACT,
            """
            class Solution {
                public int maxDepth(TreeNode root) {
                    if (root == null) return 0;
                    return 1 + Math.max(maxDepth(root.left), maxDepth(root.right));
                }
            }
            """,
            // forgets to count the current level
            """
            class Solution {
                public int maxDepth(TreeNode root) {
                    if (root == null) return 0;
                    return Math.max(maxDepth(root.left), maxDepth(root.right));
                }
            }
            """));

        // void return: the answer is the mutated argument
        out.add(new Case("rotate-array", "rotate", "[1,2,3,4,5,6,7]\n3", "[5,6,7,1,2,3,4]", Compare.MODE_EXACT,
            """
            class Solution {
                public void rotate(int[] nums, int k) {
                    k %= nums.length;
                    reverse(nums, 0, nums.length - 1);
                    reverse(nums, 0, k - 1);
                    reverse(nums, k, nums.length - 1);
                }
                private void reverse(int[] a, int i, int j) {
                    while (i < j) { int t = a[i]; a[i++] = a[j]; a[j--] = t; }
                }
            }
            """,
            // rotates the wrong way
            """
            class Solution {
                public void rotate(int[] nums, int k) {
                    int n = nums.length;
                    k %= n;
                    int[] copy = nums.clone();
                    for (int i = 0; i < n; i++) nums[i] = copy[(i + k) % n];
                }
            }
            """));

        // List<List<String>> with order-insensitive comparison
        out.add(new Case("group-anagrams", "groupAnagrams",
            "[\"eat\",\"tea\",\"tan\",\"ate\",\"nat\",\"bat\"]",
            "[[\"bat\"],[\"nat\",\"tan\"],[\"ate\",\"eat\",\"tea\"]]", Compare.MODE_UNORDERED,
            """
            import java.util.*;
            class Solution {
                public List<List<String>> groupAnagrams(String[] strs) {
                    Map<String,List<String>> m = new HashMap<>();
                    for (String s : strs) {
                        char[] c = s.toCharArray();
                        Arrays.sort(c);
                        m.computeIfAbsent(new String(c), k -> new ArrayList<>()).add(s);
                    }
                    return new ArrayList<>(m.values());
                }
            }
            """,
            // groups by length instead of by letters
            """
            import java.util.*;
            class Solution {
                public List<List<String>> groupAnagrams(String[] strs) {
                    Map<Integer,List<String>> m = new HashMap<>();
                    for (String s : strs) m.computeIfAbsent(s.length(), k -> new ArrayList<>()).add(s);
                    return new ArrayList<>(m.values());
                }
            }
            """));

        // double return with float tolerance
        out.add(new Case("median-two-sorted", "findMedianSortedArrays", "[1,2]\n[3,4]", "2.50000", Compare.MODE_FLOAT,
            """
            import java.util.*;
            class Solution {
                public double findMedianSortedArrays(int[] a, int[] b) {
                    int[] all = new int[a.length + b.length];
                    System.arraycopy(a, 0, all, 0, a.length);
                    System.arraycopy(b, 0, all, a.length, b.length);
                    Arrays.sort(all);
                    int n = all.length;
                    return (n % 2 == 1) ? all[n / 2] : (all[n / 2 - 1] + all[n / 2]) / 2.0;
                }
            }
            """,
            // always takes the lower middle -- wrong for even lengths
            """
            import java.util.*;
            class Solution {
                public double findMedianSortedArrays(int[] a, int[] b) {
                    int[] all = new int[a.length + b.length];
                    System.arraycopy(a, 0, all, 0, a.length);
                    System.arraycopy(b, 0, all, a.length, b.length);
                    Arrays.sort(all);
                    return all[(all.length - 1) / 2];
                }
            }
            """));

        // int[][] in, List<Integer> out
        out.add(new Case("spiral-matrix", "spiralOrder", "[[1,2,3],[4,5,6],[7,8,9]]",
            "[1,2,3,6,9,8,7,4,5]", Compare.MODE_EXACT,
            """
            import java.util.*;
            class Solution {
                public List<Integer> spiralOrder(int[][] m) {
                    List<Integer> out = new ArrayList<>();
                    if (m.length == 0) return out;
                    int top = 0, bot = m.length - 1, left = 0, right = m[0].length - 1;
                    while (top <= bot && left <= right) {
                        for (int i = left; i <= right; i++) out.add(m[top][i]);
                        top++;
                        for (int i = top; i <= bot; i++) out.add(m[i][right]);
                        right--;
                        if (top <= bot) { for (int i = right; i >= left; i--) out.add(m[bot][i]); bot--; }
                        if (left <= right) { for (int i = bot; i >= top; i--) out.add(m[i][left]); left++; }
                    }
                    return out;
                }
            }
            """,
            // plain row-major traversal
            """
            import java.util.*;
            class Solution {
                public List<Integer> spiralOrder(int[][] m) {
                    List<Integer> out = new ArrayList<>();
                    for (int[] row : m) for (int v : row) out.add(v);
                    return out;
                }
            }
            """));

        // String in, String out
        out.add(new Case("longest-palindrome", "longestPalindrome", "\"babad\"", "\"bab\"", Compare.MODE_EXACT,
            """
            class Solution {
                public String longestPalindrome(String s) {
                    if (s.isEmpty()) return "";
                    int start = 0, len = 1;
                    for (int i = 0; i < s.length(); i++) {
                        for (int d = 0; d < 2; d++) {
                            int l = i, r = i + d;
                            while (l >= 0 && r < s.length() && s.charAt(l) == s.charAt(r)) { l--; r++; }
                            if (r - l - 1 > len) { len = r - l - 1; start = l + 1; }
                        }
                    }
                    return s.substring(start, start + len);
                }
            }
            """,
            // never expands past a single character
            """
            class Solution {
                public String longestPalindrome(String s) {
                    return s.isEmpty() ? "" : s.substring(0, 1);
                }
            }
            """));

        return out;
    }

    static void ok(String msg)   { passed++; System.out.println("  ok    " + msg); }
    static void fail(String m, String why) { failed++; System.out.println("  FAIL  " + m + " -- " + why); }

    static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> { try { Files.delete(f); } catch (Exception ignored) {} });
        }
    }
}
