package com.clinicqueue;

import java.time.Instant;

/** One patient's place in one service queue. Mutated only through QueueService. */
public class QueueEntry {

    /** 0 = normal, 1 = elderly / pregnant / disabled, 2 = emergency. */
    public static final int NORMAL = 0;
    public static final int PRIORITY = 1;
    public static final int EMERGENCY = 2;

    private final long id;
    private final String ticket;
    private final String patientName;
    private final String phone;
    private final String serviceId;
    private final int priority;
    private final String priorityReason;
    private final Instant joinedAt;

    private Status status = Status.WAITING;
    private Instant calledAt;
    private Instant startedAt;
    private Instant finishedAt;
    private boolean nearTurnNotified;

    public QueueEntry(long id, String ticket, String patientName, String phone,
                      String serviceId, int priority, String priorityReason, Instant joinedAt) {
        this.id = id;
        this.ticket = ticket;
        this.patientName = patientName;
        this.phone = phone;
        this.serviceId = serviceId;
        this.priority = priority;
        this.priorityReason = priorityReason == null ? "" : priorityReason;
        this.joinedAt = joinedAt;
    }

    public long id() { return id; }
    public String ticket() { return ticket; }
    public String patientName() { return patientName; }
    public String phone() { return phone; }
    public String serviceId() { return serviceId; }
    public int priority() { return priority; }
    public String priorityReason() { return priorityReason; }
    public Instant joinedAt() { return joinedAt; }
    public Status status() { return status; }
    public Instant calledAt() { return calledAt; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public boolean nearTurnNotified() { return nearTurnNotified; }

    void setStatus(Status s) { this.status = s; }
    void setCalledAt(Instant t) { this.calledAt = t; }
    void setStartedAt(Instant t) { this.startedAt = t; }
    void setFinishedAt(Instant t) { this.finishedAt = t; }
    void markNearTurnNotified() { this.nearTurnNotified = true; }
}
