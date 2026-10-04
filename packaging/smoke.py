#!/usr/bin/env python3
"""Smoke-test a built LCForge: start it, judge a problem, step through it, stop it.

    python3 packaging/smoke.py [--version 1.0.0] [--expect-bundled-editor] -- LAUNCHER [ARGS...]

    e.g.  python3 packaging/smoke.py -- java -jar dist/lcforge.jar
          python3 packaging/smoke.py --expect-bundled-editor -- out/LCForge/LCForge.exe

The release workflow runs this against the jar and against every app it packages, before anything
is uploaded: a build whose bundled Java cannot compile, judge or debug a solution never ships.
Each run gets a fresh, empty data folder and a free port, so it never touches anyone's real data.
Standard library only -- like LCForge itself, nothing to install.
"""
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

# A problem with a loop in it, so a trace has steps worth counting.
SOLUTION = """class Solution {
    public int sumTo(int n) {
        int total = 0;
        for (int i = 1; i <= n; i++) {
            total += i;
        }
        return total;
    }
}
"""
CASES = [{"input": "4", "expected": "10"}, {"input": "1", "expected": "1"}, {"input": "0", "expected": "0"}]

failures = []


def check(name, ok, detail=""):
    print(("  ok    " if ok else "  FAIL  ") + name + (f"  -- {detail}" if detail != "" else ""))
    if not ok:
        failures.append(name)


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def main(argv):
    if "--" not in argv:
        sys.exit(__doc__)
    split = argv.index("--")
    opts, launcher = argv[:split], argv[split + 1:]
    version = opts[opts.index("--version") + 1] if "--version" in opts else None
    bundled = "--expect-bundled-editor" in opts

    port = free_port()
    home = tempfile.mkdtemp(prefix="lcforge-smoke-")
    base = f"http://127.0.0.1:{port}"
    log_path = os.path.join(home, "launcher.log")

    def request(path, body=None, headers=None):
        data = None if body is None else json.dumps(body).encode()
        h = {"Content-Type": "application/json"} if body is not None else {}
        h.update(headers or {})
        req = urllib.request.Request(base + path, data=data, headers=h, method="POST" if body is not None else "GET")
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                return r.status, dict(r.headers), r.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    def api(path, body=None):
        return json.loads(request(path, body)[2])

    cmd = launcher + ["--console", "--no-browser", "--port", str(port), "--home", home]
    print("starting:", " ".join(cmd))
    log = open(log_path, "wb")
    # Its own process group, so stopping it stops the judge JVMs it started too.
    if os.name == "nt":
        proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT,
                                creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
    else:
        proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)

    try:
        health = None
        deadline = time.time() + 90
        while time.time() < deadline and proc.poll() is None:
            try:
                health = api("/api/health")
                break
            except (urllib.error.URLError, ConnectionError, OSError):
                time.sleep(0.3)
        if health is None:
            check("LCForge started", False, f"exit code {proc.poll()}")
            return

        check("health: it is LCForge", health.get("app") == "lcforge", health)
        if version:
            check("health: version " + version, health.get("version") == version, health.get("version"))
        if bundled:
            check("health: editor bundled", health.get("editor") == "bundled", health.get("editor"))
            for path in ("/vendor/monaco/vs/loader.js", "/vendor/monaco/vs/editor/editor.main.js"):
                st, hd, _ = request(path)
                check("serves " + path, st == 200, st)

        st, hd, body = request("/")
        csp = {k.lower(): v for k, v in hd.items()}.get("content-security-policy", "")
        check("serves the interface", st == 200 and b"LCForge" in body, st)
        check("the interface has its security policy", "script-src 'self'" in csp, csp[:60])

        about = api("/api/about")
        print(f"        running on Java {about.get('java')}, data in {about.get('home')}")

        imp = api("/api/import", {"title": "Smoke Sum", "difficulty": "Easy", "snippet": SOLUTION, "testcases": CASES})
        slug = imp.get("slug", "")
        check("imports a problem", imp.get("ok") is True and slug, imp)

        t0 = time.time()
        run = api("/api/run", {"slug": slug, "mode": "submit"})
        check("compiles and judges a Submit (bundled javac + bin/java)",
              run.get("ok") is True and run.get("passed") == 3 and run.get("total") == 3,
              {k: run.get(k) for k in ("ok", "phase", "passed", "total", "error")})
        print(f"        submit took {(time.time() - t0) * 1000:.0f} ms")

        t0 = time.time()
        trace = api("/api/trace", {"slug": slug, "input": "4", "expected": "10"})
        out = (trace.get("result") or {}).get("output", "")
        check("steps through it (JDI + the jdwp agent)", trace.get("stepCount", 0) > 0 and out.strip() == "10",
              {k: trace.get(k) for k in ("stepCount", "truncated", "error")})
        print(f"        trace took {(time.time() - t0) * 1000:.0f} ms")

        st, _, _ = request("/api/run", {"slug": slug, "mode": "submit"}, {"Origin": "https://evil.example"})
        check("refuses a cross-site request", st == 403, st)
    finally:
        stop(proc)
        log.close()
        if failures:
            print("\n---- launcher output ----")
            with open(log_path, "rb") as f:
                sys.stdout.write(f.read().decode("utf-8", "replace")[-6000:])
        shutil.rmtree(home, ignore_errors=True)


def stop(proc):
    if proc.poll() is not None:
        return
    if os.name == "nt":
        # The jpackage launcher re-runs itself as a child: /T takes the whole tree.
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True)
    else:
        try:
            os.killpg(proc.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    try:
        proc.wait(timeout=20)
    except subprocess.TimeoutExpired:
        proc.kill()


if __name__ == "__main__":
    main(sys.argv[1:])
    print("\nsmoke test: " + ("passed" if not failures else f"{len(failures)} FAILED"))
    sys.exit(1 if failures else 0)
