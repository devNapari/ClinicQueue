package com.clinicqueue;

import java.net.BindException;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Starts ClinicQueue.
 *
 * Options:  --desktop   open the app in its own window (Edge/Chrome app mode, no browser tabs), pick a free port if needed
 *           --no-demo   start with an empty queue (no sample patients)
 *           --port=N    port to listen on (default 8080)
 * Environment: PORT, STAFF_PIN (legacy demo default 1234), role-specific *_PIN values, SEED_DEMO=false
 */
public class Main {

    /** Folders searched for staff-pin.txt: the working folder, then the folder of the app itself and its parent. */
    static java.util.List<java.nio.file.Path> pinFolders() {
        java.util.List<java.nio.file.Path> folders = new java.util.ArrayList<>();
        folders.add(java.nio.file.Path.of("").toAbsolutePath());
        try {
            java.nio.file.Path jar = java.nio.file.Path.of(
                    Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            java.nio.file.Path dir = java.nio.file.Files.isDirectory(jar) ? jar : jar.getParent();
            if (dir != null) {
                folders.add(dir);
                if (dir.getParent() != null) folders.add(dir.getParent());
            }
        } catch (Exception ignored) {
            // just use the working folder
        }
        return folders;
    }

    /** PIN from a file named staff-pin.txt (working folder, or next to the installed app), else 1234. */
    static String filePin() {
        for (java.nio.file.Path folder : pinFolders()) {
            java.nio.file.Path p = folder.resolve("staff-pin.txt");
            try {
                if (!java.nio.file.Files.isRegularFile(p)) continue;
                String pin = java.nio.file.Files.readString(p).strip();
                if (pin.length() >= 4 && pin.length() <= 32 && pin.chars().noneMatch(Character::isWhitespace)) return pin;
                System.err.println("Ignoring " + p + ": the PIN must be 4 to 32 characters with no spaces.");
            } catch (java.io.IOException | RuntimeException e) {
                System.err.println("Could not read " + p + ": " + e.getMessage());
            }
        }
        return "1234";
    }

    public static void main(String[] args) throws Exception {
        boolean desktop = false;
        boolean seed = !"false".equalsIgnoreCase(System.getenv("SEED_DEMO"));
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        for (String a : args) {
            if (a.equals("--desktop")) desktop = true;
            else if (a.equals("--no-demo")) seed = false;
            else if (a.startsWith("--port=")) port = Integer.parseInt(a.substring("--port=".length()));
            else {
                System.err.println("Unknown option: " + a);
                System.exit(2);
            }
        }
        String pin = System.getenv().getOrDefault("STAFF_PIN", filePin());
        StaffAccess staffAccess = StaffAccess.fromEnvironment(System.getenv(), pin);

        // Double-clicking the shortcut twice should not start a second copy: just open the window again.
        if (desktop && DesktopWindow.alreadyRunning(port)) {
            DesktopWindow.openApp("http://localhost:" + port + "/");
            return;
        }

        ZoneId zone = ZoneId.systemDefault();
        AdjustableClock clock = new AdjustableClock(zone);
        MockSmsNotifier sms = new MockSmsNotifier(clock, !desktop);
        WaitTimePredictor predictor = new WaitTimePredictor(zone);
        QueueService queue = new QueueService(clock, DemoData.defaultServices(), sms, predictor);
        if (seed) DemoData.seed(queue, sms, clock);

        Server server = new Server(queue, sms, clock, staffAccess);
        int bound = port;
        int attempts = desktop ? 10 : 1; // the desktop app tries the next ports if this one is taken
        for (int i = 0; i < attempts; i++) {
            try {
                server.start(port + i);
                bound = port + i;
                break;
            } catch (BindException e) {
                if (i == attempts - 1) throw e;
            }
        }

        // Every 30 seconds, release called patients who never showed up.
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "no-show-sweeper");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            queue.sweepNoShows();
            queue.dispatchDueArrivalReminders();
        }, 30, 30, TimeUnit.SECONDS);

        if (desktop) {
            DesktopWindow.show(bound, !staffAccess.hasDepartmentPins() && pin.equals("1234"));
        } else {
            System.out.println("ClinicQueue running");
            System.out.println("  Start screen: http://localhost:" + bound + "/");
            System.out.println("  Staff desk  : http://localhost:" + bound + "/staff");
            if (staffAccess.hasDepartmentPins()) {
                System.out.println("  Department PINs are configured independently.");
            } else {
                System.out.println("  Legacy shared staff PIN mode is active.");
                if (pin.equals("1234")) System.out.println("  Note: default PIN in use. Set STAFF_PIN to change it.");
            }
        }
    }
}
