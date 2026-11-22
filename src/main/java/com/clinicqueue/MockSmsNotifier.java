package com.clinicqueue;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/** Demo notifier: prints each "SMS" to the console and keeps the latest ones for the dashboard. */
public class MockSmsNotifier implements Notifier {

    public record Sms(Instant sentAt, String serviceId, String phone, String message) {}

    private static final int KEEP = 50;

    private final Clock clock;
    private final boolean echoToConsole;
    private final LinkedList<Sms> outbox = new LinkedList<>();

    public MockSmsNotifier(Clock clock, boolean echoToConsole) {
        this.clock = clock;
        this.echoToConsole = echoToConsole;
    }

    @Override
    public synchronized void send(String phone, String message) {
        send(null, phone, message);
    }

    @Override
    public synchronized void send(String serviceId, String phone, String message) {
        outbox.addFirst(new Sms(clock.instant(), serviceId, phone, message));
        while (outbox.size() > KEEP) outbox.removeLast();
        if (echoToConsole) System.out.println("[SMS -> " + phone + "] " + message);
    }

    /** Newest first. */
    public synchronized List<Sms> recent() {
        return new ArrayList<>(outbox);
    }

    public synchronized void clear() {
        outbox.clear();
    }
}
