package com.elioo.baymax.web.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * One line of the audit trail. UUIDs and the phone HMAC only — a number never reaches this record, so it
 * never reaches the table or the export built from it.
 */
public record AuditEvent(Kind kind, String phoneHash, UUID familyId, UUID patientId, UUID documentId, Instant at) {

    public enum Kind {
        OTP_REQUEST, OTP_VERIFY_OK, OTP_VERIFY_FAIL, OTP_BURNED,
        TIMELINE_VIEW, DOCUMENT_VIEW, DOCUMENT_UPLOAD, DOCUMENT_DELETE, LOGOUT;

        public String dbValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static AuditEvent otp(Kind kind, String phoneHash, Instant at) {
        return new AuditEvent(kind, phoneHash, null, null, null, at);
    }

    public static AuditEvent family(Kind kind, UUID familyId, UUID patientId, UUID documentId, Instant at) {
        return new AuditEvent(kind, null, familyId, patientId, documentId, at);
    }
}
