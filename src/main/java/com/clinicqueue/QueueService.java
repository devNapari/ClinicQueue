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

    public static final int ARRIVAL_REMINDER_MINUTES = 60;
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
        return addEntry(name, phone, serviceId, priority, reason, false, false);
    }

    /** Registers an in-person patient; an empty phone number is allowed. */
    public synchronized QueueEntry joinWalkIn(String name, String phone, String serviceId,
                                              int priority, String reason) {
        return addEntry(name, phone, serviceId, priority, reason, true, true);
    }

    private QueueEntry addEntry(String name, String phone, String serviceId, int priority,
                                String reason, boolean phoneOptional, boolean alreadyArrived) {
        if (serviceId == null || serviceId.isBlank()) throw new IllegalArgumentException("Choose a service");
        Service svc = requireService(serviceId);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name is required");
        if (name.length() > 80) throw new IllegalArgumentException("Name is too long");
        String cleanPhone = phoneOptional && (phone == null || phone.isBlank()) ? "" : normalizePhone(phone);
        if (priority < QueueEntry.NORMAL || priority > QueueEntry.EMERGENCY)
            throw new IllegalArgumentException("Invalid priority");
        if (priority > QueueEntry.NORMAL && (reason == null || reason.isBlank()))
            throw new IllegalArgumentException("A reason is required for priority entries");
        for (QueueEntry e : entries.values()) {
            if (!cleanPhone.isEmpty() && e.status().isActive()
                    && e.serviceId().equals(serviceId) && e.phone().equals(cleanPhone))
                throw new IllegalStateException("This phone number is already in the queue for " + svc.name());
        }

        int n = ticketCounters.merge(serviceId, 1, Integer::sum);
        String ticket = serviceId.substring(0, Math.min(3, serviceId.length())).toUpperCase()
                + "-" + String.format("%03d", n);
        QueueEntry entry = new QueueEntry(nextId++, ticket, name.trim(), cleanPhone, serviceId,
                priority, reason == null ? "" : reason.trim(), clock.instant());
        if (alreadyArrived) entry.markArrived(clock.instant());
        entries.put(entry.id(), entry);

        if (!cleanPhone.isEmpty()) {
            EntryView v = viewOf(entry, clock.instant());
            String message = alreadyArrived
                    ? "ClinicQueue: " + entry.patientName() + ", you are registered and checked in for "
                        + svc.name() + " (ticket " + ticket + "). You are #" + v.position()
                        + " in line; wait for your provider to call you."
                    : "ClinicQueue: " + entry.patientName() + ", you are #" + v.position()
                        + " for " + svc.name() + " (ticket " + ticket + "). Est. wait ~"
                        + v.estimatedWaitMinutes() + " min. We will text you when it is time to come in.";
            notifier.send(serviceId, cleanPhone, message);
        }
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
        List<QueueEntry> allWaiting = waitingSorted(serviceId);
        List<QueueEntry> waiting = allWaiting.stream().filter(QueueEntry::hasArrived).toList();
        if (waiting.isEmpty()) {
            if (allWaiting.isEmpty()) throw new NoSuchElementException("Nobody is waiting for " + svc.name());
            throw new IllegalStateException("No checked-in patients are waiting for " + svc.name());
        }
        QueueEntry e = waiting.get(0);
        e.setStatus(Status.CALLED);
        e.setCalledAt(clock.instant());
        if (!e.phone().isEmpty()) {
            notifier.send(serviceId, e.phone(), "ClinicQueue: " + e.patientName() + ", it is your turn for "
                    + svc.name() + " (ticket " + e.ticket() + "). Please go to the service area now.");
        }
        afterChange();
        return e;
    }

    /** Marks a waiting patient as arrived at the hospital. Repeated check-ins are harmless. */
    public synchronized QueueEntry markArrived(long id) {
        QueueEntry e = require(id);
        if (e.status() != Status.WAITING)
            throw new IllegalStateException("Only a waiting patient can check in");
        e.markArrived(clock.instant());
        afterChange();
        return e;
    }

    /** Sends the one-time arrival reminder manually, even if the estimated wait exceeds one hour. */
    public synchronized QueueEntry sendArrivalReminder(long id) {
        QueueEntry e = require(id);
        if (e.status() != Status.WAITING)
            throw new IllegalStateException("Only a waiting patient can receive an arrival reminder");
        if (e.hasArrived()) throw new IllegalStateException("This patient has already checked in");
        if (e.arrivalReminderSentAt() != null)
            throw new IllegalStateException("The arrival reminder was already sent");
        if (e.phone().isEmpty()) throw new IllegalStateException("This patient has no phone number");
        sendArrivalReminder(e, clock.instant());
        return e;
    }

    /** Sends automatic arrival reminders for waiting patients whose estimated wait is at most an hour. */
    public synchronized int dispatchDueArrivalReminders() {
        Instant now = clock.instant();
        int sent = 0;
        for (Service service : services.values()) {
            for (QueueEntry e : waitingSorted(service.id())) {
                if (e.hasArrived() || e.phone().isEmpty() || e.arrivalReminderSentAt() != null) continue;
                if (viewOf(e, now).estimatedWaitMinutes() <= ARRIVAL_REMINDER_MINUTES) {
                    sendArrivalReminder(e, now);
                    sent++;
                }
            }
        }
        return sent;
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

    public synchronized Map<String, Object> stats() {
        return stats(null);
    }

    /** Headline numbers plus average wait by hour, optionally restricted to one service. */
    public synchronized Map<String, Object> stats(String serviceId) {
        if (serviceId != null) requireService(serviceId);
        int served = 0, noShows = 0, cancelled = 0;
        double waitSum = 0;
        int waitCount = 0;
        Map<Integer, double[]> byHour = new TreeMap<>(); // hour -> {sumWait, count}
        ZoneId zone = clock.getZone();
        for (QueueEntry e : entries.values()) {
            if (serviceId != null && !e.serviceId().equals(serviceId)) continue;
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

    private void afterChange() {
        dispatchDueArrivalReminders();
    }

    private void sendArrivalReminder(QueueEntry e, Instant now) {
        Service service = services.get(e.serviceId());
        int wait = viewOf(e, now).estimatedWaitMinutes();
        notifier.send(e.serviceId(), e.phone(), "ClinicQueue: " + e.patientName() + ", please head to the hospital "
                + "for " + service.name() + " (ticket " + e.ticket() + "). Your estimated wait is about "
                + wait + " min. Check in on your ticket page when you arrive.");
        e.markArrivalReminderSentAt(now);
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
