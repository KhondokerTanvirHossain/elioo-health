package com.elioo.baymax.nudge.application.port.out;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The stored data the five rules read, per patient across documents (BMX-8). Nothing here is computed by a model;
 * every row is what the extraction persisted, with its crop.
 */
public interface NudgeDataPort {

    /** Every patient with the two things the engine must know before reading anything else. */
    record PatientRef(UUID patientId, UUID familyId, List<String> chronicFlags, Instant patientOptedOutAt, Instant familyOptedOutAt) {
        public boolean optedOut() {
            return patientOptedOutAt != null || familyOptedOutAt != null;
        }
    }

    record FollowUpRow(UUID id, UUID patientId, UUID familyId, UUID documentId, String instruction, LocalDate dueDate, String cropKey) {
    }

    record MedicationRow(UUID id, UUID patientId, UUID familyId, UUID documentId, Instant at, LocalDate docDate, String name,
                         String doseText, String frequencyText, String timingText, String durationText, String cropKey) {
    }

    record ObservationRow(UUID id, UUID documentId, String name, String value, String unit, Instant observedAt, String cropKey) {
    }

    Flux<PatientRef> patients();

    Mono<PatientRef> patient(UUID patientId);

    /** Open follow-ups with exactly this due date. */
    Flux<FollowUpRow> openFollowUpsDueOn(LocalDate dueDate);

    /** Medication events that carry a duration text, recorded since {@code since}. */
    Flux<MedicationRow> medicationsWithDurationSince(Instant since);

    /** The medicines of a patient's prescriptions, newest document first, then by name. */
    Flux<MedicationRow> medicationsOfPatient(UUID patientId);

    /** Observations of one canonical marker for a patient, oldest first. */
    /**
     * Every reading of a marker for a patient, oldest first, IN EVERY UNIT.
     *
     * <p>The unit is deliberately not a query parameter. A caller does not know which unit it wants until it
     * has seen the series — the trend rule compares readings in the most recent reading's unit, and which
     * unit that is comes from the last row. Filtering in SQL would need the answer before the question.
     *
     * <p><b>A consumer that compares these numerically MUST group by unit first</b>
     * ({@code NudgeRules.inLatestUnit}). Bangladeshi labs report creatinine in mg/dL and some regional labs
     * in µmol/L, about 88x apart, so an ungrouped comparison reads a change of lab as a rising trend and
     * tells a family to see a doctor about a normal result.
     */
    Flux<ObservationRow> observationsOf(UUID patientId, String canonicalName);

    /** When the patient's last document was received, empty if none. */
    Mono<Instant> lastDocumentAt(UUID patientId);

    /** The canonical markers this patient has any observation for. */
    Flux<String> markersOf(UUID patientId);
}
