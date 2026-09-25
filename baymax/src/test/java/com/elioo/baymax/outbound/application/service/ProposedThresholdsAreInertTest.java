package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.VerifiedItems;
import com.elioo.baymax.outbound.domain.MarkerThreshold;
import com.elioo.baymax.outbound.domain.Urgency;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The safety property of the whole per-marker mechanism: <b>a PROPOSED threshold can never change what a
 * family is told.</b>
 *
 * <p>The research numbers are loaded into config so a doctor can review them in a table, and sign-off is
 * meant to be a config change rather than a code change. That convenience is exactly the danger: the values
 * are sitting in the running system, one boolean away from live, and nobody has approved them. So the
 * inertness is asserted here rather than assumed from the fact that nothing reads them yet — "nothing reads
 * it yet" is a property of today's code, and this is a property of the design.</p>
 *
 * <p>Both directions, because a one-directional guard hides the dangerous half: a proposed entry that WOULD
 * change the verdict must not change it, and an active entry must actually work — otherwise sign-off day
 * produces a table of numbers that quietly do nothing.</p>
 */
class ProposedThresholdsAreInertTest {

    private static final Instant NOW = Instant.parse("2026-09-24T09:00:00Z");
    private static final UUID FAMILY = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();

    private final BaymaxProperties properties = new BaymaxProperties();
    private final UrgencyService urgency = new UrgencyService(properties, Clock.fixed(NOW, ZoneOffset.UTC));

    /** lab7's potassium: 6.4 against a printed 3.5–5.1. The stopgap calls this THIS_WEEK; RCPath says NOW. */
    private static Map<String, Object> potassium64() {
        return Map.of("name", "Potassium", "canonical_name", "potassium",
                "value", "6.4", "unit", "mmol/L", "ref_low", "3.5", "ref_high", "5.1");
    }

    private DocumentFacts factsWith(Map<String, Object> value) {
        Document document = new Document(UUID.randomUUID(), PATIENT, FAMILY, "lab_report", null, null,
                "{\"document_type\":\"lab_report\",\"values\":[]}", 0.9, Document.Status.DONE, null,
                "claude-sonnet-5", null, 1, NOW, NOW, new VerifiedItems.Unverified(0, 0, 0, 0), null);
        return new DocumentFacts(document, "Test Patient", List.of(value), List.of(), Map.of(),
                "https://medioo.eliooo.org/app/documents/x");
    }

    private void configure(MarkerThreshold... thresholds) {
        properties.getOutbound().setMarkerThresholds(new java.util.ArrayList<>(List.of(thresholds)));
    }

    private static MarkerThreshold potassiumNowAt(double nowHigh, MarkerThreshold.Status status) {
        return new MarkerThreshold("potassium", "mmol/L", 2.5, nowHigh, 3.5, 5.1,
                false, MarkerThreshold.Direction.NORMAL, status, false, "RCPath 2023 (proposed)");
    }

    /**
     * THE PROPERTY. A proposed threshold that would raise potassium 6.4 from THIS_WEEK to NOW must leave the
     * verdict exactly where the stopgap put it.
     */
    @Test
    void aProposedThresholdDoesNotChangeTheVerdict() {
        configure(potassiumNowAt(6.0, MarkerThreshold.Status.PROPOSED));

        var assessed = urgency.assess(factsWith(potassium64()));

        assertThat(assessed.level())
                .as("the stopgap's answer, unchanged: a proposed number has not been signed off")
                .isEqualTo(Urgency.THIS_WEEK);
        assertThat(assessed.reasons())
                .as("and the reason is the stopgap's, not the proposed rule's")
                .anySatisfy(r -> assertThat(r).contains("value_outside_range"));
    }

    /** The same entry, signed off, must actually take effect — or sign-off day changes nothing. */
    @Test
    void anActiveThresholdDoesChangeTheVerdict() {
        configure(potassiumNowAt(6.0, MarkerThreshold.Status.ACTIVE));

        assertThat(urgency.assess(factsWith(potassium64())).level())
                .as("signed off: potassium 6.4 is now a same-day result")
                .isEqualTo(Urgency.NOW);
    }

    /**
     * Sign-off must be able to lower a verdict too, not only raise it. ESR appears on no critical list, and
     * the stopgap currently sends a NOW for it.
     */
    @Test
    void anActiveNeverEscalatesEntryCanLowerTheStopgapsVerdict() {
        configure(new MarkerThreshold("esr", "mm/hr", null, null, null, null,
                true, MarkerThreshold.Direction.NORMAL, MarkerThreshold.Status.ACTIVE, false, "no critical list"));

        var facts = factsWith(Map.of("name", "ESR", "canonical_name", "esr",
                "value", "29", "unit", "mm/hr", "ref_low", "0", "ref_high", "10"));

        assertThat(urgency.assess(facts).level())
                .as("ESR is on no critical list; a single raised ESR is not a reason to send anyone anywhere")
                .isEqualTo(Urgency.ROUTINE);
    }

    /** And the same entry while merely proposed must leave that over-escalation in place. */
    @Test
    void aProposedNeverEscalatesEntryLeavesTheOverEscalationAlone() {
        configure(new MarkerThreshold("esr", "mm/hr", null, null, null, null,
                true, MarkerThreshold.Direction.NORMAL, MarkerThreshold.Status.PROPOSED, false, "no critical list"));

        var facts = factsWith(Map.of("name", "ESR", "canonical_name", "esr",
                "value", "29", "unit", "mm/hr", "ref_low", "0", "ref_high", "10"));

        // 29 against a printed ref_high of 10 is 2.9x, past the 2.0x multiple, so the stopgap calls a raised
        // ESR a GO NOW. That is the over-escalation batch 2 found and the research confirms — ESR is on no
        // critical list — and it must stay exactly as it is until a doctor signs the fix off.
        assertThat(urgency.assess(facts).level())
                .as("still the stopgap's over-escalation, because nobody has signed the fix off")
                .isEqualTo(Urgency.NOW);
    }

    /**
     * The unit rule, and the reason it exists. Creatinine 65 µmol/L is normal; 65 mg/dL is not compatible
     * with life. An ACTIVE threshold stated in mg/dL must not answer for a value printed in µmol/L.
     */
    @Test
    void aThresholdNeverAnswersForADifferentUnit() {
        configure(new MarkerThreshold("creatinine", "mg/dL", null, 4.0, null, 1.5,
                false, MarkerThreshold.Direction.NORMAL, MarkerThreshold.Status.ACTIVE, false, "test"));

        var facts = factsWith(Map.of("name", "Creatinine", "canonical_name", "creatinine",
                "value", "65", "unit", "umol/L", "ref_low", "59", "ref_high", "104"));

        assertThat(urgency.assess(facts).level())
                .as("65 umol/L is in range; the mg/dL entry must not fire on it")
                .isEqualTo(Urgency.ROUTINE);
    }

    /** A marker with no entry at all behaves exactly as it does today, and the reason records that. */
    @Test
    void aMarkerWithNoEntryFallsBackToTheStopgapAndSaysSo() {
        configure();

        var assessed = urgency.assess(factsWith(potassium64()));

        assertThat(assessed.level()).isEqualTo(Urgency.THIS_WEEK);
        assertThat(assessed.reasons())
                .as("the fallback is visible in the reasons, not silent")
                .anySatisfy(r -> assertThat(r).contains("stopgap"));
    }

    /** A symptom-gated entry is inert even when ACTIVE: the symptom question does not exist yet. */
    @Test
    void aSymptomGatedEntryIsInertEvenWhenActive() {
        configure(new MarkerThreshold("potassium", "mmol/L", 2.5, 6.0, 3.5, 5.1,
                false, MarkerThreshold.Direction.NORMAL, MarkerThreshold.Status.ACTIVE, true, "symptom-gated"));

        assertThat(urgency.assess(factsWith(potassium64())).level())
                .as("no symptom question exists, so a tier that depends on one cannot apply")
                .isEqualTo(Urgency.THIS_WEEK);
    }

    /** HDL: a high value is protective. Read as an upper limit, a healthy HDL escalates. */
    @Test
    void aHighIsGoodMarkerDoesNotEscalateOnAHighValue() {
        configure(new MarkerThreshold("hdl", "mg/dL", 25.0, null, 40.0, null,
                false, MarkerThreshold.Direction.HIGH_IS_GOOD, MarkerThreshold.Status.ACTIVE, false, "test"));

        var facts = factsWith(Map.of("name", "HDL Cholesterol", "canonical_name", "hdl",
                "value", "75", "unit", "mg/dL", "ref_low", "40", "ref_high", "60"));

        assertThat(urgency.assess(facts).level())
                .as("HDL 75 is protective, not an emergency")
                .isEqualTo(Urgency.ROUTINE);
    }

    /**
     * The unit that nearly slipped through. The config states creatinine's SI row as "umol/L" (ASCII u,
     * because a properties file is edited by hand), and a lab prints "µmol/L" with MICRO SIGN. NFKC folds
     * MICRO SIGN onto GREEK SMALL LETTER MU — not onto ASCII "u" — so the two do NOT match on normalisation
     * alone, and the threshold would silently never fire on a real page.
     *
     * <p>Silently is the problem. The value falls back to the stopgap and nothing looks broken, so a doctor
     * signs off a creatinine threshold and it does nothing for every lab that prints the proper symbol.</p>
     */
    @Test
    void aUnitWrittenWithMicroSignMatchesTheSameUnitWrittenWithAsciiU() {
        configure(new MarkerThreshold("creatinine", "umol/L", null, 442.0, null, 133.0,
                false, MarkerThreshold.Direction.NORMAL, MarkerThreshold.Status.ACTIVE, false, "UKKA"));

        var facts = factsWith(Map.of("name", "Creatinine", "canonical_name", "creatinine",
                "value", "500", "unit", "\u00b5mol/L", "ref_low", "59", "ref_high", "104"));

        // The LEVEL cannot prove this: 500 against a printed 59-104 is 4.8x the limit, so the stopgap
        // returns NOW too and the assertion would pass whether or not the threshold matched. The REASON is
        // what distinguishes them — "|threshold:UKKA" only appears when the unit actually matched.
        var assessed = urgency.assess(facts);

        assertThat(assessed.reasons())
                .as("a page printing MICRO SIGN must reach the threshold written with ASCII u")
                .anySatisfy(r -> assertThat(r).contains("threshold:UKKA"));
        assertThat(assessed.reasons())
                .as("and must NOT have fallen through to the stopgap")
                .noneSatisfy(r -> assertThat(r).contains("stopgap"));
        assertThat(assessed.level()).isEqualTo(Urgency.NOW);
    }

    /** And the Greek letter, which is what NFKC actually normalises MICRO SIGN to. */
    @Test
    void aUnitWrittenWithGreekMuAlsoMatches() {
        configure(new MarkerThreshold("creatinine", "umol/L", null, 442.0, null, 133.0,
                false, MarkerThreshold.Direction.NORMAL, MarkerThreshold.Status.ACTIVE, false, "UKKA"));

        var facts = factsWith(Map.of("name", "Creatinine", "canonical_name", "creatinine",
                "value", "500", "unit", "\u03bcmol/L", "ref_low", "59", "ref_high", "104"));

        assertThat(urgency.assess(facts).reasons())
                .as("same check on the reason, for the same reason")
                .anySatisfy(r -> assertThat(r).contains("threshold:UKKA"));
    }

    /** Folding micro onto u must not make genuinely different units equal. */
    @Test
    void foldingMicroDoesNotMakeDifferentUnitsEqual() {
        configure(new MarkerThreshold("creatinine", "umol/L", null, 442.0, null, 133.0,
                false, MarkerThreshold.Direction.NORMAL, MarkerThreshold.Status.ACTIVE, false, "UKKA"));

        // In range for mmol/L, and far below the umol/L threshold's numbers. If the units were folded
        // together this would read 5.0 against a 442 threshold and pass as ROUTINE by luck; what proves the
        // units stayed separate is the REASON — it must be the stopgap's fallback, not the threshold.
        var facts = factsWith(Map.of("name", "Creatinine", "canonical_name", "creatinine",
                "value", "0.09", "unit", "mmol/L", "ref_low", "0.06", "ref_high", "0.11"));

        var assessed = urgency.assess(facts);

        assertThat(assessed.level()).isEqualTo(Urgency.ROUTINE);
        assertThat(assessed.reasons())
                .as("mmol/L must not be answered by the umol/L row — a 1000x error must not be folded away")
                .noneSatisfy(r -> assertThat(r).contains("UKKA"));
    }
}
