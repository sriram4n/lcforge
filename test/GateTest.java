import lcforge.Server;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * The API gate, attacked the way a hostile web page would attack it.
 *
 * LCForge compiles and runs whatever Java it is handed, so this is the test that matters most to
 * anyone who installs it: if a request from some other website ever reaches /api/run again, every
 * page that user opens can execute code on their machine. Each row is a request a browser, or a
 * DNS-rebinding page, could genuinely produce.
 *
 * Raw sockets rather than HttpClient, because the JDK's clients refuse to let you set Host and
 * Origin yourself -- and forging those two headers is the entire attack.
 */
public class GateTest {

    static int passed = 0, failed = 0;
    static int port;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        Path home = root.resolve("build/gate-test");
        deleteTree(home);

        try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
        new Server(root, home).start(port);

        String own = "http://localhost:" + port;
        String evil = "https://evil.example";
        String lc = "https://leetcode.com";
        String ext = "chrome-extension://" + "abcdefghijklmnop".repeat(2);   // the shape of a real id
        String json = "application/json";
        String run = "{\"slug\":\"no-such-problem\"}";

        // ---- every door, shut ----
        expect("cross-site text/plain POST to run", 403, req("POST", "/api/run", run, host(), evil, "text/plain;charset=UTF-8"));
        expect("cross-site JSON POST to run",       403, req("POST", "/api/run", run, host(), evil, json));
        expect("cross-site form POST to save",      403, req("POST", "/api/save", "slug=x", host(), evil, "application/x-www-form-urlencoded"));
        expect("DNS rebinding: attacker Host",      403, req("POST", "/api/run", run, "evil.example:" + port, "http://evil.example:" + port, json));
        expect("DNS rebinding: GET read",           403, req("GET", "/api/problems", null, "evil.example:" + port, null, null));
        expect("missing Host header",               403, req("GET", "/api/problems", null, null, null, null));
        expect("sandboxed iframe, Origin: null",    403, req("POST", "/api/run", run, host(), "null", json));
        expect("image-tag GET /api/delete",         403, req("GET", "/api/delete", null, host(), null, null));
        expect("image-tag GET /api/run",            403, req("GET", "/api/run", null, host(), null, null));
        expect("text/plain POST with no Origin",    403, req("POST", "/api/run", run, host(), null, "text/plain"));
        expect("PUT",                               403, req("PUT", "/api/save", run, host(), null, json));
        expect("leetcode.com may not run",          403, req("POST", "/api/run", run, host(), lc, json));
        expect("leetcode.com may not save",         403, req("POST", "/api/save", run, host(), lc, json));
        expect("leetcode.com may not read about",   403, req("GET", "/api/about", null, host(), lc, null));
        // The import moved into the extension's own worker; the page itself gets nothing now.
        expect("leetcode.com may no longer import", 403, req("POST", "/api/import", "{}", host(), lc, json));
        expect("leetcode.com may no longer probe",  403, req("GET", "/api/health", null, host(), lc, null));
        expect("the extension may not run",         403, req("POST", "/api/run", run, host(), ext, json));
        expect("the extension may not save",        403, req("POST", "/api/save", run, host(), ext, json));
        // About carries the data folder's path, and with it your username. Health is the one read
        // the extension is allowed; this is not that.
        expect("the extension may not read about",  403, req("GET", "/api/about", null, host(), ext, null));
        expect("a malformed extension id may not import", 403,
               req("POST", "/api/import", "{}", host(), "chrome-extension://evil", json));
        Resp pre = req("OPTIONS", "/api/run", null, host(), evil, null);
        expect("hostile preflight refused",         403, pre);
        check("hostile preflight gets no CORS grant", pre.headers.get("access-control-allow-origin"), null);
        expect("app page under a rebinding Host",   403, req("GET", "/", null, "evil.example:" + port, null, null));

        // ---- and every legitimate path still open ----
        expect("own page, JSON POST",               200, req("POST", "/api/pet", "{}", host(), own, json));
        expect("own page via 127.0.0.1",            200, req("POST", "/api/pet", "{}", "127.0.0.1:" + port, "http://127.0.0.1:" + port, "application/json; charset=utf-8"));
        expect("plain GET read, no Origin",         200, req("GET", "/api/problems", null, host(), null, null));
        Resp health = req("GET", "/api/health", null, host(), ext, null);
        expect("the extension can still check we are up", 200, health);
        check("health names the app, for the launcher's second-instance check", health.body.contains("\"app\":\"lcforge\""), true);
        expect("own page reaches run (gate opens)", 200, req("POST", "/api/run", run, host(), own, json));
        Resp lp = req("OPTIONS", "/api/import", null, host(), ext, null);
        expect("extension preflight",               204, lp);
        check("extension preflight granted to the extension", lp.headers.get("access-control-allow-origin"), ext);
        Resp imp = req("POST", "/api/import",
            "{\"slug\":\"gate-test\",\"title\":\"1. Gate\",\"statement\":\"<p>x</p>\"}", host(), ext, json);
        expect("extension import",                  200, imp);
        check("import reply carries the real port", imp.body.contains("localhost:" + port + "/#/solve/gate-test"), true);
        Resp page = req("GET", "/", null, host(), null, null);
        expect("app page",                          200, page);
        check("app page cannot be framed", page.headers.get("x-frame-options"), "DENY");

        // The second wall: even a script that got past the statement sanitizer must not run.
        String csp = String.valueOf(page.headers.get("content-security-policy"));
        String scriptSrc = csp.contains("script-src") ? csp.split("script-src")[1].split(";")[0] : "";
        check("app page carries a script policy", scriptSrc.contains("'self'"), true);
        check("...that allows no inline script", !scriptSrc.contains("unsafe-inline") && !scriptSrc.contains("unsafe-eval"), true);
        check("...and talks to no other server", csp.contains("connect-src 'self'"), true);

        System.out.println();
        System.out.println(failed == 0
            ? "Gate: all " + passed + " checks passed"
            : "Gate: " + passed + " passed, " + failed + " FAILED");
        System.exit(failed > 0 ? 1 : 0);
    }

    static String host() { return "localhost:" + port; }

    record Resp(int status, Map<String,String> headers, String body) {}

    /** One raw HTTP/1.1 request. A null host, origin or type leaves that header out entirely. */
    static Resp req(String method, String path, String body, String host, String origin, String type)
            throws IOException {
        byte[] data = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        if (host != null) head.append("Host: ").append(host).append("\r\n");
        if (origin != null) head.append("Origin: ").append(origin).append("\r\n");
        if (type != null) head.append("Content-Type: ").append(type).append("\r\n");
        if (method.equals("OPTIONS")) {
            head.append("Access-Control-Request-Method: POST\r\n");
            head.append("Access-Control-Request-Headers: content-type\r\n");
        }
        if (body != null) head.append("Content-Length: ").append(data.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");

        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(30_000);
            OutputStream out = s.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(data);
            out.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String status = in.readLine();
            int code = Integer.parseInt(status.split(" ")[1]);
            Map<String,String> headers = new HashMap<>();
            for (String line; (line = in.readLine()) != null && !line.isEmpty(); ) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
            }
            StringBuilder b = new StringBuilder();
            for (String line; (line = in.readLine()) != null; ) b.append(line).append('\n');
            return new Resp(code, headers, b.toString());
        }
    }

    static void expect(String label, int want, Resp r) {
        check(label + " -> " + want, r.status, want);
    }

    static void check(String label, Object got, Object want) {
        if (Objects.equals(got, want)) { passed++; System.out.println("  ok    " + label); }
        else { failed++; System.out.println("  FAIL  " + label + "  (got " + got + ", wanted " + want + ")"); }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(q -> { try { Files.delete(q); } catch (IOException ignored) {} });
        }
    }
}
