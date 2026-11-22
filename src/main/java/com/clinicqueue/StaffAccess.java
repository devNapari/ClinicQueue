package com.clinicqueue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Resolves shared PINs to service-provider, nurse, or legacy demo access. */
public final class StaffAccess {

    public enum Role { LEGACY, PROVIDER, NURSE }

    public record Principal(Role role, String serviceId) {}

    private static final Map<String, String> PIN_ENVIRONMENT_KEYS = Map.of(
            "consultation", "CONSULTATION_PIN",
            "lab", "LAB_PIN",
            "pharmacy", "PHARMACY_PIN");
    private static final String NURSE_PIN_KEY = "NURSE_PIN";

    private final Map<String, String> servicePins;
    private final String nursePin;
    private final String legacyPin;

    private StaffAccess(Map<String, String> servicePins, String nursePin, String legacyPin) {
        this.servicePins = Map.copyOf(servicePins);
        this.nursePin = nursePin;
        this.legacyPin = legacyPin;
    }

    public static StaffAccess singlePin(String pin) {
        validatePin(pin, "STAFF_PIN");
        return new StaffAccess(Map.of(), null, pin);
    }

    /**
     * Uses one backward-compatible demo PIN when no department PINs are configured. Once any
     * role-specific PIN is supplied, all four roles must have distinct PINs.
     */
    public static StaffAccess fromEnvironment(Map<String, String> environment, String fallbackPin) {
        boolean configured = environment.containsKey(NURSE_PIN_KEY)
                || PIN_ENVIRONMENT_KEYS.values().stream().anyMatch(environment::containsKey);
        if (!configured) return singlePin(fallbackPin);

        Map<String, String> byService = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : PIN_ENVIRONMENT_KEYS.entrySet()) {
            String pin = environment.get(entry.getValue());
            validatePin(pin, entry.getValue());
            byService.put(entry.getKey(), pin);
        }
        String nurse = environment.get(NURSE_PIN_KEY);
        validatePin(nurse, NURSE_PIN_KEY);

        Set<String> allPins = new HashSet<>(byService.values());
        allPins.add(nurse);
        if (allPins.size() != 4)
            throw new IllegalArgumentException("Department PINs and NURSE_PIN must all be different");
        return new StaffAccess(byService, nurse, null);
    }

    public Principal authenticate(String candidate) {
        if (candidate == null) return null;
        if (legacyPin != null && same(candidate, legacyPin)) return new Principal(Role.LEGACY, null);
        for (Map.Entry<String, String> entry : servicePins.entrySet()) {
            if (same(candidate, entry.getValue())) return new Principal(Role.PROVIDER, entry.getKey());
        }
        if (nursePin != null && same(candidate, nursePin)) return new Principal(Role.NURSE, null);
        return null;
    }

    public boolean hasDepartmentPins() {
        return legacyPin == null;
    }

    private static boolean same(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8),
                second.getBytes(StandardCharsets.UTF_8));
    }

    private static void validatePin(String pin, String name) {
        if (pin == null || pin.length() < 4 || pin.length() > 32
                || pin.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(name + " must be 4 to 32 characters with no spaces");
        }
    }
}
