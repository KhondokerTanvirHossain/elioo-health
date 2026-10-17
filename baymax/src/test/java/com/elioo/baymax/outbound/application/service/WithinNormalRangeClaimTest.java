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

    private static DocumentFacts factsWithUnverified(List<Map<String, Object>> values, int unverifiedValues) {
        java.time.Instant t = java.time.Instant.parse("2026-10-20T09:00:00Z");
        Document document = new Document(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "lab_report", null, null, "{}", 0.9, Document.Status.DONE, null, null, null, 1, t, t,
                new com.elioo.baymax.extraction.domain.VerifiedItems.Unverified(unverifiedValues, 0, 0, 0), null);
        return new DocumentFacts(document, "Test Patient", values, List.of(), Map.of(), "https://x/y");
    }

    // ---- the dangerous direction: each of these was CLAIMED "all normal" before D35 ----

    /** The live gap: a one-sided printed range and a blank flag. 250 is not inside "<100"; nothing compared them. */
    @Test
    void anLdlOf250AgainstALessThan100RangeWithABlankFlagCannotMakeTheClaim() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("LDL", "250", null, "<100", "")))))
                .as("LDL 250 against '<100' was told 'everything is within the normal range' (D35)")
                .isFalse();
    }

    @Test
    void aOneSidedNumericRangeIsNotARangeAValueCanBeInside() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("LDL", "250", null, "100", null))))).isFalse();
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("LDL", "250", "", "100", null))))).isFalse();
    }

    /** Code beats the model: the flag may withdraw the claim, never grant it. */
    @Test
    void aValueOutsideItsRangeByCodeCannotBeRescuedByTheModelsNormalFlag() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("Creatinine", "2.4", "0.6", "1.1", "normal")))))
                .as("the model's flag said normal; 2.4 is outside 0.6–1.1")
                .isFalse();
    }

    /** No reading means no comparison, and no comparison means no claim (UrgencyService.number refuses these). */
    @Test
    void valuesThatDoNotParseAsOneReadingCannotMakeTheClaim() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(value("eGFR", "> 89", "60", "120", ""))))).isFalse();
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(value("HBsAg", "Positive", "Negative", "Negative", ""))))).isFalse();
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(value("TSH", "1,23", "0.5", "1.5", ""))))).isFalse();
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(value("BP", "130 / 80", "90", "140", ""))))).isFalse();
    }

    @Test
    void anInvertedRangeHasNoInside() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(value("X", "7", "10", "5", ""))))).isFalse();
    }

    /** DR-28: a value read but dropped for want of a crop is still on the page; the claim would cover it unseen. */
    @Test
    void aDocumentWithAnUnverifiedValueCannotMakeTheClaim() {
        assertThat(ExplanationService.canSayWithinNormalRange(factsWithUnverified(List.of(
                value("HbA1c", "5.2", "4.0", "5.6", "")), 1))).isFalse();
    }

    // ---- the protected direction: a truly all-inside report still says so ----

    @Test
    void aReportWhoseEveryValueIsInsideByCodeMayMakeTheClaimEvenWithBlankFlags() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("HbA1c", "5.2", "4.0", "5.6", ""),
                value("WBC", "6,100", "4,000", "11,000", null),
                value("Creatinine", "1.1", "0.6", "1.1", null)))))       // the boundary is inside
                .isTrue();
        assertThat(ExplanationService.canSayWithinNormalRange(factsWithUnverified(List.of(
                value("HbA1c", "৫.২", "৪.০", "৫.৬", "normal")), 0)))    // Bangla digits fold
                .isTrue();
    }
}
