package com.elioo.baymax.outbound.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What urgency is allowed to know beyond the document itself.
 *
 * <p><b>Hooks, not behaviour.</b> Each field below is a question a clinician has not yet answered, and the
 * code deliberately does nothing with any of them. They exist so that answering the question is a change to
 * one rule rather than a change to every signature between the pipeline and {@code UrgencyService} — the
 * plumbing is the slow part, and the doctor review is already the critical path.</p>
 *
 * <p>Carrying an unanswered question in a field is safer than inventing a policy for it: an invented
 * old-report rule, or an invented delta rule, would be indistinguishable in the data from one a clinician
 * set, and would be discovered only when a family acted on it.</p>
 *
 * @param documentDate  the date printed on the report, when the page carried one. <b>Pending:</b> the
 *                      old-report policy. A potassium of 6.4 from a report three years old is not the same
 *                      message as one from yesterday, but whether it should be lowered, left, or annotated
 *                      is a clinical decision. Null when the page printed no date, or printed an ambiguous
 *                      one (lab8's 07/11/2021 — see the batch-2 findings).
 * @param today         the date the assessment runs, so report age is computable without reaching for a
 *                      clock inside the rule and so a test can fix it.
 * @param priorValues   the patient's previous readings of the markers on this document, keyed by
 *                      {@code canonical_name|unit} — the same key the thresholds use, because a delta
 *                      across a unit change is not a delta (the trend query's missing unit predicate is a
 *                      live false-nudge path in its own right). <b>Pending:</b> what a delta means. A first
 *                      abnormal reading and a known, stable, treated abnormality are clinically different
 *                      and currently produce an identical message.
 * @param patientUnwell whether the patient has said they are currently symptomatic. <b>Pending:</b> the
 *                      symptom question itself — nothing asks it yet, so this is always
 *                      {@link java.util.Optional#empty()} and every symptom-gated threshold stays inert.
 */
public record UrgencyContext(
        LocalDate documentDate,
        LocalDate today,
        Map<String, List<PriorValue>> priorValues,
        Optional<Boolean> patientUnwell) {

    /** One earlier reading of a marker, in the unit it was reported in. */
    public record PriorValue(String canonicalName, String unit, double value, LocalDate observedAt) {
    }

    public UrgencyContext {
        priorValues = priorValues == null ? Map.of() : Map.copyOf(priorValues);
        patientUnwell = patientUnwell == null ? Optional.empty() : patientUnwell;
    }

    /** The context that knows nothing: what every caller passes until the policies exist. */
    public static UrgencyContext empty(LocalDate today) {
        return new UrgencyContext(null, today, Map.of(), Optional.empty());
    }

    /** The key both the threshold table and the delta hook join on: marker AND unit, never marker alone. */
    public static String key(String canonicalName, String unit) {
        return (canonicalName == null ? "" : canonicalName.trim().toLowerCase(java.util.Locale.ROOT))
                + "|" + (unit == null ? "" : unit.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** How old the report is in days, when it carried a date at all. */
    public Optional<Long> reportAgeDays() {
        if (documentDate == null || today == null) {
            return Optional.empty();
        }
        return Optional.of(java.time.temporal.ChronoUnit.DAYS.between(documentDate, today));
    }

    /** Earlier readings of this marker in this unit, oldest first; empty when there are none. */
    public List<PriorValue> priorFor(String canonicalName, String unit) {
        return priorValues.getOrDefault(key(canonicalName, unit), List.of());
    }
}
