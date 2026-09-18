package com.elioo.baymax.healthrecord.domain;

/**
 * A patient a session may see, and in what capacity. {@code owner} is the family the patient belongs to;
 * a share member sees the timeline but may not upload to it or delete from it.
 */
public record PatientAccess(PatientProfile patient, boolean owner) {
}
