package com.elioo.baymax.outbound.domain;

/**
 * What actually happened when a message was handed to a delivery channel (BMX-10).
 *
 * <p>The port used to return {@code Mono<Void>}, so a caller could not tell a delivered message from one the
 * provider refused — and {@code approve} set {@code sent_at} regardless. A reviewer has to be able to read
 * {@code sent_at} as "the family received it", so the outcome is now a value, not an absence of an exception.
 *
 * @param status    sent when the provider accepted it, failed otherwise; pending only before an attempt
 * @param messageId the provider's own id (a WhatsApp wamid), null unless sent
 * @param error     a machine code, never provider prose; null unless failed
 */
public record DeliveryOutcome(Status status, String messageId, String error) {

    public enum Status {
        PENDING, SENT, FAILED;

        public String dbValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Status fromDbValue(String v) {
            return v == null ? null : valueOf(v.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** Errors phase 1 can actually produce; window_expired is expected, not exceptional. */
    public static final String WINDOW_EXPIRED = "window_expired";
    public static final String TOKEN_INVALID = "token_invalid";
    public static final String NOT_ALLOWLISTED = "not_allowlisted";
    public static final String SEND_FAILED = "send_failed";

    public static DeliveryOutcome sent(String messageId) {
        return new DeliveryOutcome(Status.SENT, messageId, null);
    }

    public static DeliveryOutcome failed(String error) {
        return new DeliveryOutcome(Status.FAILED, null, error);
    }

    /** The log adapter: nothing was delivered anywhere, so there is no outcome to record. */
    public static DeliveryOutcome logged() {
        return new DeliveryOutcome(null, null, null);
    }

    public boolean wasSent() {
        return status == Status.SENT;
    }
}
