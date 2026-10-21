package com.elioo.baymax.web.application.port.in;

import com.elioo.baymax.web.domain.LabSeries;
import com.elioo.baymax.web.domain.LatestPrescription;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * The health screen (plan B6): one patient's lab series, blood-pressure pairs and the latest prescription's medicines as
 * written. Zero model calls. A patient this family cannot see is a 404.
 */
public interface HealthUseCase {

    /** The series (by their newest point, newest first) and the paired blood-pressure readings. */
    Mono<HealthLabs> labs(UUID familyId, UUID patientId);

    /**
     * The newest DONE prescription's medicines as written, with its date, or {@link LatestPrescription#none} (which still
     * says whether a newer paper is being read). Each medicine carries only {@code PaperDisplay.MEDICINE_FIELDS} and its
     * {@code crop_key}.
     */
    Mono<LatestPrescription> medicines(UUID familyId, UUID patientId);

    record HealthLabs(List<LabSeries> series, LabSeries.BpSeries bp) {
    }
}
