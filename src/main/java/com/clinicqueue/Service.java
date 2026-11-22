package com.clinicqueue;

/**
 * A clinic service with its own queue (e.g. Consultation, Laboratory, Pharmacy).
 *
 * @param id             stable identifier used in the API
 * @param name           display name
 * @param defaultMinutes expected duration before any real data exists
 * @param servers        how many staff serve this queue in parallel (e.g. doctors on duty)
 */
public record Service(String id, String name, int defaultMinutes, int servers) {
    public Service {
        if (defaultMinutes <= 0) throw new IllegalArgumentException("defaultMinutes must be positive");
        if (servers <= 0) throw new IllegalArgumentException("servers must be positive");
    }
}
