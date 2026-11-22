package com.clinicqueue;

/** Service lifecycle; arrival check-in is tracked separately from WAITING -> CALLED -> IN_SERVICE. */
public enum Status {
    WAITING, CALLED, IN_SERVICE, DONE, NO_SHOW, CANCELLED;

    /** True while the entry still occupies a place in the live queue. */
    public boolean isActive() {
        return this == WAITING || this == CALLED || this == IN_SERVICE;
    }
}
