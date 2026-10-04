/*
 * LCForge's app icons, drawn in Java2D from the same geometry as web/icon.svg. No image editor and
 * no converter to install: this one file is the source of every icon the installers carry.
 *
 *   java packaging/IconGen.java packaging/icons extension/icons
 *
 * Writes, into the first folder:
 *   icon.ico               Windows: 16 to 64 as 32-bit bitmaps, 256 as PNG (the layout Explorer expects)
 *   icon.icns              macOS: PNG entries, 16 to 1024 at @1x and @2x, on Apple's icon grid
 *   icon.png               Linux: 512, for the desktop entry
 *   icon-16/32/48/256.png  the status window's own icons, carried inside the jar
 * and into the second, the Chrome extension's: icon-16/32/48.png, and icon-128.png drawn inside the
 * Web Store's 96-pixel safe area.
 *
 * The outputs are committed, so no build runs this. Run it again only after changing the drawing.
 */
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class IconGen {

    /**
     * MAC sits on Apple's grid (an 824 tile in a 1024 canvas, with a shadow); PLAIN nearly fills
     * the canvas; STORE keeps the 16-in-128 margin the Chrome Web Store asks for.
     */
    enum Style { MAC, PLAIN, STORE }

    // Forge, the default palette: the tile is its background, the flame its accent.
    static final Color TILE_TOP = new Color(0x242c3b), TILE_BOTTOM = new Color(0x0e1116);
    static final Color AMBER = new Color(0xffa116);

    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        Path out = Paths.get(args.length > 0 ? args[0] : "packaging/icons");
        Files.createDirectories(out);

        for (int size : new int[]{ 16, 32, 48, 256 }) write(out.resolve("icon-" + size + ".png"), png(render(size, Style.PLAIN)));
        write(out.resolve("icon.png"), png(render(512, Style.PLAIN)));
        write(out.resolve("icon.ico"), ico(16, 20, 24, 32, 40, 48, 64, 256));
        write(out.resolve("icon.icns"), icns());

        if (args.length > 1) {
            Path ext = Paths.get(args[1]);
            Files.createDirectories(ext);
            for (int size : new int[]{ 16, 32, 48 }) write(ext.resolve("icon-" + size + ".png"), png(render(size, Style.PLAIN)));
            write(ext.resolve("icon-128.png"), png(render(128, Style.STORE)));
        }
    }

    static void write(Path p, byte[] bytes) throws IOException {
        Files.write(p, bytes);
        System.out.printf("  %-14s %,8d bytes%n", p.getFileName(), bytes.length);
    }

    // ---- the drawing -----------------------------------------------------------------------------

    static BufferedImage render(int size, Style style) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        double s = size;
        // Below about 24 pixels detail turns to mud: no glow, no inner core, a bigger flame and
        // bigger eyes, so it still reads as a face and not a smudge.
        boolean small = size <= 24;

        double inset, radius;
        if (style == Style.MAC) { inset = s * 100 / 1024; radius = s * 185.4 / 1024; }
        else if (style == Style.STORE) { inset = s / 8; radius = (s - 2 * inset) * 0.22; }
        else { inset = small ? Math.max(0.5, s / 32) : s / 16; radius = (s - 2 * inset) * 0.22; }
        double tileSize = s - 2 * inset;
        RoundRectangle2D tile = new RoundRectangle2D.Double(inset, inset, tileSize, tileSize, 2 * radius, 2 * radius);

        if (style == Style.MAC) shadow(img, tile, s * 0.012, s * 0.022, 0.42f);

        Graphics2D g = img.createGraphics();
        hints(g);
        g.setPaint(new GradientPaint(0, (float) inset, TILE_TOP, 0, (float) (s - inset), TILE_BOTTOM));
        g.fill(tile);

        g.clip(tile);
        if (!small) {
            // A hairline of light along the top edge: the stroke straddles the edge, the clip keeps
            // only its inner half.
            g.setStroke(new BasicStroke((float) Math.max(1.5, s / 170)));
            g.setPaint(new GradientPaint(0, (float) inset, new Color(255, 255, 255, 46),
                                         0, (float) (inset + tileSize * 0.35), new Color(255, 255, 255, 0)));
            g.draw(tile);
        }

        // SVG units to pixels. With the glow the composition runs from the tip (y 2) to the bottom
        // of the lit floor (y 25.1); without it, tip to the flame's base (y 20.2).
        double top = 2, bottom = small ? 20.2 : 25.1;
        double k = tileSize * (small ? 0.80 : 0.76) / (bottom - top);
        AffineTransform toPx = new AffineTransform();
        toPx.translate(s / 2, s / 2 + tileSize * (small ? 0.0 : 0.01));
        toPx.scale(k, k);
        toPx.translate(-12, -(top + bottom) / 2);
        g.transform(toPx);

        if (!small) {
            // The ember lights the forge around it, and the floor beneath it.
            g.setPaint(radial(12, 14.5, 12.5, 12.5, new float[]{ 0f, 1f },
                              new Color[]{ alpha(AMBER, 0.30f), alpha(AMBER, 0f) }));
            g.fill(new Rectangle2D.Double(-20, -20, 64, 64));
            g.setPaint(radial(12, 22.6, 7.6, 2.7, new float[]{ 0f, 0.55f, 1f },
                              new Color[]{ alpha(AMBER, 0.55f), alpha(AMBER, 0.18f), alpha(AMBER, 0f) }));
            g.fill(new Ellipse2D.Double(12 - 7.6, 22.6 - 2.7, 15.2, 5.4));
        }

        // The body: web/icon.svg's gradient, x1=0 y1=0 x2=.18 y2=1 over the flame's bounding box.
        Path2D body = flame();
        Rectangle2D bb = body.getBounds2D();
        g.setPaint(new LinearGradientPaint(new Point2D.Double(0, 0), new Point2D.Double(0.18, 1),
                new float[]{ 0f, 0.45f, 1f },
                new Color[]{ new Color(0xffc35c), AMBER, new Color(0xff7b0f) },
                MultipleGradientPaint.CycleMethod.NO_CYCLE, MultipleGradientPaint.ColorSpaceType.SRGB,
                boxTransform(bb)));
        g.fill(body);

        if (!small) {
            Path2D core = core();
            g.setPaint(new RadialGradientPaint(new Point2D.Double(0.5, 0.74), 0.68f, new Point2D.Double(0.5, 0.74),
                    new float[]{ 0f, 1f }, new Color[]{ new Color(107, 51, 0, 128), new Color(255, 140, 0, 0) },
                    MultipleGradientPaint.CycleMethod.NO_CYCLE, MultipleGradientPaint.ColorSpaceType.SRGB,
                    boxTransform(core.getBounds2D())));
            g.fill(core);
        }

        double eye = small ? 1.5 : 1.2;
        g.setPaint(new Color(0x24170a));
        g.fill(circle(10, 14.5, eye));
        g.fill(circle(14, 14.5, eye));
        if (size >= 48) {
            g.setPaint(new Color(255, 255, 255, 217));
            g.fill(circle(9.62, 14.08, 0.38));
            g.fill(circle(13.62, 14.08, 0.38));
        }
        g.dispose();
        return img;
    }

    /** The flame, exactly as web/icon.svg draws it. */
    static Path2D flame() {
        Path2D p = new Path2D.Double();
        p.moveTo(12, 2);
        p.curveTo(15.5, 7, 19, 9.2, 19, 13.2);
        // A 7 7 0 0 1 5 13.2: the bottom half of a circle about (12, 13.2). Java2D measures angles
        // anticlockwise on screen, so clockwise through the bottom is a negative extent.
        p.append(new Arc2D.Double(5, 6.2, 14, 14, 0, -180, Arc2D.OPEN), true);
        p.curveTo(5, 9.6, 7.5, 8.4, 9, 5.6);
        p.curveTo(9.8, 8, 10.6, 8.8, 11.4, 9.4);
        p.curveTo(11.9, 7.6, 11.6, 4.8, 12, 2);
        p.closePath();
        return p;
    }

    /** The darker heart behind the eyes. */
    static Path2D core() {
        Path2D p = new Path2D.Double();
        p.moveTo(12, 9);
        p.curveTo(13.6, 11.4, 15, 12.6, 15, 14.6);
        p.append(new Arc2D.Double(9, 11.6, 6, 6, 0, -180, Arc2D.OPEN), true);
        p.curveTo(9, 12.8, 10.8, 11.8, 12, 9);
        p.closePath();
        return p;
    }

    /** SVG's objectBoundingBox units: (0,0) to (1,1) stretched over the shape's box. */
    static AffineTransform boxTransform(Rectangle2D bb) {
        AffineTransform t = AffineTransform.getTranslateInstance(bb.getX(), bb.getY());
        t.scale(bb.getWidth(), bb.getHeight());
        return t;
    }

    /** An elliptical radial gradient: a unit circle's gradient, stretched to rx by ry about (cx, cy). */
    static Paint radial(double cx, double cy, double rx, double ry, float[] stops, Color[] colors) {
        AffineTransform t = AffineTransform.getTranslateInstance(cx, cy);
        t.scale(rx, ry);
        return new RadialGradientPaint(new Point2D.Double(0, 0), 1f, new Point2D.Double(0, 0), stops, colors,
                MultipleGradientPaint.CycleMethod.NO_CYCLE, MultipleGradientPaint.ColorSpaceType.SRGB, t);
    }

    static Ellipse2D circle(double cx, double cy, double r) { return new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r); }

    static Color alpha(Color c, float a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(a * 255)); }

    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    /** A soft drop shadow under a shape: its silhouette, blurred by three box passes (close to Gaussian). */
    static void shadow(BufferedImage img, Shape shape, double dy, double blur, float opacity) {
        int n = img.getWidth();
        BufferedImage m = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = m.createGraphics();
        hints(g);
        g.translate(0, dy);
        g.setColor(Color.BLACK);
        g.fill(shape);
        g.dispose();
        float[] a = new float[n * n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) a[y * n + x] = (m.getRGB(x, y) >>> 24) / 255f;
        int r = (int) Math.max(1, Math.round(blur / 2));
        for (int pass = 0; pass < 3; pass++) { a = boxBlur(a, n, r, true); a = boxBlur(a, n, r, false); }
        for (int i = 0; i < a.length; i++) {
            int al = Math.min(255, Math.round(a[i] * opacity * 255));
            img.setRGB(i % n, i / n, al << 24);
        }
    }

    static float[] boxBlur(float[] in, int n, int r, boolean horizontal) {
        float[] out = new float[in.length];
        for (int line = 0; line < n; line++) {
            float sum = 0;
            for (int i = -r; i <= r; i++) sum += at(in, n, line, i, horizontal);
            for (int i = 0; i < n; i++) {
                out[horizontal ? line * n + i : i * n + line] = sum / (2 * r + 1);
                sum += at(in, n, line, i + r + 1, horizontal) - at(in, n, line, i - r, horizontal);
            }
        }
        return out;
    }

    static float at(float[] a, int n, int line, int i, boolean horizontal) {
        if (i < 0 || i >= n) return 0;
        return horizontal ? a[line * n + i] : a[i * n + line];
    }

    // ---- the containers --------------------------------------------------------------------------

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /**
     * A Windows .ico. Small sizes as 32-bit bitmaps with an alpha channel, which every part of
     * Windows reads; 256 as PNG, which is how Explorer expects the big one (as a bitmap it would be
     * a quarter of a megabyte).
     */
    static byte[] ico(int... sizes) throws IOException {
        List<byte[]> images = new ArrayList<>();
        for (int size : sizes) {
            BufferedImage img = render(size, Style.PLAIN);
            images.add(size >= 256 ? png(img) : dib(img));
        }
        int offset = 6 + 16 * sizes.length;
        ByteBuffer b = ByteBuffer.allocate(offset + images.stream().mapToInt(x -> x.length).sum()).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        for (int i = 0; i < sizes.length; i++) {
            int size = sizes[i];
            b.put((byte) (size >= 256 ? 0 : size)).put((byte) (size >= 256 ? 0 : size));   // 0 means 256
            b.put((byte) 0).put((byte) 0);                                                   // no palette
            b.putShort((short) 1).putShort((short) 32);                                     // planes, bits
            b.putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        for (byte[] img : images) b.put(img);
        return b.array();
    }

    /** One icon image as a DIB: a BITMAPINFOHEADER, BGRA rows bottom-up, then the 1-bit AND mask. */
    static byte[] dib(BufferedImage img) {
        int n = img.getWidth();
        int maskRow = ((n + 31) / 32) * 4;
        ByteBuffer b = ByteBuffer.allocate(40 + n * n * 4 + maskRow * n).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(40).putInt(n).putInt(n * 2)            // height counts the mask too
         .putShort((short) 1).putShort((short) 32)
         .putInt(0).putInt(n * n * 4 + maskRow * n)
         .putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = n - 1; y >= 0; y--) {
            for (int x = 0; x < n; x++) {
                int argb = img.getRGB(x, y);
                b.put((byte) argb).put((byte) (argb >> 8)).put((byte) (argb >> 16)).put((byte) (argb >>> 24));
            }
        }
        for (int y = n - 1; y >= 0; y--) {
            byte[] row = new byte[maskRow];
            for (int x = 0; x < n; x++) if ((img.getRGB(x, y) >>> 24) == 0) row[x / 8] |= (byte) (0x80 >> (x % 8));
            b.put(row);
        }
        return b.array();
    }

    /** A macOS .icns: PNG data under each type code, the format every macOS since 10.7 reads. */
    static byte[] icns() throws IOException {
        Map<String, Integer> types = new LinkedHashMap<>();
        types.put("icp4", 16);   types.put("ic11", 32);     // 16 pt, @1x and @2x
        types.put("icp5", 32);   types.put("ic12", 64);     // 32 pt
        types.put("ic07", 128);  types.put("ic13", 256);    // 128 pt
        types.put("ic08", 256);  types.put("ic14", 512);    // 256 pt
        types.put("ic09", 512);  types.put("ic10", 1024);   // 512 pt
        Map<Integer, byte[]> rendered = new LinkedHashMap<>();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, Integer> t : types.entrySet()) {
            byte[] data = rendered.get(t.getValue());
            if (data == null) { data = png(render(t.getValue(), Style.MAC)); rendered.put(t.getValue(), data); }
            body.write(t.getKey().getBytes("US-ASCII"));
            body.write(ByteBuffer.allocate(4).putInt(8 + data.length).array());   // big-endian
            body.write(data);
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write("icns".getBytes("US-ASCII"));
        file.write(ByteBuffer.allocate(4).putInt(8 + body.size()).array());
        body.writeTo(file);
        return file.toByteArray();
    }
}
