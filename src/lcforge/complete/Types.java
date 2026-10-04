package lcforge.complete;

import java.util.*;

/**
 * The classes the completer knows about.
 *
 * Curated rather than scanned off the classpath, for two reasons: the suggestion list stays signal
 * instead of the JDK's entire surface, and every name here can carry the import line it needs. If
 * something you actually use is missing, adding it is one line.
 */
public final class Types {

    private static final Map<String, Class<?>> BY_NAME = new LinkedHashMap<>();

    static {
        // primitives — so `int[]` resolves to an array of something rather than nothing
        for (Class<?> c : new Class<?>[]{
                int.class, long.class, double.class, float.class, boolean.class,
                char.class, byte.class, short.class, void.class })
            BY_NAME.put(c.getName(), c);

        add(String.class, Integer.class, Long.class, Double.class, Float.class, Character.class,
            Boolean.class, Byte.class, Short.class, Math.class, StringBuilder.class, Object.class,
            Number.class, CharSequence.class, Comparable.class, Iterable.class, System.class,
            Exception.class, RuntimeException.class, IllegalArgumentException.class);

        add(List.class, ArrayList.class, LinkedList.class, Map.class, HashMap.class, TreeMap.class,
            LinkedHashMap.class, Set.class, HashSet.class, TreeSet.class, LinkedHashSet.class,
            Queue.class, Deque.class, ArrayDeque.class, PriorityQueue.class, Stack.class,
            Arrays.class, Collections.class, Objects.class, Optional.class, Iterator.class,
            Comparator.class, Random.class, StringJoiner.class, BitSet.class, Collection.class,
            NavigableMap.class, NavigableSet.class, SortedMap.class, SortedSet.class);

        add(java.util.stream.Stream.class, java.util.stream.IntStream.class,
            java.util.stream.Collectors.class, java.util.function.Function.class,
            java.util.function.BiFunction.class, java.util.function.Predicate.class,
            java.util.function.Supplier.class, java.util.function.Consumer.class);

        // Nested, so it needs its qualified spelling as well as its simple one.
        BY_NAME.put("Entry", Map.Entry.class);
        BY_NAME.put("Map.Entry", Map.Entry.class);
    }

    private static void add(Class<?>... cs) {
        for (Class<?> c : cs) BY_NAME.put(c.getSimpleName(), c);
    }

    public static Class<?> lookup(String simpleName) {
        return simpleName == null ? null : BY_NAME.get(simpleName.trim());
    }

    public static Map<String, Class<?>> all() { return Collections.unmodifiableMap(BY_NAME); }

    /**
     * The import this class needs, or null when it needs none. Fed to the editor so that picking
     * {@code PriorityQueue} out of the list also adds {@code import java.util.PriorityQueue;} —
     * LeetCode's own editor hides that from you, and pasted code that compiles there would
     * otherwise fail here.
     */
    public static String importFor(Class<?> c) {
        if (c == null || c.isPrimitive() || c.isArray()) return null;
        String pkg = c.getPackageName();
        if (pkg.isEmpty() || pkg.equals("java.lang")) return null;
        return c.getCanonicalName();
    }

    // ---------- the two classes we cannot reflect on ----------

    /**
     * ListNode and TreeNode live in the default package under runtime/, compiled into whichever
     * workspace you last built — not onto this server's classpath. Loading them dynamically would
     * mean a compile step before you can get a suggestion. They are four fields that have never
     * changed, so they are written out instead.
     */
    private static final Map<String, Map<String,String>> SYNTHETIC = Map.of(
        "ListNode", new LinkedHashMap<>(Map.of("val", "int", "next", "ListNode")),
        "TreeNode", new LinkedHashMap<>(Map.of("val", "int", "left", "TreeNode", "right", "TreeNode")));

    public static boolean isSynthetic(String name) { return SYNTHETIC.containsKey(name); }

    /** Field name to type name, in a stable order. */
    public static Map<String,String> syntheticFields(String name) {
        Map<String,String> f = SYNTHETIC.get(name);
        if (f == null) return Map.of();
        // Map.of does not preserve order, so impose one the reader expects.
        Map<String,String> out = new LinkedHashMap<>();
        for (String k : name.equals("ListNode") ? new String[]{"val", "next"}
                                                : new String[]{"val", "left", "right"})
            out.put(k, f.get(k));
        return out;
    }

    private Types() {}
}
