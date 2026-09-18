package com.elioo.baymax.web.domain;

import com.elioo.baymax.healthrecord.domain.PatientProfile;

import java.time.Instant;

/** A patient as the switcher shows it: who, whether this family owns them, how much is on file. */
public record PatientSummary(PatientProfile patient, boolean owner, long documents, Instant lastDocumentAt) {
}
