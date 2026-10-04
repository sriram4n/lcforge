package lcforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Persistence: plain JSON files under data/, plus real .java files under workspace/.
 *
 * Files rather than a database so the whole thing stays inspectable and git-friendly, and so the
 * solutions you write are ordinary sources you can open in an IDE. The dataset here is a few
 * hundred problems at most -- keeping it in memory and rewriting on change is entirely adequate.
 */
public final class Store {

    private final Path root;
    private final Path dataDir;
    private final Path workspaceDir;
    private final Path problemsFile;
    private final Path attemptsFile;

    /** slug -> problem record */
    private final Map<String,Object> problems = new LinkedHashMap<>();
    private Map<String,Object> settings = new LinkedHashMap<>();

    /** Every run, trace and hunt, oldest first. Append-only; see appendAttempt. */
    private final List<Object> attempts = new ArrayList<>();

    public Store(Path root) throws IOException {
        this.root = root;
        this.dataDir = root.resolve("data");
        this.workspaceDir = root.resolve("workspace");
        this.problemsFile = dataDir.resolve("problems.json");
        this.attemptsFile = dataDir.resolve("attempts.jsonl");
        Files.createDirectories(dataDir);
        Files.createDirectories(workspaceDir);
        load();
    }

    public Path root() { return root; }
    public Path workspaceDir() { return workspaceDir; }
    public Path dataDir() { return dataDir; }

    // ---------- load / save ----------

    private synchronized void load() throws IOException {
        if (Files.exists(problemsFile)) {
            Object parsed = Json.parseOrNull(Files.readString(problemsFile, StandardCharsets.UTF_8));
            if (parsed != null) problems.putAll(Json.obj(parsed));
        }
        Path s = dataDir.resolve("settings.json");
        if (Files.exists(s)) {
            Object parsed = Json.parseOrNull(Files.readString(s, StandardCharsets.UTF_8));
            if (parsed != null) settings = Json.obj(parsed);
        }
        loadAttempts();
    }

    private synchronized void loadAttempts() throws IOException {
        if (!Files.exists(attemptsFile)) return;
        for (String line : Files.readAllLines(attemptsFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            Object rec = Json.parseOrNull(line);
            // A record that will not parse is the last line of a file we were killed mid-append.
            // Dropping one attempt is the correct price; refusing to start is not.
            if (rec != null) attempts.add(rec);
        }
    }

    private synchronized void saveProblems() throws IOException {
        writeAtomic(problemsFile, Json.write(problems));
    }

    public synchronized Map<String,Object> settings() {
        return settings;
    }

    public synchronized void saveSettings(Map<String,Object> s) throws IOException {
        this.settings = s;
        writeAtomic(dataDir.resolve("settings.json"), Json.write(s));
    }

    // ---------- attempts ----------

    /**
     * Append one event to data/attempts.jsonl.
     *
     * The only file here that is not rewritten whole, and deliberately so. A year of practice is
     * tens of thousands of records; rewriting all of them on every press of Run -- which is what an
     * array inside problems.json would mean, since doRun already calls putProblem -- would make the
     * Run button slower the longer you had been using the tool. Appending a line costs the same on
     * day one and day four hundred, and a crash mid-append damages one record rather than the lot.
     *
     * (If this ever passes ~50k lines, that is the moment to think about rotation. It is a year
     * away and an archiver written today would be a guess.)
     */
    public synchronized void appendAttempt(Map<String,Object> rec) throws IOException {
        attempts.add(rec);
        Files.writeString(attemptsFile, Json.write(rec) + "\n", StandardCharsets.UTF_8,
                          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Every attempt, oldest first. */
    public synchronized List<Object> attempts() {
        return List.copyOf(attempts);
    }

    public synchronized List<Object> attempts(String slug) {
        List<Object> out = new ArrayList<>();
        for (Object a : attempts) if (Json.str(a, "slug", "").equals(slug)) out.add(a);
        return out;
    }

    public synchronized List<Object> attemptsSince(long sinceMs) {
        List<Object> out = new ArrayList<>();
        for (Object a : attempts) if (Json.num(a, "t", 0) >= sinceMs) out.add(a);
        return out;
    }

    // ---------- small named documents ----------

    /**
     * The rest of data/: pet.json, and review.json and session.json when they arrive.
     *
     * Each is one small object rewritten whole, which is what writeAtomic is for -- and routing
     * them through here rather than handing out the directory keeps every file that makes up the
     * store described in one class, and keeps the atomic write non-optional.
     */
    public synchronized String readDoc(String name) throws IOException {
        Path p = dataDir.resolve(docName(name));
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
    }

    public synchronized void writeDoc(String name, String content) throws IOException {
        writeAtomic(dataDir.resolve(docName(name)), content);
    }

    private static String docName(String name) {
        String n = Paths.get(name).getFileName().toString();
        return n.endsWith(".json") ? n : n + ".json";
    }

    /** Write via a temp file + move so a crash mid-write cannot leave a truncated store. */
    private static void writeAtomic(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---------- problems ----------

    /**
     * Submissions on a problem, counting the runs from before Run and Submit were split.
     *
     * Every one of those judged every case -- a submission in all but name -- so a record with
     * `runs` and no `submits` has had exactly that many. Read through here and never directly, or an
     * old problem shows "15 accepted out of 2 submitted".
     */
    public static long submitsOf(Object p) {
        Map<String,Object> m = Json.obj(p);
        return m.containsKey("submits") ? Json.num(m, "submits", 0) : Json.num(m, "runs", 0);
    }

    public synchronized List<Object> listProblems() {
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String,Object> e : problems.entrySet()) {
            Map<String,Object> p = Json.obj(e.getValue());
            // The learning layer's fields are all projected here from the start, zero-valued until
            // their phase fills them, so that adding spaced repetition or a pattern grid later is
            // not another edit to this method -- and so the library view can never be the reason a
            // feature looks half-wired.
            out.add(Json.map(
                "slug", e.getKey(),
                "title", p.getOrDefault("title", e.getKey()),
                "difficulty", p.getOrDefault("difficulty", ""),
                "topicTags", p.getOrDefault("topicTags", List.of()),
                "updatedAt", p.getOrDefault("updatedAt", 0L),
                "lastVerdict", p.getOrDefault("lastVerdict", ""),
                "runs", p.getOrDefault("runs", 0L),
                "passes", p.getOrDefault("passes", 0L),
                "submits", submitsOf(p),
                "bestSolveMs", p.getOrDefault("bestSolveMs", 0L),
                "bestSteps", p.getOrDefault("bestSteps", 0L),
                "lastPassAt", p.getOrDefault("lastPassAt", 0L),
                "patterns", p.getOrDefault("patterns", List.of()),
                "due", p.getOrDefault("due", "")));
        }
        out.sort((a, b) -> Long.compare(
            Json.num(b, "updatedAt", 0), Json.num(a, "updatedAt", 0)));
        return out;
    }

    public synchronized Map<String,Object> problem(String slug) {
        Object p = problems.get(slug);
        return p == null ? null : Json.obj(p);
    }

    public synchronized void putProblem(String slug, Map<String,Object> p) throws IOException {
        p.put("updatedAt", System.currentTimeMillis());
        problems.put(slug, p);
        saveProblems();
    }

    public synchronized void deleteProblem(String slug) throws IOException {
        problems.remove(slug);
        saveProblems();
        // Solutions are deliberately left on disk -- losing work to a stray click would be worse
        // than an orphaned folder.
    }

    public synchronized boolean has(String slug) { return problems.containsKey(slug); }

    // ---------- workspace files ----------

    public Path workspace(String slug) {
        return workspaceDir.resolve(safeSlug(slug));
    }

    public Path classesDir(String slug) {
        return workspace(slug).resolve("classes");
    }

    public String readCode(String slug, String file) throws IOException {
        Path p = workspace(slug).resolve(safeFile(file));
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
    }

    public void writeCode(String slug, String file, String content) throws IOException {
        Path dir = workspace(slug);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(safeFile(file)), content, StandardCharsets.UTF_8);
    }

    /** Slugs and filenames come from the network; keep them inside the workspace. */
    public static String safeSlug(String s) {
        String cleaned = s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        cleaned = cleaned.replaceAll("-{2,}", "-").replaceAll("^[-.]+|[-.]+$", "");
        if (cleaned.isEmpty()) cleaned = "untitled";
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private static String safeFile(String f) {
        String name = Paths.get(f == null ? "Solution.java" : f).getFileName().toString();
        if (!name.endsWith(".java")) name = name + ".java";
        return name;
    }
}
