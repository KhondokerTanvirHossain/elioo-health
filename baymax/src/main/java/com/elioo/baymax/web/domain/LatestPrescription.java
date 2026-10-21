package com.elioo.baymax.web.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The medicines "as written on the latest prescription", dated (Part B decision 9, Q3): the newest DONE prescription of
 * the patient by filing day, then upload, then id, <b>whatever its medicine count</b>. If it lists none the answer is its
 * date and an empty list; it never falls back to an older paper, because an older list shown as "the latest" reads as
 * what the parent takes now. Nothing here is "current".
 *
 * @param documentId   the prescription, or null when the patient has no DONE prescription ({@link #none})
 * @param date         its filing day ({@code document.timeline_date}); null with {@code documentId}
 * @param medicines    its {@code medication_event} rows, each only the fields as written
 *                     ({@code PaperDisplay.MEDICINE_FIELDS}) and its {@code crop_key}
 * @param newerNotRead the newest paper uploaded after it that may be a prescription and is not read yet (RECEIVED,
 *                     PROCESSING, or a NEEDS_RETAKE prescription or untyped paper), so the screen says so instead of
 *                     presenting the older list as current
 */
public record LatestPrescription(UUID documentId, LocalDate date, boolean dateIsUploadDay, boolean dateUncertain,
                                 List<Map<String, Object>> medicines, Optional<UUID> newerNotRead) {

    public LatestPrescription {
        medicines = medicines == null ? List.of() : List.copyOf(medicines);
        newerNotRead = newerNotRead == null ? Optional.empty() : newerNotRead;
    }

    /** No DONE prescription yet; a paper still being read may be one. */
    public static LatestPrescription none(Optional<UUID> newerNotRead) {
        return new LatestPrescription(null, null, false, false, List.of(), newerNotRead);
    }

    public boolean found() {
        return documentId != null;
    }
}
