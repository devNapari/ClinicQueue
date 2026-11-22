package com.clinicqueue;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;

/** Sample clinic, learned history and fake patients so the app is demo-ready on first launch. */
public final class DemoData {

    private static final String[] FIRST = {"Kwame", "Ama", "Kofi", "Abena", "Yaw", "Esi", "Kojo",
            "Akosua", "Kwesi", "Efua", "Nana", "Adjoa", "Fiifi", "Maame"};
    private static final String[] LAST = {"Mensah", "Owusu", "Boateng", "Asante", "Quaye", "Appiah",
            "Darko", "Addo", "Tetteh", "Amoah", "Agyeman", "Ofori"};

    private DemoData() {}

    public static List<Service> defaultServices() {
        return List.of(
                new Service("consultation", "General Consultation", 12, 2),
                new Service("lab", "Laboratory", 8, 1),
                new Service("pharmacy", "Pharmacy", 5, 1));
    }

    /** Learned history (mornings slower than afternoons) plus a realistic live queue. */
    public static void seed(QueueService queue, MockSmsNotifier sms, Clock clock) {
        ZoneId zone = clock.getZone();
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        Random rnd = new Random(42);

        for (Service s : queue.services()) {
            for (int k = 0; k < 45; k++) {
                int hour = 8 + (k % 9);
                double factor = hour < 10 ? 1.3 : hour < 13 ? 1.0 : 0.85;
                double minutes = s.defaultMinutes() * factor * (0.85 + rnd.nextDouble() * 0.3);
                var when = today.minusDays(1 + k / 9).atTime(hour, 30).atZone(zone).toInstant();
                queue.predictor().record(s.id(), when, minutes);
            }
        }

        queue.join("Kwame Mensah", "+233241000001", "consultation", QueueEntry.NORMAL, null);
        queue.join("Ama Serwaa", "+233241000002", "consultation", QueueEntry.PRIORITY, "Pregnant");
        queue.join("Kofi Boateng", "+233241000003", "consultation", QueueEntry.NORMAL, null);
        queue.join("Abena Owusu", "+233241000004", "consultation", QueueEntry.NORMAL, null);
        queue.join("Yaw Asante", "+233241000005", "consultation", QueueEntry.NORMAL, null);
        queue.join("Esi Quaye", "+233241000006", "consultation", QueueEntry.NORMAL, null);

        // Ama (priority) is seen first: call her and start the consultation.
        QueueEntry first = queue.callNext("consultation");
        queue.startService(first.id());

        queue.join("Kojo Darko", "+233241000007", "lab", QueueEntry.NORMAL, null);
        queue.join("Akosua Addo", "+233241000008", "lab", QueueEntry.NORMAL, null);
        queue.join("Kwesi Tetteh", "+233241000009", "pharmacy", QueueEntry.NORMAL, null);

        sms.clear();
    }

    /** Adds {@code count} random walk-in patients to a service. Returns how many joined. */
    public static int rush(QueueService queue, String serviceId, int count) {
        Random rnd = new Random();
        int joined = 0;
        for (int i = 0; i < count; i++) {
            String name = FIRST[rnd.nextInt(FIRST.length)] + " " + LAST[rnd.nextInt(LAST.length)];
            String phone = "+23324" + String.format("%07d", rnd.nextInt(10_000_000));
            boolean elderly = rnd.nextInt(8) == 0;
            try {
                queue.join(name, phone, serviceId,
                        elderly ? QueueEntry.PRIORITY : QueueEntry.NORMAL,
                        elderly ? "Elderly (70+)" : null);
                joined++;
            } catch (IllegalStateException duplicatePhone) {
                // extremely unlikely collision; skip
            }
        }
        return joined;
    }
}
