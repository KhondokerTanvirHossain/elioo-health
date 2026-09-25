package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.VerifiedItems;
import com.elioo.baymax.outbound.domain.UrgencyContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hooks carry questions, not answers — and a question must not quietly behave like an answer.
 *
 * <p>{@link UrgencyContext} gives urgency the report's age, the patient's prior readings for the same marker
 * and unit, and whether they are currently unwell. Every one of those is a clinical decision nobody has
 * made: whether a three-year-old potassium of 6.4 is still a GO NOW, whether a known stable abnormality
 * should say something different from a new one, whether a tier applies only to a symptomatic patient.</p>
 *
 * <p>The fields exist so that answering those questions is a change to one rule instead of a change to every
 * signature between the pipeline and this service — the plumbing is the slow part and the doctor review is
 * already the critical path. But a hook that starts doing something before the policy exists is worse than
 * no hook: an invented old-report rule is indistinguishable in the data from one a clinician set, and would
 * be found only when a family acted on it.</p>
 *
 * <p>So this asserts the boring property directly: <b>a fully populated context produces exactly the verdict
 * an empty one does.</b> When a policy lands, this test is the one that must be deliberately changed, which
 * is the point.</p>
 */
class UrgencyHooksAreInertTest {

    private static final Instant NOW = Instant.parse("2026-09-24T09:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

    private final BaymaxProperties properties = new BaymaxProperties();
    private final UrgencyService urgency = new UrgencyService(properties, Clock.fixed(NOW, ZoneOffset.UTC));

    private DocumentFacts facts(Map<String, Object> value) {
        Document document = new Document(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "lab_report", null, null, "{\"document_type\":\"lab_report\",\"values\":[]}", 0.9,
                Document.Status.DONE, null, "claude-sonnet-5", null, 1, NOW, NOW,
                new VerifiedItems.Unverified(0, 0, 0, 0), null);
        return new DocumentFacts(document, "Test Patient", List.of(value), List.of(), Map.of(), "https://x/y");
    }

    private static Map<String, Object> potassium64() {
        return Map.of("name", "Potassium", "canonical_name", "potassium",
                "value", "6.4", "unit", "mmol/L", "ref_low", "3.5", "ref_high", "5.1");
    }

    /** A report three years old. Pending: whether age should change anything. Today: it must not. */
    @Test
    void anOldReportIsAssessedExactlyAsARecentOneIs() {
        var old = new UrgencyContext(LocalDate.of(2023, 1, 5), TODAY, Map.of(), Optional.empty());

        assertThat(urgency.assess(facts(potassium64()), old).level())
                .as("the old-report policy does not exist; age must not silently change the verdict")
                .isEqualTo(urgency.assess(facts(potassium64()), UrgencyContext.empty(TODAY)).level());
    }

    /** A known, stable, previously-abnormal marker. Pending: what a delta means. Today: nothing. */
    @Test
    void aKnownStableAbnormalityIsAssessedAsANewOneIs() {
        var withHistory = new UrgencyContext(TODAY, TODAY,
                Map.of(UrgencyContext.key("potassium", "mmol/L"), List.of(
                        new UrgencyContext.PriorValue("potassium", "mmol/L", 6.3, LocalDate.of(2026, 6, 1)),
                        new UrgencyContext.PriorValue("potassium", "mmol/L", 6.4, LocalDate.of(2026, 8, 1)))),
                Optional.empty());

        assertThat(urgency.assess(facts(potassium64()), withHistory).level())
                .as("stable at 6.3-6.4 for months reads identically to a first-ever 6.4, for now")
                .isEqualTo(urgency.assess(facts(potassium64()), UrgencyContext.empty(TODAY)).level());
    }

    /** Whether the patient is unwell. Pending: the symptom question itself. Today: no effect either way. */
    @Test
    void aSymptomaticPatientIsAssessedAsAnAsymptomaticOneIs() {
        var unwell = new UrgencyContext(TODAY, TODAY, Map.of(), Optional.of(true));
        var well = new UrgencyContext(TODAY, TODAY, Map.of(), Optional.of(false));

        assertThat(urgency.assess(facts(potassium64()), unwell).level())
                .isEqualTo(urgency.assess(facts(potassium64()), well).level());
    }

    /** Everything at once, against the context that knows nothing. */
    @Test
    void aFullyPopulatedContextChangesNothingAtAll() {
        var populated = new UrgencyContext(LocalDate.of(2021, 3, 3), TODAY,
                Map.of(UrgencyContext.key("potassium", "mmol/L"), List.of(
                        new UrgencyContext.PriorValue("potassium", "mmol/L", 4.1, LocalDate.of(2025, 1, 1)))),
                Optional.of(true));

        var withContext = urgency.assess(facts(potassium64()), populated);
        var without = urgency.assess(facts(potassium64()));

        assertThat(withContext.level()).isEqualTo(without.level());
        assertThat(withContext.reasons())
                .as("not even the reasons may differ — a hook must not leave a trace it had an opinion")
                .isEqualTo(without.reasons());
    }

    /** The hooks still have to compute what they promise, or they are not hooks. */
    @Test
    void theContextComputesReportAgeAndFindsPriorsByMarkerAndUnit() {
        var context = new UrgencyContext(LocalDate.of(2026, 9, 14), TODAY,
                Map.of(UrgencyContext.key("creatinine", "mg/dL"), List.of(
                        new UrgencyContext.PriorValue("creatinine", "mg/dL", 1.1, LocalDate.of(2026, 1, 1)))),
                Optional.empty());

        assertThat(context.reportAgeDays()).contains(10L);
        assertThat(context.priorFor("creatinine", "mg/dL")).hasSize(1);
        assertThat(context.priorFor("creatinine", "umol/L"))
                .as("a prior in a different unit is not a prior for this marker — a delta across units is not a delta")
                .isEmpty();
    }

    /** A page with no date, or an ambiguous one, yields no age rather than a guessed one. */
    @Test
    void aReportWithNoDateHasNoAge() {
        assertThat(UrgencyContext.empty(TODAY).reportAgeDays()).isEmpty();
    }
}
