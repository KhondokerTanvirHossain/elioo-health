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
        m.put("id", java.util.UUID.randomUUID());          // every persisted observation has one; F2 joins on it
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

    /** One claim fixture: the facts, and whether the model's flag or critical mark (not the code) withdraws the claim. */
    record Fixture(String name, DocumentFacts facts, boolean modelFlagged) {
    }

    /**
     * Every document this class judges, as one list, so ValueVerdictTest can hold F2 (c) over all of them: the claim
     * holds exactly when every stored verdict is INSIDE.
     */
    static List<Fixture> fixtures() {
        Map<String, Object> marked = value("HbA1c", "5.2", "4.0", "5.6", "");
        marked.put("critical", true);
        return List.of(
                new Fixture("prescription, no ranges", facts(List.of(value("BP", "130", null, null, null), value("BP", "89", null, null, null))), false),
                new Fixture("all inside", facts(List.of(value("HbA1c", "5.2", "4.0", "5.6", "normal"), value("Creatinine", "0.9", "0.6", "1.1", "normal"))), false),
                new Fixture("one outside", facts(List.of(value("HbA1c", "9.8", "4.0", "5.6", "high"), value("Creatinine", "0.9", "0.6", "1.1", "normal"))), true),
                new Fixture("ranged and unranged", facts(List.of(value("HbA1c", "5.2", "4.0", "5.6", "normal"), value("BP", "130", null, null, null))), false),
                new Fixture("no values", facts(List.of()), false),
                new Fixture("LDL 250 vs <100", facts(List.of(value("LDL", "250", null, "<100", ""))), false),
                new Fixture("LDL 250 vs 100 only", facts(List.of(value("LDL", "250", null, "100", null))), false),
                new Fixture("LDL 90 vs <100", facts(List.of(value("LDL", "90", null, "<100", ""))), false),
                new Fixture("HDL 52 vs >40", facts(List.of(value("HDL", "52", ">40", null, ""))), false),
                new Fixture("outside, model said normal", facts(List.of(value("Creatinine", "2.4", "0.6", "1.1", "normal"))), false),
                new Fixture("a bound as the value", facts(List.of(value("eGFR", "> 89", "60", "120", ""))), false),
                new Fixture("qualitative", facts(List.of(value("HBsAg", "Positive", "Negative", "Negative", ""))), false),
                new Fixture("ambiguous comma", facts(List.of(value("TSH", "1,23", "0.5", "1.5", ""))), false),
                new Fixture("a pair", facts(List.of(value("BP", "130 / 80", "90", "140", ""))), false),
                new Fixture("inverted range", facts(List.of(value("X", "7", "10", "5", ""))), false),
                new Fixture("inside, model flag high", facts(List.of(value("HbA1c", "5.2", "4.0", "5.6", "high"))), true),
                new Fixture("inside, critical mark", facts(List.of(marked)), true),
                new Fixture("an unverified value", factsWithUnverified(List.of(value("HbA1c", "5.2", "4.0", "5.6", "")), 1), false),
                new Fixture("all inside, blank flags", facts(List.of(value("HbA1c", "5.2", "4.0", "5.6", ""),
                        value("WBC", "6,100", "4,000", "11,000", null), value("Creatinine", "1.1", "0.6", "1.1", null))), false),
                new Fixture("Bangla digits", factsWithUnverified(List.of(value("HbA1c", "৫.২", "৪.০", "৫.৬", "normal")), 0), false));
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

    // A value that MEETS its one-sided range: these fail only if a one-sided range is treated as inside. 250 is
    // above 100 under any reading of "<100", so the cases above cannot tell "one-sided is unknown" from "a missing
    // bound is no limit". 90 against "<100" can; these are the t2 phone fixture's values.

    @Test
    void anLdlOf90AgainstALessThan100RangeIsNotInside() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("LDL", "90", null, "<100", "")))))
                .as("LDL 90 against '<100' is a one-sided range: unknown, not inside (D35)")
                .isFalse();
    }

    @Test
    void anLdlOf90AgainstAnUpperBoundOnlyIsNotInside() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("LDL", "90", null, "100", null)))))
                .as("LDL 90 with ref_low null and ref_high 100 is one-sided: unknown, not inside (D35)")
                .isFalse();
    }

    @Test
    void anLdlOf90AgainstAnUpperBoundAndABlankLowIsNotInside() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("LDL", "90", "", "100", null)))))
                .as("LDL 90 with ref_low blank and ref_high 100 is one-sided: unknown, not inside (D35)")
                .isFalse();
    }

    @Test
    void anHdlOf52AgainstAGreaterThan40RangeIsNotInside() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("HDL", "52", ">40", null, "")))))
                .as("HDL 52 against '>40' is a one-sided range: unknown, not inside (D35)")
                .isFalse();
    }

    /** Code beats the model: the flag may withdraw the claim, never grant it. */
    @Test
    void aValueOutsideItsRangeByCodeCannotBeRescuedByTheModelsNormalFlag() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("Creatinine", "2.4", "0.6", "1.1", "normal")))))
                .as("the model's flag said normal; 2.4 is outside 0.6–1.1")
                .isFalse();
    }

    // No reading means no comparison, and no comparison means no claim (UrgencyService.number refuses these).
    // One test per case, so each one's red on the pre-fix source is visible on its own.

    @Test
    void aBoundAsTheValueCannotMakeTheClaim() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(value("eGFR", "> 89", "60", "120", "")))))
                .as("'> 89' is a bound, not a reading").isFalse();
    }

    @Test
    void aQualitativeResultCannotMakeTheClaim() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(value("HBsAg", "Positive", "Negative", "Negative", "")))))
                .as("'Positive' against 'Negative' has no number to compare").isFalse();
    }

    @Test
    void anAmbiguousCommaCannotMakeTheClaim() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(value("TSH", "1,23", "0.5", "1.5", "")))))
                .as("'1,23' is 1.23 or 123; it is not read").isFalse();
    }

    @Test
    void aPairInOneCellCannotMakeTheClaim() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(value("BP", "130 / 80", "90", "140", "")))))
                .as("'130 / 80' is two readings, not one").isFalse();
    }

    /**
     * Documents an implied property, not a separate code path: no value satisfies low <= v <= high when low > high,
     * so an inverted range is never inside. It is red on the pre-fix source (which never compared at all), but no
     * clause of insideByCode exists for it alone.
     */
    @Test
    void anInvertedRangeHasNoInside() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(value("X", "7", "10", "5", ""))))).isFalse();
    }

    // ---- the withdraw-only rules: inside by code, so only the flag or the critical mark can withdraw ----

    /** The model's flag alone withdraws: the value is inside by code, so no other rule produces this verdict. */
    @Test
    void aValueInsideByCodeIsWithdrawnByTheModelsNonNormalFlag() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("HbA1c", "5.2", "4.0", "5.6", "high")))))
                .as("5.2 is inside 4.0-5.6 by code; only the model's 'high' flag can withdraw the claim")
                .isFalse();
    }

    /**
     * The critical mark alone withdraws. Reachable: UrgencyService does not read the {@code critical} field (it reads
     * the page's own marker words), so a value marked critical but inside by code arrives here at ROUTINE.
     */
    @Test
    void aValueInsideByCodeIsWithdrawnByItsCriticalMark() {
        Map<String, Object> marked = value("HbA1c", "5.2", "4.0", "5.6", "");
        marked.put("critical", true);
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(marked))))
                .as("5.2 is inside 4.0-5.6 by code; only the critical mark can withdraw the claim")
                .isFalse();
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
