package com.clinicqueue;

/** Lifecycle of a queue entry: WAITING -> CALLED -> IN_SERVICE -> DONE (or NO_SHOW / CANCELLED). */
public enum Status {
    WAITING, CALLED, IN_SERVICE, DONE, NO_SHOW, CANCELLED;

    /** True while the entry still occupies a place in the live queue. */
    public boolean isActive() {
        return this == WAITING || this == CALLED || this == IN_SERVICE;
    }
}
