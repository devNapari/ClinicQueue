package com.clinicqueue;

/**
 * Sends a message to a patient. Production would implement this with an SMS gateway
 * (Africa's Talking, Hubtel, Twilio...); the demo uses {@link MockSmsNotifier}.
 */
public interface Notifier {
    void send(String phone, String message);

    default void send(String serviceId, String phone, String message) {
        send(phone, message);
    }
}
