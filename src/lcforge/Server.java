package lcforge;

import com.sun.net.httpserver.*;
import lcforge.complete.Completer;
import lcforge.judge.Compare;
import lcforge.judge.Compile;
import lcforge.judge.RunnerPool;
import lcforge.learn.Learn;
import lcforge.pet.Ember;
import lcforge.trace.Tracer;

import java.io.*;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * The local web server: the interface, and the /api surface behind it. Nothing leaves this machine.
 *
 * Started by {@link App}, which decides where everything lives; this class only serves it.
 */
public final class Server {

    /** The one place the version is written. The About sheet, /api/health and the build all read it. */
    public static final String VERSION = "1.0.0";

    private static final long RUN_TIMEOUT_MS = 8_000;

    private final Layout layout;
    private final Path home;     // the data: data/ and workspace/ -- yours, kept apart from the code
    private final Store store;
    private final RunnerPool runner;
    private final Path buildDir;     // LCForge's own classes, for the runner and tracer JVMs
    private final Path runtimeDir;   // ListNode / TreeNode sources, compiled with every solution
    private final Learn learn;
    private final Ember ember;
    private final boolean bundledEditor;   // did this build carry its own copy of Monaco?
    private volatile int port;    // set by start(); the Host and Origin checks are written in terms of it

    public Server(Layout layout) throws IOException {
        this.layout = layout;
        this.home = layout.home();
        this.store = new Store(home);
        this.buildDir = layout.code();
        this.runtimeDir = layout.runtimeSrc();
        this.runner = new RunnerPool(buildDir);
        this.learn = new Learn(store);
        this.ember = new Ember(store);
        this.bundledEditor = layout.web().read("/vendor/monaco/vs/loader.js") != null;
    }

    /** A source checkout's layout: what the tests, run.ps1 and run.sh use. */
    public Server(Path root, Path home) throws IOException {
        this(Layout.dev(root, home));
    }

    /** Kept so anything that launched `lcforge.Server` directly still works. */
    public static void main(String[] args) throws Exception {
        App.main(args);
    }

    /**
     * Bind and serve. Throws BindException when the port is taken, and leaves the deciding -- is
     * that another LCForge, or something else? -- to the launcher, which knows how to ask.
     */
    public void start(int port) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.port = port;

        http.createContext("/", this::handleStatic);
        http.createContext("/api/", this::handleApi);
        http.setExecutor(Executors.newFixedThreadPool(4));
        http.start();

        Runtime.getRuntime().addShutdownHook(new Thread(runner::close));
    }

    /** Stop the runner JVM; the shutdown hook does this too, but Quit should not wait for exit. */
    public void close() {
        runner.close();
    }

    public int port() { return port; }
    public Path home() { return home; }

    // ---------- static files ----------

    private void handleStatic(HttpExchange ex) throws IOException {
        harden(ex);
        if (!isLoopbackHost(ex.getRequestHeaders().getFirst("Host"))) {
            send(ex, 403, "text/plain", "LCForge only answers to localhost".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) path = "/index.html";

        // Assets refuses anything that is not a plain path below its own root -- no traversal, from
        // a folder or from inside the jar.
        byte[] body = layout.web().read(path);
        if (body == null) {
            send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }

        ex.getResponseHeaders().add("Cache-Control", "no-store");
        if (path.endsWith(".html")) ex.getResponseHeaders().set("Content-Security-Policy", PAGE_POLICY);
        send(ex, 200, contentType(path), body);
    }

    /**
     * The page's Content-Security-Policy: the second wall behind the statement sanitizer.
     *
     * Problem statements are third-party HTML rendered into a page that can run code, so the
     * sanitizer is the first line and this is the one that holds if the sanitizer is ever wrong.
     * No inline script runs at all (boot.js is a file for exactly this reason), scripts load only
     * from here and the CDN Monaco ships from, the page can talk to no server but this one, and
     * images come only from where LeetCode keeps its diagrams.
     *
     * Styles keep 'unsafe-inline' because Monaco writes its theme into <style> elements at runtime;
     * the sanitizer strips every style attribute and style tag from statements, so that is not a door.
     */
    private static final String PAGE_POLICY = String.join("; ",
        "default-src 'self'",
        "script-src 'self' https://cdnjs.cloudflare.com",
        "style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com",
        "font-src 'self' data: https://cdnjs.cloudflare.com",
        "img-src 'self' data: https://assets.leetcode.com https://s3-lc-upload.s3.amazonaws.com",
        "connect-src 'self'",
        "worker-src 'self' blob:",
        "frame-src 'none'",
        "object-src 'none'",
        "base-uri 'none'",
        "form-action 'none'",
        "frame-ancestors 'none'");

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js"))   return "text/javascript; charset=utf-8";
        if (name.endsWith(".css"))  return "text/css; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".svg"))  return "image/svg+xml";
        if (name.endsWith(".png"))  return "image/png";
        if (name.endsWith(".ttf"))  return "font/ttf";
        if (name.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    // ---------- api ----------

    private void handleApi(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        harden(ex);

        String why = refusal(ex, path, method);
        if (why != null) {
            String origin = ex.getRequestHeaders().getFirst("Origin");
            System.err.println("  refused " + method + " " + path
                + (origin == null ? "" : " from " + origin) + " (" + why + ")");
            send(ex, 403, "application/json; charset=utf-8",
                 Json.write(Json.map("ok", false, "error", "refused: " + why)).getBytes(StandardCharsets.UTF_8));
            return;
        }

        // The extension's import is the one cross-origin call there is. Its worker holds host
        // permission for these ports, so Chrome does not hold it to CORS -- the grant is here for
        // any browser that still asks. Exactly that origin, exactly the paths refusal() lets it
        // reach, nothing else.
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && isExtensionOrigin(origin)) {
            Headers h = ex.getResponseHeaders();
            h.add("Access-Control-Allow-Origin", origin);
            h.add("Access-Control-Allow-Headers", "Content-Type");
            h.add("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
            h.add("Vary", "Origin");
        }
        if (method.equals("OPTIONS")) { send(ex, 204, "text/plain", new byte[0]); return; }

        try {
            Object body = null;
            if (method.equals("POST")) {
                String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                body = raw.isBlank() ? Json.map() : Json.parse(raw);
            }

            Object result;
            switch (path) {
                // "app" lets a second launch tell LCForge already running on the port apart from
                // something else squatting on it -- the first gets a browser tab, the second a new port.
                case "/api/health":     result = Json.map("ok", true, "app", "lcforge", "version", VERSION,
                                                          "editor", bundledEditor ? "bundled" : "cdn"); break;
                case "/api/about":      result = about(); break;
                case "/api/problems":   result = Json.map("ok", true, "problems", store.listProblems()); break;
                case "/api/problem":    result = getProblem(ex); break;
                case "/api/import":     result = doImport(body); break;
                case "/api/paste":      result = doPaste(body); break;
                case "/api/save":       result = doSave(body); break;
                case "/api/run":        result = doRun(body); break;
                case "/api/trace":      result = doTrace(body); break;
                case "/api/capture":    result = doCapture(body); break;
                case "/api/complete":  result = Completer.complete(Json.str(body, "code", ""), (int) Json.num(body, "offset", 0)); break;
                case "/api/stats":      result = learn.stats((int) Json.num(body, "days", 365)); break;
                case "/api/attempts":   result = getAttempts(ex); break;
                case "/api/pet":        result = ember.view(System.currentTimeMillis()); break;
                case "/api/pet/feed":   result = doFeed(body); break;
                case "/api/pet/buy":    result = doBuy(body); break;
                case "/api/pet/equip":  result = doEquip(body); break;
                case "/api/pet/name":   result = doName(body); break;
                case "/api/delete":     result = doDelete(body); break;
                case "/api/settings":   result = doSettings(method, body); break;
                default:
                    send(ex, 404, "application/json", Json.write(Json.map("ok", false, "error", "no such endpoint")).getBytes(StandardCharsets.UTF_8));
                    return;
            }
            send(ex, 200, "application/json; charset=utf-8", Json.write(result).getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            e.printStackTrace();
            byte[] msg = Json.write(Json.map("ok", false, "error", String.valueOf(e))).getBytes(StandardCharsets.UTF_8);
            send(ex, 500, "application/json; charset=utf-8", msg);
        }
    }

    // ---------- who may call the api ----------
    //
    // This server compiles and runs arbitrary Java, so "it only listens on 127.0.0.1" is not a
    // security boundary on its own: every web page you open is also running on this machine, and
    // any of them can send requests to localhost. Until this existed, any site you visited could
    // POST /api/save and then /api/run and execute whatever it liked, as you.
    //
    // Four checks, each closing a different door. None of them is redundant with another.

    /** The endpoints a plain GET may reach. Every one of them only reads. */
    private static final Set<String> READ_ONLY = Set.of(
        "/api/health", "/api/about", "/api/problems", "/api/problem", "/api/settings", "/api/pet", "/api/attempts");

    /**
     * All the browser extension may do: hand over the problem you are looking at, and check we are
     * up. Not leetcode.com itself: the extension's worker makes these calls, not the page, because
     * Chrome (since 142) asks the user before any web page may reach their own machine -- and a
     * gate that let leetcode.com in would be one more site with a path to a server that runs code.
     */
    private static final Set<String> FROM_EXTENSION = Set.of("/api/import", "/api/health");

    /** A Chrome extension's origin: chrome-extension:// and a 32-letter id written in a to p. */
    private static final java.util.regex.Pattern EXTENSION = java.util.regex.Pattern.compile("chrome-extension://[a-p]{32}");

    /** Null to allow the request, otherwise the reason it was refused. */
    private String refusal(HttpExchange ex, String path, String method) {
        Headers in = ex.getRequestHeaders();

        // 1. Host. A page on evil.example can repoint its own hostname at 127.0.0.1 (DNS rebinding)
        //    and from then on talk to us as if it were same-origin -- reading the replies, too. The
        //    browser still sends the name it believes it is talking to, so anything that is not a
        //    loopback name is somebody else's page.
        if (!isLoopbackHost(in.getFirst("Host"))) return "not a localhost request";

        // 2. Origin. Browsers attach it to every cross-site request and to every POST, and a page
        //    cannot forge it. Our own page may do anything; the extension may do the import; nothing
        //    else may do anything. Absent means not a browser at all (curl, a test), which is fine:
        //    anything that can run curl on this machine can already run Java on it.
        String origin = in.getFirst("Origin");
        if (origin != null && !isOwnOrigin(origin)
                && !(isExtensionOrigin(origin) && FROM_EXTENSION.contains(path))) {
            return "origin not allowed";
        }

        // 3. Method. GET never changes anything. Before this, an image tag pointing at /api/delete on
        //    any page would delete the problem called "untitled": a GET arrives with no body, and an
        //    empty slug is cleaned up into exactly that name.
        if (method.equals("GET")) {
            if (!READ_ONLY.contains(path)) return "use POST";
        } else if (method.equals("POST")) {
            // 4. Content-Type. A cross-site page may POST text/plain without asking first; it may not
            //    send application/json without a CORS preflight, which only the extension is granted.
            //    So even a browser that somehow left Origin off cannot fire a request we would act on.
            String ct = in.getFirst("Content-Type");
            if (ct == null || !ct.trim().toLowerCase(Locale.ROOT).startsWith("application/json")) {
                return "expected application/json";
            }
        } else if (!method.equals("OPTIONS")) {
            return "method not allowed";
        }
        return null;
    }

    private boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        return h.equals("localhost:" + port) || h.equals("127.0.0.1:" + port) || h.equals("[::1]:" + port);
    }

    private static boolean isExtensionOrigin(String origin) {
        return EXTENSION.matcher(origin.trim()).matches();
    }

    private boolean isOwnOrigin(String origin) {
        String o = origin.trim().toLowerCase(Locale.ROOT);
        return o.equals("http://localhost:" + port) || o.equals("http://127.0.0.1:" + port)
            || o.equals("http://[::1]:" + port);
    }

    /**
     * Headers every response gets. No framing, so a hostile page cannot load LCForge invisibly
     * underneath its own buttons and borrow your click on Run; no MIME sniffing; and no referrer,
     * so the problem diagrams fetched from LeetCode's CDN never carry a localhost URL with them.
     */
    private static void harden(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        h.set("X-Frame-Options", "DENY");
        h.set("Content-Security-Policy", "frame-ancestors 'none'");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
    }

    /**
     * What the About sheet shows. Kept off /api/health on purpose: health is the one endpoint a
     * page on leetcode.com may read, and the data folder's path has your username in it.
     */
    private Object about() {
        return Json.map("ok", true,
            "version", VERSION,
            "home", home.toString(),
            "java", System.getProperty("java.version", "?"));
    }

    /**
     * One problem's history, newest first, for the Attempts tab.
     *
     * A projection rather than the raw records, and capped, so the response does not grow without
     * bound over a year of Ctrl+Enter. Traces are left out: stepping through your code is studying
     * it, not an attempt at the answer.
     */
    private Object getAttempts(HttpExchange ex) {
        String slug = Store.safeSlug(query(ex, "slug"));
        List<Object> all = store.attempts(slug);
        List<Object> out = new ArrayList<>();
        int runs = 0, submits = 0;
        for (Object a : all) {
            if (!Json.str(a, "ev", "").equals("run")) continue;
            if (Learn.modeOf(a).equals(Learn.MODE_SUBMIT)) submits++; else runs++;
        }
        for (int i = all.size() - 1; i >= 0 && out.size() < 60; i--) {
            Object a = all.get(i);
            if (!Json.str(a, "ev", "").equals("run")) continue;
            Map<String,Object> row = Json.map(
                "t", Json.num(a, "t", 0),
                "mode", Learn.modeOf(a),
                "verdict", Json.str(a, "verdict", ""),
                "ok", Json.bool(a, "ok", false),
                "passed", Json.num(a, "passed", 0),
                "total", Json.num(a, "total", 0),
                "runMs", Json.num(a, "runMs", 0));
            long errors = Json.num(a, "errors", -1);
            if (errors >= 0) row.put("errors", errors);
            out.add(row);
        }
        return Json.map("ok", true, "attempts", out, "runs", runs, "submits", submits);
    }

    private Object getProblem(HttpExchange ex) throws IOException {
        String slug = query(ex, "slug");
        Map<String,Object> p = store.problem(slug);
        if (p == null) return Json.map("ok", false, "error", "unknown problem: " + slug);
        Map<String,Object> out = new LinkedHashMap<>(p);
        out.put("slug", slug);
        String code = store.readCode(slug, "Solution.java");
        if (code.isBlank()) {
            code = Json.str(p, "snippet", "");
            if (code.isBlank()) code = Importer.defaultSnippet();
        }
        out.put("code", code);
        return Json.map("ok", true, "problem", out);
    }

    /** Called by the Chrome extension with LeetCode's own GraphQL payload. */
    private Object doImport(Object body) throws IOException {
        String slug = Store.safeSlug(Json.str(body, "slug", ""));
        if (slug.isEmpty() || slug.equals("untitled")) {
            slug = Store.safeSlug(Importer.slugify(Json.str(body, "title", "")));
        }

        Map<String,Object> p = store.problem(slug);
        boolean isNew = (p == null);
        if (p == null) p = new LinkedHashMap<>();

        p.put("title", Json.str(body, "title", slug));
        p.put("difficulty", Json.str(body, "difficulty", ""));
        p.put("statement", Json.str(body, "statement", Json.str(p, "statement", "")));
        p.put("url", Json.str(body, "url", ""));
        if (Json.obj(body).containsKey("topicTags")) p.put("topicTags", Json.obj(body).get("topicTags"));

        String snippet = Json.str(body, "snippet", "");
        if (!snippet.isBlank()) p.put("snippet", snippet);

        // The signature from LeetCode's metaData: the entry method name plus parameter names and
        // types. Settles which method to call when a solution has helpers, and gives the UI
        // something to show you about what each input line means.
        Map<String,Object> meta = Importer.parseMeta(Json.str(body, "meta", ""));
        p.putAll(meta);

        // Testcases, in order of trustworthiness:
        //   1. cases the caller supplied outright
        //   2. LeetCode's own exampleTestcaseList (inputs) + expected outputs from the statement
        //   3. the statement alone, parsed for Input:/Output: pairs
        List<Object> cases = Json.arr(Json.obj(body).get("testcases"));
        if (cases.isEmpty()) {
            List<Object> fromHtml = Importer.testcasesFrom(Json.str(p, "statement", ""));
            List<Object> exampleInputs = Json.arr(Json.obj(body).get("exampleInputs"));
            cases = exampleInputs.isEmpty()
                ? fromHtml
                : Importer.mergeCases(exampleInputs, fromHtml);
        }
        if (!cases.isEmpty()) p.put("testcases", cases);
        p.putIfAbsent("testcases", new ArrayList<>());
        p.putIfAbsent("compareMode", Compare.MODE_EXACT);
        p.putIfAbsent("entry", "");

        store.putProblem(slug, p);

        // Seed the workspace only on first import -- never clobber code you have written.
        if (store.readCode(slug, "Solution.java").isBlank()) {
            String code = snippet.isBlank() ? Importer.defaultSnippet() : snippet;
            store.writeCode(slug, "Solution.java", code);
        }

        return Json.map("ok", true, "slug", slug, "isNew", isNew,
                        "url", "http://localhost:" + port + "/#/solve/" + slug);
    }

    /** Called by the paste box in the UI. */
    private Object doPaste(Object body) throws IOException {
        String text = Json.str(body, "text", "");
        Map<String,Object> parsed = Importer.parsePaste(text);
        String snippet = Json.str(body, "snippet", "");
        if (!snippet.isBlank()) parsed.put("snippet", snippet);
        return doImport(parsed);
    }

    private Object doSave(Object body) throws IOException {
        String slug = Store.safeSlug(Json.str(body, "slug", ""));
        Map<String,Object> p = store.problem(slug);
        if (p == null) return Json.map("ok", false, "error", "unknown problem");

        if (Json.obj(body).containsKey("code")) {
            store.writeCode(slug, "Solution.java", Json.str(body, "code", ""));
        }
        if (Json.obj(body).containsKey("testcases")) {
            p.put("testcases", Json.arr(Json.obj(body).get("testcases")));
        }
        if (Json.obj(body).containsKey("compareMode")) {
            p.put("compareMode", Json.str(body, "compareMode", Compare.MODE_EXACT));
        }
        if (Json.obj(body).containsKey("entry")) {
            p.put("entry", Json.str(body, "entry", ""));
        }
        store.putProblem(slug, p);
        return Json.map("ok", true);
    }

    private Object doDelete(Object body) throws IOException {
        store.deleteProblem(Store.safeSlug(Json.str(body, "slug", "")));
        return Json.map("ok", true);
    }

    private Object doSettings(String method, Object body) throws IOException {
        if (method.equals("POST")) {
            store.saveSettings(Json.obj(body));
        }
        // Never echo the raw keys back to the page.
        Map<String,Object> s = new LinkedHashMap<>(store.settings());
        for (String k : List.of("anthropicKey", "openaiKey", "geminiKey")) {
            Object v = s.get(k);
            if (v instanceof String && !((String) v).isBlank()) s.put(k, "***saved***");
        }
        return Json.map("ok", true, "settings", s);
    }

    // ---------- compile / run / trace ----------

    /** Compile the workspace, saving the incoming code first. Returns null when compilation failed. */
    private Compile.Result prepare(String slug, String code, List<Object> problems) throws IOException {
        if (code != null) store.writeCode(slug, "Solution.java", code);
        Path ws = store.workspace(slug);
        Path classes = store.classesDir(slug);
        // A stale .class from a renamed helper class would otherwise keep resolving.
        deleteClasses(classes);
        return Compile.compile(ws, runtimeDir, classes);
    }

    private static void deleteClasses(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.class")) {
            for (Path p : s) Files.deleteIfExists(p);
        }
    }

    private Object doRun(Object body) throws IOException {
        String slug = Store.safeSlug(Json.str(body, "slug", ""));
        Map<String,Object> p = store.problem(slug);
        if (p == null) return Json.map("ok", false, "error", "unknown problem");

        // Run or Submit. Which cases to send is the page's decision -- it knows which one you are
        // looking at -- so the server never picks; it only records which of the two this was.
        String mode = Learn.modeOf(body);
        boolean submit = mode.equals(Learn.MODE_SUBMIT);

        Compile.Result c = prepare(slug, Json.str(body, "code", null), null);
        if (!c.ok) {
            List<Object> diags = diagList(c);
            learn.record(Learn.summarizeCompileFailure(slug, c.millis, diags.size(), mode));
            return Json.map("ok", false, "phase", "compile", "mode", mode,
                            "diagnostics", diags, "millis", c.millis);
        }

        String entry = Json.str(body, "entry", Json.str(p, "entry", ""));
        String compare = Json.str(p, "compareMode", Compare.MODE_EXACT);

        // Run the cases the page sent if present, else the stored ones.
        List<Object> cases = Json.arr(Json.obj(body).get("testcases"));
        if (cases.isEmpty()) cases = Json.arr(p.get("testcases"));

        Path classes = store.classesDir(slug);
        runner.reload(classes);

        List<Object> results = new ArrayList<>();
        int passCount = 0;
        for (Object cObj : cases) {
            String input = Json.str(cObj, "input", "");
            String expected = Json.str(cObj, "expected", "");
            List<String> lines = splitLines(input);

            RunnerPool.Run r = runner.run(classes, "Solution", entry, lines, RUN_TIMEOUT_MS);

            Map<String,Object> res = new LinkedHashMap<>(r.toMap());
            res.put("input", input);
            res.put("expected", expected);
            if (r.ok) {
                Compare.Outcome o = Compare.check(r.output, expected, compare);
                res.put("verdict", o.verdict.name());
                if (o.note != null) res.put("note", o.note);
                if (o.passed()) passCount++;
            } else {
                res.put("verdict", r.timedOut ? "TIMEOUT" : "ERROR");
            }
            results.add(res);
        }

        // The library's status dot is a verdict on the problem, and one case is not a verdict on the
        // problem: a Run leaves the dot exactly as the last Submit left it.
        if (submit) {
            p.put("lastVerdict", results.isEmpty() ? "" : (passCount == results.size() ? "PASS" : "FAIL"));
        }

        // The one place a finished run is summarized, so it is the one place the learning layer
        // hooks in. Everything downstream -- heatmap, streak, personal bests, the ember's sparks --
        // reads the log this writes, and none of it needs another edit here.
        Map<String,Object> summary = Learn.summarizeRun(slug, body, passCount, results, c.millis);

        // Paid before the record is appended, so the ember's "have you already passed this today"
        // question is asked of the history and not of the run being asked about.
        long sparks = ember.awardRun(slug, Json.str(p, "difficulty", ""), summary);

        learn.observeRun(p, summary);
        store.putProblem(slug, p);
        learn.record(summary);

        Map<String,Object> out = Json.map("ok", true, "phase", "run", "mode", mode,
                        "results", results,
                        "passed", passCount,
                        "total", results.size(),
                        "compileMillis", c.millis,
                        "diagnostics", diagList(c));
        if (sparks > 0) out.put("sparks", sparks);
        // A full pass that pays nothing looks exactly like a full pass that pays, and the only
        // difference is a ledger the page cannot see. Say so, or the next time it happens it reads
        // as the currency being broken rather than as the rule working.
        else if (submit && !results.isEmpty() && passCount == results.size()) out.put("claimed", true);
        return out;
    }

    /**
     * Run one input and hand back whatever the current solution produced, with no comparison.
     *
     * This fills in the "expected" box for a case you just invented. It is a REGRESSION baseline,
     * not ground truth: it records what your code does today so you notice when that changes. If
     * the solution is wrong for this input, it will happily capture the wrong answer -- which is
     * why the UI labels every captured value and asks you to eyeball it.
     */
    private Object doCapture(Object body) throws IOException {
        String slug = Store.safeSlug(Json.str(body, "slug", ""));
        Map<String,Object> p = store.problem(slug);
        if (p == null) return Json.map("ok", false, "error", "unknown problem");

        String input = Json.str(body, "input", "");
        if (input.isBlank()) return Json.map("ok", false, "error", "nothing to run -- add an input first");

        Compile.Result c = prepare(slug, Json.str(body, "code", null), null);
        if (!c.ok) {
            return Json.map("ok", false, "phase", "compile",
                            "error", "fix the compile errors first",
                            "diagnostics", diagList(c));
        }

        Path classes = store.classesDir(slug);
        runner.reload(classes);
        RunnerPool.Run r = runner.run(classes, "Solution",
            Json.str(body, "entry", Json.str(p, "entry", "")),
            splitLines(input), RUN_TIMEOUT_MS);

        if (!r.ok) {
            return Json.map("ok", false,
                            "error", r.timedOut ? "timed out before producing an answer" : r.error);
        }
        return Json.map("ok", true, "output", r.output, "stdout", r.stdout, "millis", r.millis);
    }

    private Object doTrace(Object body) throws IOException {
        String slug = Store.safeSlug(Json.str(body, "slug", ""));
        Map<String,Object> p = store.problem(slug);
        if (p == null) return Json.map("ok", false, "error", "unknown problem");

        Compile.Result c = prepare(slug, Json.str(body, "code", null), null);
        if (!c.ok) {
            return Json.map("ok", false, "phase", "compile", "diagnostics", diagList(c));
        }

        String entry = Json.str(body, "entry", Json.str(p, "entry", ""));
        List<String> lines = splitLines(Json.str(body, "input", ""));

        Tracer.Trace t = Tracer.trace(buildDir, store.classesDir(slug), "Solution", entry, lines);
        Map<String,Object> out = new LinkedHashMap<>(t.toMap());
        out.put("phase", "trace");

        String expected = Json.str(body, "expected", "");
        out.put("expected", expected);

        // Step count is an efficiency score, so it only counts when the run was right. A solution
        // that returns early traces in a dozen steps; recording that as a record would hand the
        // best score to the worst answer.
        long steps = Json.num(out, "stepCount", 0);
        boolean truncated = Json.bool(out, "truncated", false);
        String got = Json.str(Json.obj(out.get("result")), "output", "");
        boolean correct = !expected.isBlank() && got.trim().equals(expected.trim());

        learn.observeTrace(p, steps, truncated, correct);
        store.putProblem(slug, p);
        learn.record(Learn.summarizeTrace(slug, steps, truncated, correct));
        out.put("bestSteps", Json.num(p, "bestSteps", 0));
        return out;
    }

    // ---------- the ember ----------

    private Object doFeed(Object body) throws IOException {
        long now = System.currentTimeMillis();
        return ember.wrap(ember.feed(Json.str(body, "fuel", ""),
                                     Json.bool(body, "unobserved", false), now), now);
    }

    private Object doBuy(Object body) throws IOException {
        long now = System.currentTimeMillis();
        return ember.wrap(ember.buy(Json.str(body, "item", ""), now), now);
    }

    private Object doEquip(Object body) throws IOException {
        long now = System.currentTimeMillis();
        return ember.wrap(ember.equip(Json.str(body, "item", ""),
                                      Json.bool(body, "on", true), now), now);
    }

    private Object doName(Object body) throws IOException {
        long now = System.currentTimeMillis();
        return ember.wrap(ember.rename(Json.str(body, "name", ""), now), now);
    }

    private static List<Object> diagList(Compile.Result c) {
        List<Object> out = new ArrayList<>();
        for (Compile.Diagnostic d : c.diagnostics) out.add(d.toMap());
        return out;
    }

    /** LeetCode format: one argument per line. Blank lines are dropped, not treated as arguments. */
    private static List<String> splitLines(String input) {
        List<String> out = new ArrayList<>();
        for (String l : input.split("\\R")) {
            if (!l.isBlank()) out.add(l.trim());
        }
        return out;
    }

    // ---------- http helpers ----------

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return "";
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(key)) {
                return java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        }
        ex.close();
    }
}
