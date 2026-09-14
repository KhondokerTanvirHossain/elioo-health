package com.elioo.baymax.healthrecord.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A person whose records the family keeps. Consent is by proxy from the family owner ("I am authorised
 * to manage this person's health records"), recorded once and never updated.
 *
 * @param sex FHIR administrativeGender: male | female | other | unknown
 */
public record PatientProfile(
        UUID id,
        UUID familyId,
        String name,
        int age,
        Sex sex,
        List<String> chronicFlags,
        Instant proxyConsentAt,
        Instant createdAt
) {
    public enum Sex {
        MALE, FEMALE, OTHER, UNKNOWN;

        public String dbValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Sex fromDbValue(String v) {
            return valueOf(v.toUpperCase(java.util.Locale.ROOT));
        }
    }
}
