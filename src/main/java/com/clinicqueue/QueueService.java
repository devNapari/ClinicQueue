package com.clinicqueue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;

/**
 * All queue rules in one place: joining, priority ordering, calling the next patient,
 * no-show handling, notifications and statistics. Thread-safe (single lock).
 *
 * Errors: IllegalArgumentException = bad input, IllegalStateException = action not allowed
 * in the current state, NoSuchElementException = unknown id.
 */
public class QueueService {

    public static final int NEAR_TURN_POSITION = 3;
    public static final int NO_SHOW_GRACE_MINUTES = 10;

    public record EntryView(QueueEntry entry, int position, int peopleAhead, int estimatedWaitMinutes) {}

    public record ServiceStatus(Service service, int waiting, int called, int inService,
                                int estimatedWaitNewPatient, double expectedMinutes, int samples) {}

    private static final Comparator<QueueEntry> QUEUE_ORDER =
            Comparator.comparingInt((QueueEntry e) -> -e.priority())
                    .thenComparing(QueueEntry::joinedAt)
                    .thenComparingLong(QueueEntry::id);

    private final Clock clock;
    private final Notifier notifier;
    private final WaitTimePredictor predictor;
    private final Map<String, Service> services = new LinkedHashMap<>();
    private final Map<Long, QueueEntry> entries = new LinkedHashMap<>();
    private final Map<String, Integer> ticketCounters = new HashMap<>();
    private long nextId = 1;

    public QueueService(Clock clock, List<Service> serviceList, Notifier notifier, WaitTimePredictor predictor) {
        this.clock = clock;
        this.notifier = notifier;
        this.predictor = predictor;
        for (Service s : serviceList) services.put(s.id(), s);
    }

    public WaitTimePredictor predictor() { return predictor; }

    /** Wipes all queue data and learned history (used by the demo reset button). */
    public synchronized void clear() {
        entries.clear();
        ticketCounters.clear();
        nextId = 1;
        predictor.clear();
    }

    // ---------------------------------------------------------------- patient actions

    public synchronized QueueEntry join(String name, String phone, String serviceId,
                                        int priority, String reason) {
        if (serviceId == null || serviceId.isBlank()) throw new IllegalArgumentException("Choose a service");
        Service svc = requireService(serviceId);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name is required");
        if (name.length() > 80) throw new IllegalArgumentException("Name is too long");
        String cleanPhone = normalizePhone(phone);
        if (priority < QueueEntry.NORMAL || priority > QueueEntry.EMERGENCY)
            throw new IllegalArgumentException("Invalid priority");
        if (priority > QueueEntry.NORMAL && (reason == null || reason.isBlank()))
            throw new IllegalArgumentException("A reason is required for priority entries");
        for (QueueEntry e : entries.values()) {
            if (e.status().isActive() && e.serviceId().equals(serviceId) && e.phone().equals(cleanPhone))
                throw new IllegalStateException("This phone number is already in the queue for " + svc.name());
        }

        int n = ticketCounters.merge(serviceId, 1, Integer::sum);
        String ticket = serviceId.substring(0, Math.min(3, serviceId.length())).toUpperCase()
                + "-" + String.format("%03d", n);
        QueueEntry entry = new QueueEntry(nextId++, ticket, name.trim(), cleanPhone, serviceId,
                priority, reason == null ? "" : reason.trim(), clock.instant());
        entries.put(entry.id(), entry);

        EntryView v = viewOf(entry, clock.instant());
        notifier.send(cleanPhone, "ClinicQueue: " + entry.patientName() + ", you are #" + v.position()
                + " for " + svc.name() + " (ticket " + ticket + "). Est. wait ~" + v.estimatedWaitMinutes()
                + " min. We will text you when it is nearly your turn.");
        if (v.position() <= NEAR_TURN_POSITION) entry.markNearTurnNotified();
        afterChange();
        return entry;
    }

    public synchronized QueueEntry cancel(long id) {
        QueueEntry e = require(id);
        if (e.status() != Status.WAITING && e.status() != Status.CALLED)
            throw new IllegalStateException("Only waiting or called entries can be cancelled");
        e.setStatus(Status.CANCELLED);
        e.setFinishedAt(clock.instant());
        afterChange();
        return e;
    }

    // ---------------------------------------------------------------- staff actions

    public synchronized QueueEntry callNext(String serviceId) {
        Service svc = requireService(serviceId);
        List<QueueEntry> waiting = waitingSorted(serviceId);
        if (waiting.isEmpty()) throw new NoSuchElementException("Nobody is waiting for " + svc.name());
        QueueEntry e = waiting.get(0);
        e.setStatus(Status.CALLED);
        e.setCalledAt(clock.instant());
        notifier.send(e.phone(), "ClinicQueue: " + e.patientName() + ", it is your turn for " + svc.name()
                + " (ticket " + e.ticket() + "). Please go to the desk now.");
        afterChange();
        return e;
    }

    public synchronized QueueEntry startService(long id) {
        QueueEntry e = require(id);
        if (e.status() != Status.CALLED)
            throw new IllegalStateException("Only a called patient can start service");
        e.setStatus(Status.IN_SERVICE);
        e.setStartedAt(clock.instant());
        afterChange();
        return e;
    }

    public synchronized QueueEntry finish(long id) {
        QueueEntry e = require(id);
        if (e.status() != Status.IN_SERVICE)
            throw new IllegalStateException("Only a patient in service can be finished");
        Instant now = clock.instant();
        e.setStatus(Status.DONE);
        e.setFinishedAt(now);
        double minutes = Duration.between(e.startedAt(), now).toMillis() / 60000.0;
        predictor.record(e.serviceId(), now, Math.max(minutes, 0.1));
        afterChange();
        return e;
    }

    public synchronized QueueEntry markNoShow(long id) {
        QueueEntry e = require(id);
        if (e.status() != Status.CALLED)
            throw new IllegalStateException("Only a called patient can be marked as no-show");
        e.setStatus(Status.NO_SHOW);
        e.setFinishedAt(clock.instant());
        afterChange();
        return e;
    }

    /** Marks called patients who never showed up within the grace period. Returns how many. */
    public synchronized int sweepNoShows() {
        Instant now = clock.instant();
        int count = 0;
        for (QueueEntry e : entries.values()) {
            if (e.status() == Status.CALLED
                    && Duration.between(e.calledAt(), now).toMinutes() >= NO_SHOW_GRACE_MINUTES) {
                e.setStatus(Status.NO_SHOW);
                e.setFinishedAt(now);
                count++;
            }
        }
        if (count > 0) afterChange();
        return count;
    }

    // ---------------------------------------------------------------- queries

    public synchronized EntryView get(long id) {
        return viewOf(require(id), clock.instant());
    }

    /** Live queue for one service: in service, then called, then waiting in priority order. */
    public synchronized List<EntryView> queueFor(String serviceId) {
        requireService(serviceId);
        Instant now = clock.instant();
        List<QueueEntry> ordered = new ArrayList<>();
        for (QueueEntry e : entries.values())
            if (e.serviceId().equals(serviceId) && e.status() == Status.IN_SERVICE) ordered.add(e);
        for (QueueEntry e : entries.values())
            if (e.serviceId().equals(serviceId) && e.status() == Status.CALLED) ordered.add(e);
        ordered.addAll(waitingSorted(serviceId));
        List<EntryView> views = new ArrayList<>();
        for (QueueEntry e : ordered) views.add(viewOf(e, now));
        return views;
    }

    public synchronized List<ServiceStatus> serviceStatuses() {
        Instant now = clock.instant();
        List<ServiceStatus> out = new ArrayList<>();
        for (Service s : services.values()) {
            int waiting = 0, called = 0, inService = 0;
            List<QueueEntry> everyone = new ArrayList<>();
            for (QueueEntry e : entries.values()) {
                if (!e.serviceId().equals(s.id()) || !e.status().isActive()) continue;
                everyone.add(e);
                switch (e.status()) {
                    case WAITING -> waiting++;
                    case CALLED -> called++;
                    case IN_SERVICE -> inService++;
                    default -> { }
                }
            }
            out.add(new ServiceStatus(s, waiting, called, inService,
                    predictor.estimateWaitMinutes(s, everyone, now),
                    predictor.expectedMinutes(s, now), predictor.sampleCount(s.id())));
        }
        return out;
    }

    public synchronized List<Service> services() {
        return new ArrayList<>(services.values());
    }

    public synchronized Service service(String id) {
        return requireService(id);
    }

    /** Headline numbers plus average wait by hour of day, for the dashboard chart. */
    public synchronized Map<String, Object> stats() {
        int served = 0, noShows = 0, cancelled = 0;
        double waitSum = 0;
        int waitCount = 0;
        Map<Integer, double[]> byHour = new TreeMap<>(); // hour -> {sumWait, count}
        ZoneId zone = clock.getZone();
        for (QueueEntry e : entries.values()) {
            if (e.status() == Status.DONE) served++;
            else if (e.status() == Status.NO_SHOW) noShows++;
            else if (e.status() == Status.CANCELLED) cancelled++;
            if (e.startedAt() != null) {
                double w = Duration.between(e.joinedAt(), e.startedAt()).toMillis() / 60000.0;
                waitSum += w;
                waitCount++;
                double[] agg = byHour.computeIfAbsent(e.joinedAt().atZone(zone).getHour(), h -> new double[2]);
                agg[0] += w;
                agg[1] += 1;
            }
        }
        List<Map<String, Object>> hourly = new ArrayList<>();
        for (Map.Entry<Integer, double[]> en : byHour.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("hour", en.getKey());
            row.put("avgWaitMinutes", round1(en.getValue()[0] / en.getValue()[1]));
            row.put("patients", (int) en.getValue()[1]);
            hourly.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("served", served);
        out.put("noShows", noShows);
        out.put("cancelled", cancelled);
        out.put("avgWaitMinutes", waitCount == 0 ? 0.0 : round1(waitSum / waitCount));
        out.put("hourly", hourly);
        return out;
    }

    // ---------------------------------------------------------------- internals

    private EntryView viewOf(QueueEntry e, Instant now) {
        if (e.status() != Status.WAITING) return new EntryView(e, 0, 0, 0);
        Service svc = services.get(e.serviceId());
        List<QueueEntry> ahead = new ArrayList<>();
        for (QueueEntry other : entries.values()) {
            if (other.serviceId().equals(e.serviceId())
                    && (other.status() == Status.IN_SERVICE || other.status() == Status.CALLED))
                ahead.add(other);
        }
        List<QueueEntry> waiting = waitingSorted(e.serviceId());
        int position = waiting.indexOf(e) + 1;
        ahead.addAll(waiting.subList(0, position - 1));
        return new EntryView(e, position, ahead.size(), predictor.estimateWaitMinutes(svc, ahead, now));
    }

    private List<QueueEntry> waitingSorted(String serviceId) {
        List<QueueEntry> waiting = new ArrayList<>();
        for (QueueEntry e : entries.values())
            if (e.serviceId().equals(serviceId) && e.status() == Status.WAITING) waiting.add(e);
        waiting.sort(QUEUE_ORDER);
        return waiting;
    }

    /** Text patients who have just moved into the "nearly your turn" zone. */
    private void afterChange() {
        Instant now = clock.instant();
        for (Service s : services.values()) {
            List<QueueEntry> waiting = waitingSorted(s.id());
            for (int i = 0; i < Math.min(NEAR_TURN_POSITION, waiting.size()); i++) {
                QueueEntry e = waiting.get(i);
                if (e.nearTurnNotified()) continue;
                EntryView v = viewOf(e, now);
                String lead = v.position() == 1 ? "you are next" : "you are #" + v.position() + " in line";
                notifier.send(e.phone(), "ClinicQueue: " + e.patientName() + ", " + lead + " for "
                        + s.name() + " (~" + v.estimatedWaitMinutes() + " min). Please head to the clinic now.");
                e.markNearTurnNotified();
            }
        }
    }

    private QueueEntry require(long id) {
        QueueEntry e = entries.get(id);
        if (e == null) throw new NoSuchElementException("No queue entry with id " + id);
        return e;
    }

    private Service requireService(String id) {
        Service s = id == null ? null : services.get(id);
        if (s == null) throw new NoSuchElementException("Unknown service: " + id);
        return s;
    }

    static String normalizePhone(String phone) {
        if (phone == null) throw new IllegalArgumentException("Phone number is required");
        String p = phone.replaceAll("[\\s\\-().]", "");
        if (!p.matches("\\+?\\d{7,15}"))
            throw new IllegalArgumentException("Enter a valid phone number (7-15 digits)");
        return p;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
