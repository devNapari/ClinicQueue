package com.clinicqueue;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The desktop side of ClinicQueue (started with --desktop). It tries, in this order:
 *
 *  1. A native window with the pages shown by JavaFX's WebView ({@code FxWindow}). This is what the
 *     installed Windows program uses; it needs no browser. It is only present in the desktop build.
 *  2. Microsoft Edge (part of Windows 10/11) or Chrome started in "app mode": its own window with no
 *     tabs and no address bar. Closing it quits ClinicQueue.
 *  3. The older small control window (addresses, open browser, quit), using the default browser.
 *
 * With no display (or in tests) it just prints the addresses.
 */
final class DesktopWindow {

    private DesktopWindow() {}

    /** True if a ClinicQueue is already answering on this port (so we should not start a second one). */
    static boolean alreadyRunning(int port) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/api/services")
                    .toURL().openConnection();
            c.setConnectTimeout(400);
            c.setReadTimeout(800);
            if (c.getResponseCode() != 200) return false;
            String body = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return body.contains("\"estimatedWaitMinutes\"");
        } catch (Exception e) {
            return false;
        }
    }

    static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // fall through to the printed address
        }
        System.out.println("Open " + url + " in your browser.");
    }

    /** First private IPv4 address of this computer, e.g. 192.168.1.20, or null if offline. */
    static String lanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (SocketException ignored) {
            // treat as offline
        }
        return null;
    }

    // ------------------------------------------------------------------ own window (app mode)

    /** A window that closes faster than this is assumed to have been handed to an already-running browser. */
    static final long MIN_WINDOW_MILLIS = 3000;

    /**
     * Finds a browser that can show ClinicQueue as an app window: Edge first, then Chrome. Setting the
     * environment variable CLINICQUEUE_BROWSER to a program's full path overrides the search.
     * Returns null if none is found.
     */
    static Path findAppBrowser(Function<String, String> env, Predicate<Path> exists) {
        String override = env.apply("CLINICQUEUE_BROWSER");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override.strip());
            return exists.test(p) ? p : null;
        }
        List<Path> candidates = new ArrayList<>();
        for (String var : List.of("ProgramFiles(x86)", "ProgramFiles")) {
            String base = env.apply(var);
            if (base != null && !base.isBlank()) candidates.add(Path.of(base, "Microsoft", "Edge", "Application", "msedge.exe"));
        }
        for (String var : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
            String base = env.apply(var);
            if (base != null && !base.isBlank()) candidates.add(Path.of(base, "Google", "Chrome", "Application", "chrome.exe"));
        }
        for (Path c : candidates) if (exists.test(c)) return c;
        return null;
    }

    private static Path dataDir(Function<String, String> env, String name) {
        String local = env.apply("LOCALAPPDATA");
        Path base = (local == null || local.isBlank()) ? Path.of(System.getProperty("java.io.tmpdir")) : Path.of(local);
        return base.resolve("ClinicQueue").resolve(name);
    }

    /** Where the Edge/Chrome app window keeps its own settings, kept apart from the person's everyday browser profile. */
    static Path profileDir(Function<String, String> env) {
        return dataDir(env, "app-window");
    }

    /** Where the JavaFX window keeps what the pages store (for example a patient's ticket). */
    static Path webViewDir(Function<String, String> env) {
        return dataDir(env, "webview");
    }

    // ------------------------------------------------------------------ JavaFX window

    /**
     * Shows the JavaFX window if this build has it and JavaFX can start. Looked up by name so the plain
     * JDK-only build (no JavaFX) still compiles and runs. Returns false if it is not available.
     */
    private static boolean openJavaFx(String url, boolean ownsServer) {
        try {
            Class<?> window = Class.forName("com.clinicqueue.FxWindow");
            window.getMethod("open", String.class, Path.class, boolean.class, Runnable.class)
                    .invoke(null, url, webViewDir(System::getenv), ownsServer, (Runnable) () -> System.exit(0));
            return true;
        } catch (ClassNotFoundException e) {
            return false; // a build without JavaFX: nothing to report
        } catch (Throwable t) {
            Throwable cause = (t instanceof InvocationTargetException ite && ite.getCause() != null) ? ite.getCause() : t;
            System.err.println("JavaFX window not available (" + cause + "). Trying Edge or Chrome instead.");
            return false;
        }
    }

    /** The mark as PNG bytes, for the JavaFX window's icon. */
    static byte[] iconPng() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(icon(), "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The command that opens url as a stand-alone window. The separate profile folder is what makes this a
     * new process that stays running until the window is closed, instead of handing over to a browser that is
     * already open and exiting at once.
     */
    static List<String> appCommand(Path browser, String url, Path profile) {
        return List.of(browser.toString(),
                "--app=" + url,
                "--user-data-dir=" + profile,
                "--window-size=1280,860",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-background-mode");
    }

    /** Opens the window without waiting for it. Returns false if it could not be started. */
    private static boolean startAppWindow(String url) {
        Path browser = findAppBrowser(System::getenv, Files::exists);
        if (browser == null) return false;
        try {
            new ProcessBuilder(appCommand(browser, url, profileDir(System::getenv))).start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Opens ClinicQueue in its own window if possible, otherwise in the default browser. */
    static void openApp(String url) {
        if (GraphicsEnvironment.isHeadless()) {
            openBrowser(url);
            return;
        }
        // This copy does not run the server (another one already does), so closing the window just ends this copy.
        if (openJavaFx(url, false)) return;
        if (!startAppWindow(url)) openBrowser(url);
    }

    /**
     * Opens the window and waits until the person closes it. Returns true if it stayed open long enough to
     * be a real window that was then closed; false if it could not start or vanished at once.
     */
    private static boolean runAppWindow(String url) {
        Path browser = findAppBrowser(System::getenv, Files::exists);
        if (browser == null) return false;
        try {
            Process p = new ProcessBuilder(appCommand(browser, url, profileDir(System::getenv))).start();
            long started = System.nanoTime();
            p.waitFor();
            return (System.nanoTime() - started) / 1_000_000 >= MIN_WINDOW_MILLIS;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    static void show(int port, boolean defaultPin) {
        String local = "http://localhost:" + port + "/";
        String lan = lanAddress();
        String lanUrl = lan == null ? null : "http://" + lan + ":" + port + "/";

        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("On this computer: " + local);
            System.out.println(lanUrl == null ? "No network connection found for phones."
                    : "Phones on the same Wi-Fi: " + lanUrl);
            return;
        }
        if (openJavaFx(local, true)) return;
        if (findAppBrowser(System::getenv, Files::exists) == null) {
            showControlWindow(local, lanUrl, defaultPin);
            return;
        }
        // Own window. Closing it quits ClinicQueue. This thread is not a daemon, so it also keeps the program alive.
        Thread t = new Thread(() -> {
            if (runAppWindow(local)) {
                System.exit(0);
            }
            // The window could not be shown: keep the server running and fall back to the control window.
            showControlWindow(local, lanUrl, defaultPin);
        }, "app-window");
        t.start();
    }

    private static void showControlWindow(String local, String lanUrl, boolean defaultPin) {
        SwingUtilities.invokeLater(() -> build(local, lanUrl, defaultPin));
        openBrowser(local);
    }

    private static void build(String local, String lanUrl, boolean defaultPin) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // keep the default look
        }

        JFrame frame = new JFrame("ClinicQueue");
        frame.setIconImage(icon());
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        JLabel title = new JLabel("ClinicQueue is running");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        box.add(left(title));
        box.add(Box.createVerticalStrut(6));
        box.add(left(wrapped("Keep this window open while the clinic is open. Closing it stops ClinicQueue.")));
        box.add(Box.createVerticalStrut(16));

        box.add(left(bold("On this computer")));
        box.add(left(urlField(local)));
        box.add(Box.createVerticalStrut(12));

        box.add(left(bold("On phones and other computers on the same Wi-Fi")));
        box.add(left(lanUrl == null ? wrapped("No network connection found.") : urlField(lanUrl)));
        box.add(Box.createVerticalStrut(12));

        if (defaultPin) {
            box.add(left(wrapped("Staff PIN: 1234. To change it, save a new PIN in a text file named staff-pin.txt "
                    + "in the ClinicQueue folder, then start ClinicQueue again.")));
            box.add(Box.createVerticalStrut(8));
        }
        box.add(left(wrapped("If Windows asks about the firewall, choose Allow on private networks so phones can connect.")));
        box.add(Box.createVerticalStrut(18));

        JButton open = new JButton("Open ClinicQueue");
        open.addActionListener(e -> openBrowser(local));

        String shareUrl = lanUrl != null ? lanUrl : local;
        JButton copy = new JButton(lanUrl != null ? "Copy link for phones" : "Copy link");
        String copyLabel = copy.getText();
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(shareUrl), null);
            copy.setText("Copied");
            Timer t = new Timer(1500, ev -> copy.setText(copyLabel));
            t.setRepeats(false);
            t.start();
        });

        JButton quit = new JButton("Quit");
        quit.addActionListener(e -> confirmQuit(frame));

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(open);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(copy);
        buttons.add(Box.createHorizontalGlue());
        buttons.add(quit);
        box.add(left(buttons));

        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { confirmQuit(frame); }
        });

        frame.setContentPane(box);
        frame.pack();
        frame.setMinimumSize(frame.getSize());
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void confirmQuit(JFrame frame) {
        int answer = JOptionPane.showConfirmDialog(frame,
                "Quit ClinicQueue? Patients will not be able to reach it until you start it again.",
                "Quit ClinicQueue", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer == JOptionPane.YES_OPTION) System.exit(0);
    }

    private static JLabel bold(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private static JLabel wrapped(String text) {
        return new JLabel("<html><body style='width:360px'>" + text + "</body></html>");
    }

    private static JTextField urlField(String url) {
        JTextField f = new JTextField(url, 28);
        f.setEditable(false);
        f.setMaximumSize(f.getPreferredSize());
        return f;
    }

    private static <T extends javax.swing.JComponent> T left(T c) {
        c.setAlignmentX(0f);
        return c;
    }

    /** The three-dot mark (two grey, one amber) drawn in code, so no image file is needed. */
    private static BufferedImage icon() {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x10262b));
        g.fillRoundRect(0, 0, 64, 64, 14, 14);
        g.setColor(new Color(0x8fa3a6));
        g.fillOval(7, 25, 14, 14);
        g.fillOval(25, 25, 14, 14);
        g.setColor(new Color(0xf2b705));
        g.fillOval(43, 25, 14, 14);
        g.dispose();
        return img;
    }
}
