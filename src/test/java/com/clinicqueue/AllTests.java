package com.clinicqueue;

import com.sun.net.httpserver.HttpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Dependency-free test runner: run with `./run-tests.sh`. Exits non-zero if any test fails. */
public class AllTests {

    // ------------------------------------------------------------ tiny test framework

    interface Body { void run() throws Exception; }

    static int passed = 0, failed = 0;

    static void test(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL  " + name + "  ->  " + t);
            t.printStackTrace(System.out);
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void eq(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(message + " (expected " + expected + " but was " + actual + ")");
    }

    static void throwsEx(Class<? extends Throwable> type, Body body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return;
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }

    // ------------------------------------------------------------ fixtures

    static class TestClock extends Clock {
        private Instant now = Instant.parse("2026-11-20T09:00:00Z");
        void plusMinutes(long m) { now = now.plusSeconds(m * 60); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    record Ctx(TestClock clock, MockSmsNotifier sms, WaitTimePredictor predictor, QueueService q) {}

    static Ctx ctx() {
        TestClock clock = new TestClock();
        MockSmsNotifier sms = new MockSmsNotifier(clock, false);
        WaitTimePredictor predictor = new WaitTimePredictor(ZoneOffset.UTC);
        QueueService q = new QueueService(clock, List.of(
                new Service("consultation", "General Consultation", 10, 1),
                new Service("lab", "Laboratory", 5, 2)), sms, predictor);
        return new Ctx(clock, sms, predictor, q);
    }

    static String phone(int n) { return "+23324000" + String.format("%04d", n); }

    static long countTo(MockSmsNotifier sms, String phone) {
        return sms.recent().stream().filter(s -> s.phone().equals(phone)).count();
    }

    static QueueEntry rawEntry(long id, Status status, Instant startedAt) {
        QueueEntry e = new QueueEntry(id, "CON-" + id, "P" + id, phone((int) id), "consultation",
                0, "", Instant.parse("2026-11-20T08:00:00Z"));
        e.setStatus(status);
        e.setStartedAt(startedAt);
        return e;
    }

    // ------------------------------------------------------------ predictor

    static void predictorTests() {
        test("predictor: uses default duration when there is no data", () -> {
            WaitTimePredictor p = new WaitTimePredictor(ZoneOffset.UTC);
            Service one = new Service("consultation", "C", 10, 1);
            Service two = new Service("lab", "L", 5, 2);
            Instant now = Instant.parse("2026-11-20T09:00:00Z");
            List<QueueEntry> three = List.of(rawEntry(1, Status.WAITING, null),
                    rawEntry(2, Status.WAITING, null), rawEntry(3, Status.WAITING, null));
            eq(30, p.estimateWaitMinutes(one, three, now), "3 patients x 10 min, 1 server");
            eq(8, p.estimateWaitMinutes(two, three, now), "3 x 5 min / 2 servers rounds up to 8");
            eq(0, p.estimateWaitMinutes(one, List.of(), now), "empty queue means no wait");
        });

        test("predictor: learns from real durations", () -> {
            WaitTimePredictor p = new WaitTimePredictor(ZoneOffset.UTC);
            Service s = new Service("consultation", "C", 10, 1);
            Instant t = Instant.parse("2026-11-19T10:30:00Z");
            for (int i = 0; i < 20; i++) p.record("consultation", t, 20);
            double expected = p.expectedMinutes(s, Instant.parse("2026-11-20T10:00:00Z"));
            check(expected > 18 && expected < 19, "expected about 18.7 but was " + expected);
        });

        test("predictor: busy hours are predicted slower than quiet hours", () -> {
            WaitTimePredictor p = new WaitTimePredictor(ZoneOffset.UTC);
            Service s = new Service("consultation", "C", 10, 1);
            for (int i = 0; i < 5; i++) {
                p.record("consultation", Instant.parse("2026-11-19T09:30:00Z"), 30);
                p.record("consultation", Instant.parse("2026-11-19T14:30:00Z"), 10);
            }
            double morning = p.expectedMinutes(s, Instant.parse("2026-11-20T09:00:00Z"));
            double afternoon = p.expectedMinutes(s, Instant.parse("2026-11-20T14:00:00Z"));
            check(morning > afternoon * 1.5, "morning " + morning + " should be well above afternoon " + afternoon);
            Map<Integer, Double> byHour = p.hourlyAverageMinutes("consultation");
            eq(30.0, byHour.get(9), "learned 9:00 average");
            eq(10.0, byHour.get(14), "learned 14:00 average");
        });

        test("predictor: patient already in service only counts remaining time", () -> {
            WaitTimePredictor p = new WaitTimePredictor(ZoneOffset.UTC);
            Service s = new Service("consultation", "C", 10, 1);
            Instant now = Instant.parse("2026-11-20T09:10:00Z");
            QueueEntry inService = rawEntry(1, Status.IN_SERVICE, Instant.parse("2026-11-20T09:06:00Z"));
            eq(6, p.estimateWaitMinutes(s, List.of(inService), now), "10 min expected - 4 min elapsed");
        });
    }

    // ------------------------------------------------------------ queue rules

    static void queueTests() {
        test("queue: emergency and priority patients are seen first", () -> {
            Ctx c = ctx();
            c.q.join("A Normal", phone(1), "consultation", QueueEntry.NORMAL, null);
            c.q.join("B Normal", phone(2), "consultation", QueueEntry.NORMAL, null);
            c.q.join("C Emergency", phone(3), "consultation", QueueEntry.EMERGENCY, "Chest pain");
            c.q.join("D Elderly", phone(4), "consultation", QueueEntry.PRIORITY, "Elderly");
            List<String> order = new ArrayList<>();
            for (QueueService.EntryView v : c.q.queueFor("consultation")) order.add(v.entry().patientName());
            eq(List.of("C Emergency", "D Elderly", "A Normal", "B Normal"), order, "queue order");
        });

        test("queue: position, people ahead and wait estimate", () -> {
            Ctx c = ctx();
            c.q.join("A", phone(1), "consultation", QueueEntry.NORMAL, null);
            c.q.join("B", phone(2), "consultation", QueueEntry.NORMAL, null);
            QueueEntry third = c.q.join("C", phone(3), "consultation", QueueEntry.NORMAL, null);
            QueueService.EntryView v = c.q.get(third.id());
            eq(3, v.position(), "position");
            eq(2, v.peopleAhead(), "people ahead");
            eq(20, v.estimatedWaitMinutes(), "2 patients x 10 min");
        });

        test("queue: providers can only call patients who checked in", () -> {
            Ctx c = ctx();
            QueueEntry remote = c.q.join("Remote", phone(1), "consultation", QueueEntry.NORMAL, null);
            throwsEx(IllegalStateException.class, () -> c.q.callNext("consultation"));
            c.q.markArrived(remote.id());
            eq(remote.id(), c.q.callNext("consultation").id(), "the earlier checked-in ticket is called first");
            eq(Status.CALLED, remote.status(), "actual call changes the queue status");
        });

        test("queue: arrival reminders are sent once at the one-hour threshold", () -> {
            Ctx c = ctx();
            List<QueueEntry> ahead = new ArrayList<>();
            for (int i = 1; i <= 8; i++)
                ahead.add(c.q.join("P" + i, phone(i), "consultation", QueueEntry.NORMAL, null));
            QueueEntry target = ahead.get(7);
            eq(null, target.arrivalReminderSentAt(), "70-minute wait is not yet due");
            eq(1L, countTo(c.sms, phone(8)), "target only got the join confirmation");
            c.q.cancel(ahead.get(0).id());
            check(target.arrivalReminderSentAt() != null, "reminder sent after wait fell to 60 minutes");
            eq(2L, countTo(c.sms, phone(8)), "join confirmation plus one arrival reminder");
            c.clock.plusMinutes(5);
            eq(0, c.q.dispatchDueArrivalReminders(), "a sent reminder is never repeated");
            eq(2L, countTo(c.sms, phone(8)), "no duplicate after scheduler tick");
        });

        test("queue: nurse walk-ins may omit a phone and are already arrived", () -> {
            Ctx c = ctx();
            QueueEntry walkIn = c.q.joinWalkIn("Walk In", "", "lab", QueueEntry.NORMAL, null);
            check(walkIn.hasArrived(), "walk-in is checked in at registration");
            eq(0L, c.sms.recent().stream().filter(s -> s.phone().isEmpty()).count(),
                    "no SMS is sent without a phone number");
            eq(walkIn.id(), c.q.callNext("lab").id(), "provider can call the checked-in walk-in");
        });

        test("queue: a manual arrival reminder is one-time and cannot be sent after check-in", () -> {
            Ctx c = ctx();
            for (int i = 1; i <= 8; i++)
                c.q.join("Ahead " + i, phone(i), "consultation", QueueEntry.NORMAL, null);
            QueueEntry patient = c.q.join("Manual", phone(9), "consultation", QueueEntry.NORMAL, null);
            c.q.sendArrivalReminder(patient.id());
            long patientId = patient.id();
            throwsEx(IllegalStateException.class, () -> c.q.sendArrivalReminder(patientId));
            c.q.markArrived(patientId);
            throwsEx(IllegalStateException.class, () -> c.q.sendArrivalReminder(patientId));
        });

        test("queue: finishing a visit teaches the predictor", () -> {
            Ctx c = ctx();
            QueueEntry e = c.q.join("A", phone(1), "consultation", QueueEntry.NORMAL, null);
            c.q.markArrived(e.id());
            c.q.callNext("consultation");
            c.q.startService(e.id());
            c.clock.plusMinutes(15);
            c.q.finish(e.id());
            eq(Status.DONE, e.status(), "status");
            eq(1, c.predictor.sampleCount("consultation"), "samples recorded");
            double expected = c.predictor.expectedMinutes(c.q.service("consultation"), c.clock.instant());
            check(expected > 10, "estimate should move above the 10 min default but was " + expected);
        });

        test("queue: called patients who never show up are released after the grace period", () -> {
            Ctx c = ctx();
            QueueEntry a = c.q.join("A", phone(1), "consultation", QueueEntry.NORMAL, null);
            QueueEntry b = c.q.join("B", phone(2), "consultation", QueueEntry.NORMAL, null);
            c.q.markArrived(a.id());
            c.q.markArrived(b.id());
            c.q.callNext("consultation");
            c.clock.plusMinutes(9);
            eq(0, c.q.sweepNoShows(), "still inside the grace period");
            c.clock.plusMinutes(2);
            eq(1, c.q.sweepNoShows(), "released after 10+ minutes");
            eq(Status.NO_SHOW, a.status(), "A status");
            eq(1, c.q.get(b.id()).position(), "B moves up");
        });

        test("queue: a phone number cannot hold two places in the same service", () -> {
            Ctx c = ctx();
            c.q.join("A", phone(1), "consultation", QueueEntry.NORMAL, null);
            throwsEx(IllegalStateException.class,
                    () -> c.q.join("A again", phone(1), "consultation", QueueEntry.NORMAL, null));
            c.q.join("A at the lab", phone(1), "lab", QueueEntry.NORMAL, null); // different service is fine
        });

        test("queue: a patient can rejoin after leaving", () -> {
            Ctx c = ctx();
            QueueEntry a = c.q.join("A", phone(1), "consultation", QueueEntry.NORMAL, null);
            c.q.cancel(a.id());
            c.q.join("A", phone(1), "consultation", QueueEntry.NORMAL, null);
        });

        test("queue: bad input is rejected", () -> {
            Ctx c = ctx();
            throwsEx(IllegalArgumentException.class,
                    () -> c.q.join(" ", phone(1), "consultation", QueueEntry.NORMAL, null));
            throwsEx(IllegalArgumentException.class,
                    () -> c.q.join("A", "abc", "consultation", QueueEntry.NORMAL, null));
            throwsEx(IllegalArgumentException.class,
                    () -> c.q.join("A", phone(1), "consultation", QueueEntry.EMERGENCY, " "));
            throwsEx(IllegalArgumentException.class,
                    () -> c.q.join("A", phone(1), "consultation", 7, "x"));
            throwsEx(NoSuchElementException.class,
                    () -> c.q.join("A", phone(1), "dentist", QueueEntry.NORMAL, null));
        });

        test("queue: actions are only allowed in the right state", () -> {
            Ctx c = ctx();
            QueueEntry a = c.q.join("A", phone(1), "consultation", QueueEntry.NORMAL, null);
            throwsEx(IllegalStateException.class, () -> c.q.startService(a.id()));
            throwsEx(IllegalStateException.class, () -> c.q.finish(a.id()));
            throwsEx(IllegalStateException.class, () -> c.q.markNoShow(a.id()));
            throwsEx(NoSuchElementException.class, () -> c.q.get(999));
            throwsEx(NoSuchElementException.class, () -> c.q.callNext("lab")); // nobody waiting there
        });

        test("notifications: automatic arrival reminder is sent for a short estimated wait", () -> {
            Ctx c = ctx();
            QueueEntry patient = c.q.join("P1", phone(1), "consultation", QueueEntry.NORMAL, null);
            check(patient.arrivalReminderSentAt() != null, "zero-minute estimated wait is due immediately");
            eq(2L, countTo(c.sms, phone(1)), "join confirmation plus automatic arrival reminder");
            eq(0, c.q.dispatchDueArrivalReminders(), "the scheduler does not repeat the reminder");
        });

        test("stats: counts and average wait", () -> {
            Ctx c = ctx();
            QueueEntry a = c.q.join("A", phone(1), "consultation", QueueEntry.NORMAL, null);
            QueueEntry b = c.q.join("B", phone(2), "consultation", QueueEntry.NORMAL, null);
            c.q.markArrived(a.id());
            c.q.markArrived(b.id());
            c.clock.plusMinutes(10);
            c.q.callNext("consultation");
            c.q.startService(a.id());
            c.clock.plusMinutes(5);
            c.q.finish(a.id());
            c.q.cancel(b.id());
            Map<String, Object> s = c.q.stats();
            eq(1, s.get("served"), "served");
            eq(1, s.get("cancelled"), "cancelled");
            eq(10.0, s.get("avgWaitMinutes"), "average wait");
        });
    }

    // ------------------------------------------------------------ JSON

    static void jsonTests() {
        test("json: writes nested maps, lists, escapes and enums", () -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("a", "he said \"hi\"\n");
            m.put("b", List.of(1, 2.5, true));
            m.put("c", Status.WAITING);
            m.put("d", null);
            eq("{\"a\":\"he said \\\"hi\\\"\\n\",\"b\":[1,2.5,true],\"c\":\"WAITING\",\"d\":null}",
                    Json.toJson(m), "json output");
        });

        test("json: parses flat objects and rejects garbage", () -> {
            Map<String, String> m = Json.parseObject(" {\"name\": \"Ama \\\"A\\\" S\", \"count\": 5, \"x\": null} ");
            eq("Ama \"A\" S", m.get("name"), "string with escapes");
            eq("5", m.get("count"), "number kept as text");
            check(m.containsKey("x") && m.get("x") == null, "null value");
            eq(0, Json.parseObject("").size(), "empty body");
            throwsEx(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":"));
            throwsEx(IllegalArgumentException.class, () -> Json.parseObject("not json"));
            throwsEx(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":[1,2]}"));
        });
    }

    // ------------------------------------------------------------ HTTP API, end to end

    record Resp(int status, String body) {}

    static Resp call(HttpClient client, String method, String url, String body, String pin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        if (pin != null) b.header("X-Staff-Pin", pin);
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body());
    }

    static void httpTests() throws Exception {
        TestClock unused = new TestClock();
        AdjustableClock clock = new AdjustableClock(ZoneOffset.UTC);
        MockSmsNotifier sms = new MockSmsNotifier(clock, false);
        WaitTimePredictor predictor = new WaitTimePredictor(ZoneOffset.UTC);
        QueueService q = new QueueService(clock, DemoData.defaultServices(), sms, predictor);
        HttpServer http = new Server(q, sms, clock, "9876").start(0);
        String base = "http://127.0.0.1:" + http.getAddress().getPort();
        HttpClient client = HttpClient.newHttpClient();

        try {
            test("http: patient joins, is called and sees their status", () -> {
                Resp services = call(client, "GET", base + "/api/services", null, null);
                eq(200, services.status(), "services status");
                check(services.body().contains("\"consultation\""), "services lists consultation");

                Resp join = call(client, "POST", base + "/api/queue/join",
                        "{\"name\":\"Ama Test\",\"phone\":\"0241234567\",\"serviceId\":\"consultation\",\"priority\":2,\"reason\":\"fake\"}", null);
                eq(201, join.status(), "join status");
                Map<String, String> ticket = Json.parseObject(extractFlat(join.body()));
                String id = ticket.get("id");
                check(!join.body().contains("Ama Test") && !join.body().contains("0241234567"),
                        "patient view must not expose name or phone");

                // The patient tried to self-declare an emergency: staff view must show normal priority.
                Resp staffView = call(client, "GET", base + "/api/staff/queue/consultation", null, "9876");
                eq(200, staffView.status(), "staff queue status");
                check(staffView.body().contains("\"priority\":0"), "self-declared priority must be ignored");

                eq(409, call(client, "POST", base + "/api/staff/call-next/consultation", "", "9876").status(),
                        "provider cannot call a patient before check-in");
                eq(200, call(client, "POST", base + "/api/queue/" + id + "/arrive", "", null).status(),
                        "patient can check in");
                Resp next = call(client, "POST", base + "/api/staff/call-next/consultation", "", "9876");
                eq(200, next.status(), "call next status");
                Resp mine = call(client, "GET", base + "/api/queue/" + id, null, null);
                check(mine.body().contains("\"status\":\"CALLED\""), "patient sees CALLED but got " + mine.body());
            });

            test("http: staff endpoints need the PIN", () -> {
                eq(401, call(client, "GET", base + "/api/staff/stats", null, null).status(), "no pin");
                eq(401, call(client, "GET", base + "/api/staff/stats", null, "0000").status(), "wrong pin");
                eq(401, call(client, "POST", base + "/api/demo/reset", "", null).status(), "demo needs pin");
                eq(200, call(client, "GET", base + "/api/staff/stats", null, "9876").status(), "right pin");
            });

            test("http: errors map to the right status codes", () -> {
                eq(400, call(client, "POST", base + "/api/queue/join", "{\"name\":\"\",\"phone\":\"1\"}", null).status(), "bad input");
                eq(400, call(client, "POST", base + "/api/queue/join", "garbage", null).status(), "bad json");
                eq(404, call(client, "GET", base + "/api/queue/99999", null, null).status(), "unknown ticket");
                eq(404, call(client, "GET", base + "/api/nothing", null, null).status(), "unknown route");
                String dup = "{\"name\":\"Dup\",\"phone\":\"0249990000\",\"serviceId\":\"lab\"}";
                eq(201, call(client, "POST", base + "/api/queue/join", dup, null).status(), "first join");
                eq(409, call(client, "POST", base + "/api/queue/join", dup, null).status(),
                        "duplicate phone in the same service");
            });

            test("http: web pages are served and path traversal is blocked", () -> {
                Resp home = call(client, "GET", base + "/", null, null);
                eq(200, home.status(), "home");
                check(home.body().contains("ClinicQueue"), "home page content");
                check(home.body().contains("Staff or nurse sign in") && home.body().contains("Join the queue"),
                        "start screen offers both options");
                eq(200, call(client, "GET", base + "/staff", null, null).status(), "staff page");
                Resp join = call(client, "GET", base + "/join", null, null);
                eq(200, join.status(), "patient join page");
                check(join.body().contains("id=\"join-form\""), "join page has the join form");
                eq(200, call(client, "GET", base + "/style.css", null, null).status(), "css");
                eq(404, call(client, "GET", base + "/..%2F..%2Fetc%2Fpasswd", null, null).status(), "traversal");
                eq(404, call(client, "GET", base + "/nope.html", null, null).status(), "missing file");
            });

            test("http: demo fast-forward moves the clock and learns durations", () -> {
                Resp join = call(client, "POST", base + "/api/queue/join",
                        "{\"name\":\"Kojo Demo\",\"phone\":\"0241112222\",\"serviceId\":\"pharmacy\"}", null);
                String id = Json.parseObject(extractFlat(join.body())).get("id");
                eq(200, call(client, "POST", base + "/api/queue/" + id + "/arrive", "", null).status(), "check in");
                call(client, "POST", base + "/api/staff/call-next/pharmacy", "", "9876");
                eq(200, call(client, "POST", base + "/api/staff/entries/" + id + "/start", "", "9876").status(), "start");
                eq(200, call(client, "POST", base + "/api/demo/advance", "{\"minutes\":12}", "9876").status(), "advance");
                eq(200, call(client, "POST", base + "/api/staff/entries/" + id + "/finish", "", "9876").status(), "finish");
                Resp stats = call(client, "GET", base + "/api/staff/stats", null, "9876");
                check(stats.body().contains("\"served\":2") || stats.body().contains("\"served\":1"), "served counted");
                eq(1, predictor.sampleCount("pharmacy"), "pharmacy learned one real duration");
            });

            test("http: /api/lan answers this computer with an address for phones (or null offline)", () -> {
                Resp r = call(client, "GET", base + "/api/lan", null, null);
                eq(200, r.status(), "lan status");
                Map<String, String> m = Json.parseObject(r.body());
                check(m.containsKey("url"), "lan response has a url field but was " + r.body());
                String url = m.get("url");
                check(url == null || url.matches("http://\\d+\\.\\d+\\.\\d+\\.\\d+:" + http.getAddress().getPort() + "/"),
                        "lan url looks like http://a.b.c.d:port/ but was " + url);
            });
        } finally {
            http.stop(0);
        }
    }

    static void roleAccessTests() throws Exception {
        AdjustableClock appClock = new AdjustableClock(ZoneOffset.UTC);
        MockSmsNotifier sms = new MockSmsNotifier(appClock, false);
        QueueService queue = new QueueService(appClock, DemoData.defaultServices(), sms,
                new WaitTimePredictor(ZoneOffset.UTC));
        StaffAccess access = StaffAccess.fromEnvironment(Map.of(
                "CONSULTATION_PIN", "1111",
                "LAB_PIN", "2222",
                "PHARMACY_PIN", "3333",
                "NURSE_PIN", "4444"), "9999");
        HttpServer http = new Server(queue, sms, appClock, access).start(0);
        String base = "http://127.0.0.1:" + http.getAddress().getPort();
        HttpClient client = HttpClient.newHttpClient();

        try {
            test("roles: provider PIN is limited to its service and nurse can only register", () -> {
                Resp session = call(client, "GET", base + "/api/staff/session", null, "1111");
                check(session.body().contains("\"role\":\"provider\"")
                        && session.body().contains("\"serviceId\":\"consultation\""),
                        "provider session identifies its service");
                eq(403, call(client, "GET", base + "/api/staff/queue/lab", null, "1111").status(),
                        "provider cannot access another service");
                eq(403, call(client, "POST", base + "/api/staff/call-next/lab", "", "1111").status(),
                        "provider cannot call another service");
                eq(403, call(client, "GET", base + "/api/staff/stats", null, "4444").status(),
                        "nurse cannot access provider statistics");
                eq(403, call(client, "GET", base + "/api/staff/queue/lab", null, "4444").status(),
                        "nurse cannot access provider queue");

                Resp created = call(client, "POST", base + "/api/nurse/walkins",
                        "{\"name\":\"Walk-in Lab\",\"phone\":\"\",\"serviceId\":\"lab\"}", "4444");
                eq(201, created.status(), "nurse can register a phone-less walk-in");
                check(created.body().contains("\"arrivedAt\":") && !created.body().contains("\"arrivedAt\":null"),
                        "walk-in is already checked in");
                String id = Json.parseObject(extractFlat(created.body())).get("id");
                Resp providerStats = call(client, "GET", base + "/api/staff/stats", null, "1111");
                check(providerStats.body().contains("\"serviceId\":\"consultation\"")
                        && !providerStats.body().contains("\"serviceId\":\"lab\""),
                        "provider statistics are limited to its service");
                eq(200, call(client, "POST", base + "/api/staff/call-next/lab", "", "2222").status(),
                        "lab provider can call the registered patient");
                eq(403, call(client, "POST", base + "/api/staff/entries/" + id + "/start", "", "4444").status(),
                        "nurse cannot start a service");
            });
        } finally {
            http.stop(0);
        }

        test("roles: department PINs must be complete and unique", () -> {
            throwsEx(IllegalArgumentException.class,
                    () -> StaffAccess.fromEnvironment(Map.of("CONSULTATION_PIN", "1111"), "9999"));
            throwsEx(IllegalArgumentException.class, () -> StaffAccess.fromEnvironment(Map.of(
                    "CONSULTATION_PIN", "1111", "LAB_PIN", "1111",
                    "PHARMACY_PIN", "3333", "NURSE_PIN", "4444"), "9999"));
        });
    }

    // ------------------------------------------------------------ desktop window

    static void desktopTests() {
        java.nio.file.Path edge86 = java.nio.file.Path.of("C:/Program Files (x86)", "Microsoft", "Edge", "Application", "msedge.exe");
        java.nio.file.Path chrome = java.nio.file.Path.of("C:/Program Files", "Google", "Chrome", "Application", "chrome.exe");
        java.util.function.Function<String, String> env = k -> switch (k) {
            case "ProgramFiles(x86)" -> "C:/Program Files (x86)";
            case "ProgramFiles" -> "C:/Program Files";
            case "LOCALAPPDATA" -> "C:/Users/Ama/AppData/Local";
            default -> null;
        };

        test("desktop: Edge is preferred, Chrome is the fallback, nothing found gives null", () -> {
            eq(edge86, DesktopWindow.findAppBrowser(env, p -> true), "Edge wins when both exist");
            eq(chrome, DesktopWindow.findAppBrowser(env, p -> p.equals(chrome)), "Chrome when Edge is missing");
            eq(null, DesktopWindow.findAppBrowser(env, p -> false), "null when there is no browser");
        });

        test("desktop: CLINICQUEUE_BROWSER overrides the search, and a wrong path means no window", () -> {
            java.nio.file.Path mine = java.nio.file.Path.of("D:/tools/browser.exe");
            java.util.function.Function<String, String> withOverride = k -> k.equals("CLINICQUEUE_BROWSER") ? "D:/tools/browser.exe" : env.apply(k);
            eq(mine, DesktopWindow.findAppBrowser(withOverride, p -> true), "override used");
            eq(null, DesktopWindow.findAppBrowser(withOverride, p -> false), "missing override is not silently replaced");
        });

        test("desktop: the JavaFX window keeps its page data in its own folder", () -> {
            java.nio.file.Path web = DesktopWindow.webViewDir(env);
            check(web.toString().replace('\\', '/').endsWith("AppData/Local/ClinicQueue/webview"),
                    "webview folder lives under LOCALAPPDATA but was " + web);
            check(!web.equals(DesktopWindow.profileDir(env)), "kept apart from the Edge/Chrome profile");
            byte[] png = DesktopWindow.iconPng();
            check(png.length > 100 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G', "icon is a PNG");
        });

        test("desktop: the window command is an app window with its own profile", () -> {
            java.nio.file.Path profile = DesktopWindow.profileDir(env);
            check(profile.toString().replace('\\', '/').endsWith("AppData/Local/ClinicQueue/app-window"),
                    "profile lives under LOCALAPPDATA but was " + profile);
            java.util.List<String> cmd = DesktopWindow.appCommand(edge86, "http://localhost:8080/", profile);
            eq(edge86.toString(), cmd.get(0), "program first");
            check(cmd.contains("--app=http://localhost:8080/"), "uses app mode (no tabs, no address bar)");
            check(cmd.contains("--user-data-dir=" + profile), "uses its own profile so the process stays until the window closes");
            check(cmd.stream().noneMatch(a -> a.equals("http://localhost:8080/")), "url is only passed through --app");
        });
    }

    /** Pulls the top-level scalar fields out of a flat-ish JSON object by dropping nested parts. */
    static String extractFlat(String json) {
        // Patient JSON is already flat: {"id":1,"ticket":"CON-001",...}
        return json;
    }

    // ------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        predictorTests();
        queueTests();
        jsonTests();
        httpTests();
        roleAccessTests();
        desktopTests();
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
