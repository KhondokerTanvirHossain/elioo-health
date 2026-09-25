package com.elioo.baymax.extraction.application.port.in;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Re-runs crop resolution for documents already extracted: stored page images are OCR'd again (Vision only,
 * metered as usual), the stored extraction_json is replayed through the current cutter, old crops and items
 * are replaced. No model call. Exists so a corpus cropped under the old, offset-only resolution (DR-12)
 * never needs re-extraction. Not scheduled; run by an operator.
 */
public interface RecropUseCase {

    record RecropReport(UUID documentId, String outcome, int values, int medicines, int followUps, int context,
                        int unverified) {
    }

    Mono<RecropReport> recrop(UUID documentId);

    /** Every DONE document, in creation order. */
    Flux<RecropReport> recropAll();

    /**
     * How much of a document's GATING content could be independently confirmed on the page — measured,
     * never applied.
     *
     * <p>The retake gate currently rests on the model's own confidence score, which moved 0.85 → 0.70 on the
     * same image between two runs while the values it read stayed identical. A score that decides whether a
     * family is asked to re-photograph their report should not be the model's opinion of itself.</p>
     *
     * <p>Image-side verification offers an objective alternative: for each item in a gating section, whether
     * something other than the extraction read the value back off the page. This computes that rate and
     * changes nothing — the gate is untouched until the separation is shown to be real. Read-only, unlike
     * {@link #recrop}: no crop is stored, no item is deleted, no document row is written.</p>
     *
     * <p>Works on NEEDS_RETAKE documents too, which is the point: those are gated before cropping ever runs,
     * so they have no verification signal in the database at all, and they are exactly the population the
     * gate is deciding about.</p>
     *
     * @param confirmed  gating items whose value was read back off the page
     * @param extracted  gating items the model reported in the gating sections
     * @param confidence the model's self-reported overall confidence, for comparison
     */
    record VerificationRate(UUID documentId, String documentType, String status, String outcome,
                            int confirmed, int extracted, Double confidence) {

        /** Confirmed ÷ extracted, or empty when the document has no gating items to judge. */
        public java.util.Optional<Double> rate() {
            return extracted == 0 ? java.util.Optional.empty()
                    : java.util.Optional.of((double) confirmed / extracted);
        }
    }

    /** Measures one document without changing it. */
    Mono<VerificationRate> verificationRate(UUID documentId);
}
