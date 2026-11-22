package com.clinicqueue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;

/**
 * REST API + static web pages on the JDK's built-in HTTP server (no external dependencies).
 *
 * This computer only: GET /api/lan (the Wi-Fi address for phones)
 * Public:  GET /api/services, POST /api/queue/join, GET /api/queue/{id}, POST /api/queue/{id}/cancel
 * Staff (header X-Staff-Pin): queue view, call next, start/finish/no-show, add walk-in, stats, SMS log
 * Demo  (header X-Staff-Pin): add rush of patients, fast-forward the clock, reset
 */
public class Server {

    private static final int MAX_BODY = 64 * 1024;

    private final QueueService queue;
    private final MockSmsNotifier sms;
    private final AdjustableClock clock;
    private final String staffPin;

    public Server(QueueService queue, MockSmsNotifier sms, AdjustableClock clock, String staffPin) {
        this.queue = queue;
        this.sms = sms;
        this.clock = clock;
        this.staffPin = staffPin;
    }

    public HttpServer start(int port) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/", this::handle);
        http.setExecutor(Executors.newFixedThreadPool(8));
        http.start();
        return http;
    }

    // ------------------------------------------------------------------ dispatch

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.startsWith("/api/")) {
                route(ex, method, path);
            } else if (method.equals("GET")) {
                serveStatic(ex, path);
            } else {
                sendJson(ex, 405, error("Method not allowed"));
            }
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, error(e.getMessage()));
        } catch (SecurityException e) {
            sendJson(ex, 401, error(e.getMessage()));
        } catch (NoSuchElementException e) {
            sendJson(ex, 404, error(e.getMessage()));
        } catch (IllegalStateException e) {
            sendJson(ex, 409, error(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, error("Internal server error"));
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex, String method, String path) throws IOException {
        String[] seg = path.substring("/api/".length()).split("/");
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");

        // ---------------- this computer only: the Wi-Fi address phones should use (shown in the app window)
        if (get && is(seg, "lan")) {
            if (!ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
                throw new NoSuchElementException("Not found");
            }
            String lan = DesktopWindow.lanAddress();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("url", lan == null ? null : "http://" + lan + ":" + ex.getLocalAddress().getPort() + "/");
            sendJson(ex, 200, out);
            return;
        }

        // ---------------- public (patients)
        if (get && is(seg, "services")) {
            sendJson(ex, 200, servicesJson());
            return;
        }
        if (post && is(seg, "queue", "join")) {
            Map<String, String> b = Json.parseObject(readBody(ex));
            // Patients can never self-declare priority; only staff can (prevents queue gaming).
            QueueEntry e = queue.join(b.get("name"), b.get("phone"), b.get("serviceId"),
                    QueueEntry.NORMAL, null);
            sendJson(ex, 201, patientJson(queue.get(e.id())));
            return;
        }
        if (seg.length >= 2 && seg[0].equals("queue") && seg[1].matches("\\d{1,18}")) {
            long id = Long.parseLong(seg[1]);
            if (get && seg.length == 2) {
                sendJson(ex, 200, patientJson(queue.get(id)));
                return;
            }
            if (post && seg.length == 3 && seg[2].equals("cancel")) {
                queue.cancel(id);
                sendJson(ex, 200, patientJson(queue.get(id)));
                return;
            }
        }

        // ---------------- staff + demo (PIN protected)
        if (seg[0].equals("staff") || seg[0].equals("demo")) {
            requirePin(ex);

            if (get && is(seg, "staff", "queue", seg.length == 3 ? seg[2] : "")) {
                List<Object> rows = new ArrayList<>();
                for (QueueService.EntryView v : queue.queueFor(seg[2])) rows.add(staffJson(v));
                sendJson(ex, 200, rows);
                return;
            }
            if (post && seg.length == 3 && seg[0].equals("staff") && seg[1].equals("call-next")) {
                QueueEntry e = queue.callNext(seg[2]);
                sendJson(ex, 200, staffJson(queue.get(e.id())));
                return;
            }
            if (post && is(seg, "staff", "join")) {
                Map<String, String> b = Json.parseObject(readBody(ex));
                int priority = parseInt(b.get("priority"), QueueEntry.NORMAL, "priority");
                QueueEntry e = queue.join(b.get("name"), b.get("phone"), b.get("serviceId"),
                        priority, b.get("reason"));
                sendJson(ex, 201, staffJson(queue.get(e.id())));
                return;
            }
            if (post && seg.length == 4 && seg[0].equals("staff") && seg[1].equals("entries")
                    && seg[2].matches("\\d{1,18}")) {
                long id = Long.parseLong(seg[2]);
                QueueEntry e = switch (seg[3]) {
                    case "start" -> queue.startService(id);
                    case "finish" -> queue.finish(id);
                    case "noshow" -> queue.markNoShow(id);
                    case "cancel" -> queue.cancel(id);
                    default -> throw new NoSuchElementException("Unknown action: " + seg[3]);
                };
                sendJson(ex, 200, staffJson(queue.get(e.id())));
                return;
            }
            if (get && is(seg, "staff", "stats")) {
                sendJson(ex, 200, statsJson());
                return;
            }
            if (get && is(seg, "staff", "sms")) {
                List<Object> rows = new ArrayList<>();
                for (MockSmsNotifier.Sms s : sms.recent()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sentAt", s.sentAt());
                    m.put("phone", s.phone());
                    m.put("message", s.message());
                    rows.add(m);
                }
                sendJson(ex, 200, rows);
                return;
            }
            if (post && is(seg, "demo", "rush")) {
                Map<String, String> b = Json.parseObject(readBody(ex));
                int count = parseInt(b.get("count"), 5, "count");
                if (count < 1 || count > 20) throw new IllegalArgumentException("count must be 1-20");
                String serviceId = b.getOrDefault("serviceId", "consultation");
                queue.service(serviceId); // validates
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("joined", DemoData.rush(queue, serviceId, count));
                sendJson(ex, 200, out);
                return;
            }
            if (post && is(seg, "demo", "advance")) {
                Map<String, String> b = Json.parseObject(readBody(ex));
                int minutes = parseInt(b.get("minutes"), 5, "minutes");
                if (minutes < 1 || minutes > 120) throw new IllegalArgumentException("minutes must be 1-120");
                clock.advance(Duration.ofMinutes(minutes));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("advancedMinutes", minutes);
                out.put("noShowsMarked", queue.sweepNoShows());
                sendJson(ex, 200, out);
                return;
            }
            if (post && is(seg, "demo", "reset")) {
                clock.resetOffset();
                queue.clear();
                sms.clear();
                DemoData.seed(queue, sms, clock);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                sendJson(ex, 200, out);
                return;
            }
        }

        sendJson(ex, 404, error("Not found"));
    }

    // ------------------------------------------------------------------ JSON views

    private List<Object> servicesJson() {
        List<Object> rows = new ArrayList<>();
        for (QueueService.ServiceStatus s : queue.serviceStatuses()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.service().id());
            m.put("name", s.service().name());
            m.put("servers", s.service().servers());
            m.put("waiting", s.waiting());
            m.put("called", s.called());
            m.put("inService", s.inService());
            m.put("estimatedWaitMinutes", s.estimatedWaitNewPatient());
            m.put("expectedMinutesPerPatient", Math.round(s.expectedMinutes() * 10.0) / 10.0);
            m.put("samples", s.samples());
            rows.add(m);
        }
        return rows;
    }

    /** What a patient may see about their own ticket: no names or phone numbers. */
    private Map<String, Object> patientJson(QueueService.EntryView v) {
        QueueEntry e = v.entry();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("ticket", e.ticket());
        m.put("serviceId", e.serviceId());
        m.put("serviceName", queue.service(e.serviceId()).name());
        m.put("status", e.status());
        m.put("position", v.position());
        m.put("peopleAhead", v.peopleAhead());
        m.put("estimatedWaitMinutes", v.estimatedWaitMinutes());
        m.put("joinedAt", e.joinedAt());
        return m;
    }

    private Map<String, Object> staffJson(QueueService.EntryView v) {
        QueueEntry e = v.entry();
        Map<String, Object> m = patientJson(v);
        m.put("patientName", e.patientName());
        m.put("phone", e.phone());
        m.put("priority", e.priority());
        m.put("priorityReason", e.priorityReason());
        m.put("calledAt", e.calledAt());
        m.put("startedAt", e.startedAt());
        m.put("waitingMinutes", Duration.between(e.joinedAt(), clock.instant()).toMinutes());
        return m;
    }

    private Map<String, Object> statsJson() {
        Map<String, Object> out = new LinkedHashMap<>(queue.stats());
        List<Object> learned = new ArrayList<>();
        for (Service s : queue.services()) {
            List<Object> hours = new ArrayList<>();
            for (Map.Entry<Integer, Double> h : queue.predictor().hourlyAverageMinutes(s.id()).entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("hour", h.getKey());
                row.put("avgMinutes", h.getValue());
                hours.add(row);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("serviceId", s.id());
            m.put("serviceName", s.name());
            m.put("samples", queue.predictor().sampleCount(s.id()));
            m.put("byHour", hours);
            learned.add(m);
        }
        out.put("learned", learned);
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private void requirePin(HttpExchange ex) {
        String given = ex.getRequestHeaders().getFirst("X-Staff-Pin");
        boolean ok = given != null && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), staffPin.getBytes(StandardCharsets.UTF_8));
        if (!ok) throw new SecurityException("Staff PIN required");
    }

    private static boolean is(String[] seg, String... expected) {
        if (seg.length != expected.length) return false;
        for (int i = 0; i < seg.length; i++) {
            if (!seg[i].equals(expected[i])) return false;
        }
        return true;
    }

    private static int parseInt(String s, int dflt, String field) {
        if (s == null || s.isBlank()) return dflt;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a whole number");
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(MAX_BODY + 1);
            if (data.length > MAX_BODY) throw new IllegalArgumentException("Request too large");
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ------------------------------------------------------------------ static files

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        String name = switch (path) {
            case "/" -> "index.html";
            case "/staff" -> "staff.html";
            case "/join" -> "join.html";
            default -> path.startsWith("/") ? path.substring(1) : path;
        };
        // Flat file names only: blocks any path traversal.
        if (!name.matches("[A-Za-z0-9._-]+")) {
            sendJson(ex, 404, error("Not found"));
            return;
        }
        try (InputStream in = Server.class.getResourceAsStream("/static/" + name)) {
            if (in == null) {
                sendJson(ex, 404, error("Not found"));
                return;
            }
            send(ex, 200, contentType(name), in.readAllBytes());
        }
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}
