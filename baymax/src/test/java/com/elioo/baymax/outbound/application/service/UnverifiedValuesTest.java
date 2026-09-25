package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.VerifiedItems;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-28's surviving half and DR-31: a value we read but could not point at still counts.
 *
 * <p>A value with no locatable crop is never persisted ({@code crop_key} is NOT NULL, "no number without its
 * source"), so it never reaches {@code observationsOf} and never reaches urgency. lab10 in batch 2 was the
 * case that mattered: a clear page, read correctly at 0.9 confidence, whose single value was an out-of-range
 * uric acid — dropped, and therefore invisible to every rule that exists to catch exactly that.
 *
 * <p>The rule has a direction. An unverified value **may raise** urgency and **never lower** it, is **never
 * shown** and its number **never appears in the body** — so a value we could not verify cannot reassure
 * anyone, and cannot put a number in front of a family that we could not point at on their own page.
 */
class UnverifiedValuesTest {

    private static final Instant NOW = Instant.parse("2026-09-24T09:00:00Z");
    private static final UUID FAMILY = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();

    private final BaymaxProperties properties = new BaymaxProperties();
    private final UrgencyService urgency = new UrgencyService(properties, Clock.fixed(NOW, ZoneOffset.UTC));

    /** lab10's extraction, as stored: one value, out of range, whose crop could not be located. */
    private static final String LAB10_EXTRACTION = """
            {"document_type":"lab_report","values":[
              {"name":"Uric Acid","canonical_name":"uric_acid","value":"2.1","unit":"mg/dL",
               "ref_low":"3.4","ref_high":"7.0"}]}
            """;

    private Document documentWithDroppedValues(String extractionJson, int droppedValues) {
        return new Document(UUID.randomUUID(), PATIENT, FAMILY, "lab_report", null, null,
                extractionJson, 0.9, Document.Status.DONE, null, "claude-sonnet-5", null, 1,
                NOW, NOW, new VerifiedItems.Unverified(droppedValues, 0, 0, 0), null);
    }

    private DocumentFacts factsShowingNothing(String extractionJson, int droppedValues) {
        // values() is the PERSISTED list — empty, because nothing could be cropped
        return new DocumentFacts(documentWithDroppedValues(extractionJson, droppedValues),
                "Test Patient", List.of(), List.of(), Map.of(), "https://medioo.eliooo.org/app/documents/x");
    }

    /**
     * The lab10 case. Nothing is shown, so nothing can be judged from what is shown — but the value was read,
     * it is out of its printed range, and urgency must see it.
     */
    @Test
    void anUnverifiedOutOfRangeValueStillRaisesUrgency() {
        UrgencyAssessment assessed = urgency.assess(factsShowingNothing(LAB10_EXTRACTION, 1));

        assertThat(assessed.level())
                .as("uric acid 2.1 against a printed range of 3.4-7.0 is out of range, crop or no crop")
                .isEqualTo(Urgency.THIS_WEEK);
        assertThat(assessed.reasons()).anySatisfy(r -> assertThat(r).contains("uric_acid"));
    }

    /** The direction that must hold: an unverified value may raise urgency, never lower it. */
    @Test
    void anUnverifiedNormalValueDoesNotLowerUrgency() {
        String withCritical = """
                {"document_type":"lab_report","values":[
                  {"name":"Potassium","canonical_name":"potassium","value":"12.0","unit":"mmol/L",
                   "ref_low":"3.5","ref_high":"5.1"},
                  {"name":"Sodium","canonical_name":"sodium","value":"140","unit":"mmol/L",
                   "ref_low":"135","ref_high":"145"}]}
                """;

        UrgencyAssessment assessed = urgency.assess(factsShowingNothing(withCritical, 2));

        assertThat(assessed.level())
                .as("a normal unverified value must not pull a critical one back down")
                .isEqualTo(Urgency.NOW);
    }

    /** A document with nothing dropped behaves exactly as before: only persisted values are read. */
    @Test
    void aDocumentWithNothingDroppedIsUnchanged() {
        DocumentFacts facts = new DocumentFacts(
                documentWithDroppedValues(LAB10_EXTRACTION, 0), "Test Patient",
                List.of(Map.of("name", "Uric Acid", "canonical_name", "uric_acid",
                        "value", "5.0", "ref_low", "3.4", "ref_high", "7.0")),
                List.of(), Map.of(), "https://x/y");

        assertThat(urgency.assess(facts).level())
                .as("the persisted value is in range and nothing was dropped")
                .isEqualTo(Urgency.ROUTINE);
    }

    /** An unverified value with no printed range escalates nothing, exactly as a persisted one would not. */
    @Test
    void anUnverifiedValueWithNoPrintedRangeEscalatesNothing() {
        String noRange = """
                {"document_type":"lab_report","values":[
                  {"name":"SGPT","canonical_name":"alt","value":"55.32","unit":"U/L",
                   "ref_low":null,"ref_high":null}]}
                """;

        assertThat(urgency.assess(factsShowingNothing(noRange, 1)).level())
                .as("no printed range, no escalation — crop or no crop")
                .isEqualTo(Urgency.ROUTINE);
    }
}
