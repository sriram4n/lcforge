package lcforge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The web interface's files, wherever they happen to be.
 *
 * In a source checkout they are the web/ folder, read fresh on every request so an edit shows up on
 * reload. Packaged, they travel inside the jar and are read from the classpath. The server asks for
 * "/app.js" and does not need to know which.
 */
public interface Assets {

    /** The bytes served at a site path like "/app.js", or null if there is no such file. */
    byte[] read(String sitePath) throws IOException;

    /** For the startup banner. */
    String describe();

    /**
     * The one rule both implementations share: a site path names a file below the root and nothing
     * else. No "..", no backslashes, no drive letters, no trailing slash -- refused outright rather
     * than normalised, because a request that needs normalising is not one a browser sends.
     */
    static String clean(String sitePath) {
        if (sitePath == null || !sitePath.startsWith("/") || sitePath.endsWith("/")) return null;
        String rel = sitePath.substring(1);
        if (rel.isEmpty() || rel.contains("\\") || rel.contains(":") || rel.contains("\0")) return null;
        for (String part : rel.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return null;
        }
        return rel;
    }

    /** Files under a directory on disk. */
    static Assets dir(Path root) {
        Path base = root.toAbsolutePath().normalize();
        return new Assets() {
            public byte[] read(String sitePath) throws IOException {
                String rel = clean(sitePath);
                if (rel == null) return null;
                Path file = base.resolve(rel).normalize();
                if (!file.startsWith(base) || !Files.isRegularFile(file)) return null;
                return Files.readAllBytes(file);
            }
            public String describe() { return base.toString(); }
        };
    }

    /** Files packed into the jar under a prefix, e.g. "web". */
    static Assets classpath(String prefix) {
        return new Assets() {
            public byte[] read(String sitePath) throws IOException {
                String rel = clean(sitePath);
                if (rel == null) return null;
                try (InputStream in = Assets.class.getResourceAsStream("/" + prefix + "/" + rel)) {
                    return in == null ? null : in.readAllBytes();
                }
            }
            public String describe() { return "built in"; }
        };
    }
}
