package lcforge;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything LCForge shows outside the browser: one small window, one error dialog, and opening a
 * browser tab. Kept in its own class so a `--console` run never loads a GUI toolkit at all.
 *
 * The window is deliberately plain — the OS's own look, three buttons. It is not where you work;
 * it is how you know LCForge is running and how you stop it. Closing it stops LCForge.
 */
final class Gui {

    static void statusWindow(String url, Path home, Server server) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { /* the cross-platform look is fine too */ }

            JFrame f = new JFrame("LCForge");
            List<BufferedImage> icons = icons();
            if (!icons.isEmpty()) {
                // Every size, so the title bar and the taskbar each pick a drawn one, not a blur.
                f.setIconImages(new ArrayList<>(icons));
                // The Dock icon, which only macOS lets a program set. Not from an installed app: there
                // the Dock already shows the bundle's own icon, drawn on Apple's grid, and this one
                // (sized for Windows and Linux) would replace it.
                if (System.getProperty("jpackage.app-path") == null) {
                    try { Taskbar.getTaskbar().setIconImage(icons.get(icons.size() - 1)); } catch (Exception | Error ignored) { }
                }
            }

            JLabel title = new JLabel("LCForge is running");
            title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 4f));
            JLabel where = new JLabel(url);
            where.setForeground(UIManager.getColor("Label.disabledForeground"));
            JLabel note = new JLabel("<html>Closing this window stops LCForge.<br>Your work is saved as you type.</html>");
            note.setFont(note.getFont().deriveFont(note.getFont().getSize2D() - 1f));
            note.setForeground(UIManager.getColor("Label.disabledForeground"));

            JButton openBtn = new JButton("Open LCForge");
            openBtn.addActionListener(e -> browse(url));
            JButton folderBtn = new JButton("Data folder");
            folderBtn.addActionListener(e -> openFolder(home));
            JButton quitBtn = new JButton("Quit");
            quitBtn.addActionListener(e -> quit(server));

            JPanel text = new JPanel();
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.setOpaque(false);
            for (JComponent c : new JComponent[]{ title, where }) {
                c.setAlignmentX(Component.LEFT_ALIGNMENT);
                text.add(c);
            }
            text.add(Box.createVerticalStrut(10));
            note.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(note);

            JPanel top = new JPanel(new BorderLayout(14, 0));
            top.setOpaque(false);
            if (!icons.isEmpty()) top.add(new JLabel(new ImageIcon(header(icons.get(icons.size() - 1), 56))), BorderLayout.WEST);
            top.add(text, BorderLayout.CENTER);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.setOpaque(false);
            buttons.add(folderBtn);
            buttons.add(quitBtn);
            buttons.add(openBtn);

            JPanel root = new JPanel(new BorderLayout(0, 16));
            root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
            root.add(top, BorderLayout.CENTER);
            root.add(buttons, BorderLayout.SOUTH);

            f.setContentPane(root);
            f.getRootPane().setDefaultButton(openBtn);
            f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            f.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) { quit(server); }
            });
            f.pack();
            f.setResizable(false);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }

    private static void quit(Server server) {
        server.close();
        System.exit(0);
    }

    /** A startup failure, said in a dialog — an installed app has no terminal to say it in. */
    static void fatal(String message) {
        try {
            SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null, message,
                "LCForge could not start", JOptionPane.ERROR_MESSAGE));
        } catch (Exception e) {
            System.err.println(message);
        }
    }

    /**
     * Open a URL in the default browser. Desktop.browse first; the OS's own opener when the JDK's
     * desktop integration is missing, which is common on Linux without GNOME libraries.
     */
    static boolean browse(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (Exception | Error ignored) { /* fall through */ }
        return osOpen(url);
    }

    private static void openFolder(Path dir) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir.toFile());
                return;
            }
        } catch (Exception | Error ignored) { /* fall through */ }
        osOpen(dir.toString());
    }

    private static boolean osOpen(String target) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd = os.contains("win") ? new String[]{ "rundll32", "url.dll,FileProtocolHandler", target }
                     : os.contains("mac") ? new String[]{ "open", target }
                     : new String[]{ "xdg-open", target };
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The app's icons, smallest first, if this build carries them (the jar and the installers do). */
    private static List<BufferedImage> icons() {
        List<BufferedImage> out = new ArrayList<>();
        for (int size : new int[]{ 16, 32, 48, 256 }) {
            try (InputStream in = Gui.class.getResourceAsStream("/icons/icon-" + size + ".png")) {
                if (in != null) out.add(ImageIO.read(in));
            } catch (IOException ignored) { /* a missing size is not worth failing over */ }
        }
        return out;
    }

    /**
     * The window's own picture of the icon, at 1x, 1.5x and 2x, so a high-DPI screen gets real
     * pixels instead of the 1x copy stretched.
     */
    private static Image header(BufferedImage src, int size) {
        return new BaseMultiResolutionImage(scaled(src, size), scaled(src, size * 3 / 2), scaled(src, size * 2));
    }

    /**
     * Downscale by halving, then one last bilinear step. A single bilinear jump from 256 to 56
     * samples only a few source pixels per output pixel and comes out jagged; halving averages them all.
     */
    private static BufferedImage scaled(BufferedImage src, int size) {
        BufferedImage img = src;
        int w = src.getWidth();
        do {
            w = Math.max(size, w / 2);
            BufferedImage next = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, w, w, null);
            g.dispose();
            img = next;
        } while (w > size);
        return img;
    }

    private Gui() {}
}
