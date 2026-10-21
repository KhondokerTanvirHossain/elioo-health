package com.elioo.baymax.web.application.port.out;

import com.elioo.baymax.web.domain.LatestPrescription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * The health screen's read model (plan B6): stored rows only, read after visibility, nothing assessed. The patient is the
 * only filter; access is the caller's ({@code HealthRecordPort.visiblePatient}).
 */
public interface HealthReadPort {

    /**
     * Every observation with a {@code canonical_name} on a DONE paper of the patient, newest {@code timeline_date} then
     * {@code created_at} then id. Each map holds the keys {@code PaperDisplay.kindOf} and {@code PrintedRange} read
     * ({@code id}, {@code name}, {@code canonical_name}, {@code value}, {@code unit}, {@code ref_low}, {@code ref_high},
     * {@code verdict}, {@code verdict_side}; a NULL column is an absent key) plus {@code document_id}, {@code crop_key},
     * {@code source_line} (absent when NULL), {@code date} (the paper's {@code timeline_date}), {@code date_is_fallback}
     * ({@code timeline_date_is_upload_day}) and {@code date_uncertain} ({@code timeline_date_uncertain}).
     */
    Flux<Map<String, Object>> labPoints(UUID patientId);

    /** The newest DONE prescription and its medicines as written, or {@link LatestPrescription#none}; see {@link LatestPrescription}. */
    Mono<LatestPrescription> latestPrescription(UUID patientId);
}
