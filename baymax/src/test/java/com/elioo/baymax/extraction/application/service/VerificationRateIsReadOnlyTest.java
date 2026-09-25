package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.extraction.application.port.in.RecropUseCase.VerificationRate;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verification-rate probe measures and changes nothing.
 *
 * <p>It is meant to run across every document in production, including ones a family is currently looking
 * at, so that the retake gate can be compared against an objective signal before anything is switched. The
 * neighbouring {@code recrop} deletes a document's crops and items and rewrites its row; building the
 * measurement on that would have destroyed live data to answer a question about it — the same shape as the
 * drafting rerun that overwrote ten verified labels.</p>
 *
 * <p>The arithmetic is separated from the I/O so the counting rule can be asserted without a database, and
 * the no-write property is asserted against real ports in
 * {@code VerificationRateAcceptanceTest}.</p>
 */
class VerificationRateIsReadOnlyTest {

    private static VerificationRate rate(int confirmed, int extracted) {
        return new VerificationRate(UUID.randomUUID(), "lab_report", "DONE", "measured",
                confirmed, extracted, 0.9);
    }

    @Test
    void theRateIsConfirmedOverExtracted() {
        assertThat(rate(15, 21).rate()).contains(15d / 21d);
        assertThat(rate(21, 21).rate()).contains(1.0);
        assertThat(rate(0, 21).rate()).contains(0.0);
    }

    /**
     * A document with nothing to judge yields no rate, rather than 0% or 100%.
     *
     * <p>Both defaults would be lies that a threshold would then act on: 0% reads as "unreadable" and would
     * retake a prescription that simply has no lab values, 100% reads as "perfectly verified" and would wave
     * through a page nothing was checked on. Empty forces the caller to exclude it.</p>
     */
    @Test
    void aDocumentWithNoGatingItemsHasNoRate() {
        assertThat(rate(0, 0).rate())
                .as("no gating items means no evidence either way, not a score of zero")
                .isEmpty();
    }

    /** The comparison the whole exercise exists for is carried on the same row. */
    @Test
    void theModelsOwnConfidenceIsCarriedAlongsideForComparison() {
        VerificationRate measured = rate(15, 21);

        assertThat(measured.confidence()).isEqualTo(0.9);
        assertThat(measured.status()).isEqualTo("DONE");
        assertThat(measured.documentType()).isEqualTo("lab_report");
    }

    /** A NEEDS_RETAKE document must be measurable: it is the population the gate is deciding about. */
    @Test
    void aRetakenDocumentCarriesAMeasurableRate() {
        VerificationRate retaken = new VerificationRate(UUID.randomUUID(), "lab_report", "NEEDS_RETAKE",
                "measured", 3, 13, 0.75);

        assertThat(retaken.rate())
                .as("gated before cropping, so the database holds no verification for it — this is the gap")
                .contains(3d / 13d);
    }
}
