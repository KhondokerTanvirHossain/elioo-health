package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.extraction.domain.Document;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "সব কিছু স্বাভাবিক সীমার মধ্যে আছে" — everything is within the normal range — is a claim ABOUT THE DOCUMENT,
 * and may be made only when the document carries values with printed ranges and every one is inside.
 *
 * <p>On 2026-09-21 it was sent for prescription 08c02570: no printed ranges anywhere (a prescription has none),
 * while the same page recorded near-blackout, hallucinations, a hammering severe headache and a new Parkinson's
 * diagnosis. The template asserted it unconditionally because the copy had been written for lab reports.
 */
class WithinNormalRangeClaimTest {

    private static Map<String, Object> value(String name, String v, String low, String high, String flag) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("value", v);
        m.put("ref_low", low);
        m.put("ref_high", high);
        m.put("flag", flag);
        m.put("critical", false);
        return m;
    }

    private static DocumentFacts facts(List<Map<String, Object>> values) {
        Document document = Document.received(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 1,
                java.time.Instant.parse("2026-09-21T09:00:00Z"));
        return new DocumentFacts(document, "Test Patient", values, List.of(), Map.of(), "https://x/y");
    }

    /**
     * The exact document. BP 130 and BP 89, both with ref_low and ref_high null, because prescriptions do not
     * print reference ranges — the values were read from a handwritten vitals line.
     */
    @Test
    void theProductionPrescriptionCannotSupportTheNormalRangeClaim() {
        List<Map<String, Object>> asExtracted = List.of(
                value("BP", "130", null, null, null),
                value("BP", "89", null, null, null));

        assertThat(ExplanationService.canSayWithinNormalRange(facts(asExtracted)))
                .as("prescription 08c02570: no printed ranges, and the same page recorded near-blackout, "
                        + "hallucinations and a new Parkinson's diagnosis")
                .isFalse();
    }

    @Test
    void aLabReportWhoseValuesAreAllInsideTheirPrintedRangesMayMakeTheClaim() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("HbA1c", "5.2", "4.0", "5.6", "normal"),
                value("Creatinine", "0.9", "0.6", "1.1", "normal")))))
                .isTrue();
    }

    @Test
    void oneValueOutsideItsRangeWithdrawsTheClaim() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("HbA1c", "9.8", "4.0", "5.6", "high"),
                value("Creatinine", "0.9", "0.6", "1.1", "normal")))))
                .isFalse();
    }

    /** One value without a range is enough to withdraw it: the claim would cover it without evidence. */
    @Test
    void aMixOfRangedAndUnrangedValuesWithdrawsTheClaim() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("HbA1c", "5.2", "4.0", "5.6", "normal"),
                value("BP", "130", null, null, null)))))
                .isFalse();
    }

    @Test
    void aDocumentWithNoValuesAtAllCannotMakeTheClaim() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of()))).isFalse();
    }
}
