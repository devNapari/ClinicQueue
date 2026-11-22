package com.clinicqueue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Learns how long each service really takes and turns that into wait-time estimates.
 *
 * Expected duration of one consultation =
 *     smoothed rolling average of the last {@value #WINDOW} real durations
 *   x time-of-day factor (how this hour compares with the service's overall average)
 *
 * The rolling average is blended with the clinic's configured default using a small
 * prior weight, so the very first patients get sensible numbers and the estimate
 * adapts quickly as real data arrives.
 */
public class WaitTimePredictor {

    static final int WINDOW = 20;
    static final int HISTORY = 200;
    static final int PRIOR_WEIGHT = 3;
    static final int MIN_SAMPLES_FOR_HOUR_FACTOR = 3;
    static final double MIN_FACTOR = 0.7;
    static final double MAX_FACTOR = 1.5;

    private record Sample(Instant finishedAt, double minutes) {}

    private final Map<String, Deque<Sample>> history = new HashMap<>();
    private final ZoneId zone;

    public WaitTimePredictor(ZoneId zone) {
        this.zone = zone;
    }

    /** Record a completed consultation of the given real length. */
    public synchronized void record(String serviceId, Instant finishedAt, double minutes) {
        if (minutes <= 0) return;
        Deque<Sample> d = history.computeIfAbsent(serviceId, k -> new ArrayDeque<>());
        d.addLast(new Sample(finishedAt, minutes));
        while (d.size() > HISTORY) d.removeFirst();
    }

    public synchronized int sampleCount(String serviceId) {
        Deque<Sample> d = history.get(serviceId);
        return d == null ? 0 : d.size();
    }

    public synchronized void clear() {
        history.clear();
    }

    /** What the predictor has learned: average real duration per hour of day (hour -> minutes). */
    public synchronized Map<Integer, Double> hourlyAverageMinutes(String serviceId) {
        Map<Integer, double[]> agg = new TreeMap<>();
        Deque<Sample> d = history.get(serviceId);
        if (d != null) {
            for (Sample s : d) {
                double[] a = agg.computeIfAbsent(s.finishedAt().atZone(zone).getHour(), h -> new double[2]);
                a[0] += s.minutes();
                a[1] += 1;
            }
        }
        Map<Integer, Double> out = new TreeMap<>();
        for (Map.Entry<Integer, double[]> e : agg.entrySet())
            out.put(e.getKey(), Math.round(e.getValue()[0] / e.getValue()[1] * 10.0) / 10.0);
        return out;
    }

    /** Expected length in minutes of one consultation of this service starting at {@code at}. */
    public synchronized double expectedMinutes(Service service, Instant at) {
        Deque<Sample> d = history.get(service.id());
        if (d == null || d.isEmpty()) return service.defaultMinutes();

        // 1. Smoothed rolling average over the most recent samples.
        Sample[] all = d.toArray(new Sample[0]);
        int from = Math.max(0, all.length - WINDOW);
        double sum = 0;
        for (int i = from; i < all.length; i++) sum += all[i].minutes();
        int n = all.length - from;
        double base = (PRIOR_WEIGHT * service.defaultMinutes() + sum) / (PRIOR_WEIGHT + n);

        // 2. Time-of-day factor: is this hour usually slower or faster than average?
        int hour = at.atZone(zone).getHour();
        double hourSum = 0;
        int hourCount = 0;
        double totalSum = 0;
        for (Sample s : all) {
            totalSum += s.minutes();
            if (s.finishedAt().atZone(zone).getHour() == hour) {
                hourSum += s.minutes();
                hourCount++;
            }
        }
        double factor = 1.0;
        if (hourCount >= MIN_SAMPLES_FOR_HOUR_FACTOR) {
            double overall = totalSum / all.length;
            factor = (hourSum / hourCount) / overall;
            factor = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, factor));
        }
        return base * factor;
    }

    /**
     * Estimated minutes until a new patient joining behind {@code ahead} would start being seen.
     *
     * @param ahead everyone in front: patients currently in service, called, and waiting
     */
    public synchronized int estimateWaitMinutes(Service service, List<QueueEntry> ahead, Instant now) {
        double expected = expectedMinutes(service, now);
        double total = 0;
        for (QueueEntry e : ahead) {
            if (e.status() == Status.IN_SERVICE && e.startedAt() != null) {
                double elapsed = Duration.between(e.startedAt(), now).toSeconds() / 60.0;
                total += Math.max(1.0, expected - elapsed);
            } else {
                total += expected;
            }
        }
        return (int) Math.ceil(total / service.servers());
    }
}
