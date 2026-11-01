package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.common.time.PatientCalendar;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.outbound.domain.MarkerThreshold;
import com.elioo.baymax.outbound.domain.PrintedRange;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyContext;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import com.elioo.baymax.outbound.domain.UrgencyReason;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Urgency, computed in code from the persisted extraction and nothing else (BMX-6):
 * <ul>
 *   <li>NOW — a value outside its <em>printed</em> reference range that is critical under the STOPGAP rule
 *       ({@link #criticalKind}: ≥ 2.0 × ref_high, ≤ 0.5 × ref_low, or the page's own text marks it critical
 *       near the value), or the document text carries a verbatim emergency phrase (config list, both scripts).</li>
 *   <li>THIS_WEEK — a value outside its printed range but not critical, a diagnosis line on anything but a
 *       prescription (where the doctor has just been seen and a diagnosis is expected), or a follow-up due
 *       date already past.</li>
 *   <li>ROUTINE — everything else. <b>No printed range → no escalation from values</b>, whatever the number.</li>
 * </ul>
 * The model's own {@code flag} is ignored here. Assessment only ever raises.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UrgencyService {

    private final BaymaxProperties properties;
    private final Clock clock;

    /**
     * Urgency for a document, with everything the rules are allowed to know beyond the page.
     *
     * <p>{@code context} carries the report's age, the patient's prior readings and whether they are
     * currently unwell. <b>No rule consults any of them yet</b> — they are hooks for policies a clinician
     * has not written, and {@code UrgencyHooksAreInertTest} asserts that a populated context produces the
     * same verdict as an empty one. Inventing an old-report or delta policy here would be indistinguishable
     * in the data from one a doctor set.</p>
     */
    public UrgencyAssessment assess(DocumentFacts facts, UrgencyContext context) {
        return assess(facts);
    }

    public UrgencyAssessment assess(DocumentFacts facts) {
        UrgencyAssessment a = UrgencyAssessment.routine();
        String text = facts.document().extractionJson() == null ? "" : facts.document().extractionJson();

        // F2: one verdict per shown value, from the branch that decides whether it raises the document. The document
        // is raised from the rows afterwards, so a raising row and the banner share one reason string by construction.
        List<ValueVerdict> verdicts = new ArrayList<>();
        for (Map<String, Object> v : facts.values()) {
            String name = String.valueOf(v.getOrDefault("canonical_name", v.get("name"))).toLowerCase(Locale.ROOT);
            UUID id = v.get("id") instanceof UUID u ? u : null;
            Optional<Verdict> perMarker = perMarkerVerdict(v);
            if (perMarker.isPresent()) {
                Verdict verdict = perMarker.get();
                if (verdict.level() != Urgency.ROUTINE) {
                    verdicts.add(new ValueVerdict(id, verdict.level() == Urgency.NOW ? ValueVerdict.Kind.CRITICAL : ValueVerdict.Kind.OUTSIDE,
                            verdict.level(), verdict.side(), verdict.reasonFor(name)));
                } else {
                    verdicts.add(unraised(id, v, name, "|threshold:" + verdict.threshold().source(), text));
                }
                continue;                                    // a signed-off threshold replaces the stopgap
            }
            switch (criticalKind(v, text)) {
                case CRITICAL_HIGH -> verdicts.add(new ValueVerdict(id, ValueVerdict.Kind.CRITICAL, Urgency.NOW, ValueVerdict.Side.HIGH,
                        UrgencyReason.VALUE_CRITICAL.of(name, STOPGAP)));
                case CRITICAL_LOW -> verdicts.add(new ValueVerdict(id, ValueVerdict.Kind.CRITICAL, Urgency.NOW, ValueVerdict.Side.LOW,
                        UrgencyReason.VALUE_CRITICAL.of(name, STOPGAP)));
                case OUTSIDE -> verdicts.add(new ValueVerdict(id, ValueVerdict.Kind.OUTSIDE, Urgency.THIS_WEEK, printedSide(v),
                        UrgencyReason.VALUE_OUTSIDE_RANGE.of(name, STOPGAP)));
                case NONE -> verdicts.add(unraised(id, v, name, STOPGAP, text));
            }
        }
        for (ValueVerdict r : verdicts) {
            if (r.level() != Urgency.ROUTINE) {
                a = a.raise(r.level(), r.reason());          // the row and the banner share one string, by construction
            }
        }

        // DR-28: a value we read but could not point at on the page is never shown — and must still be able to
        // raise urgency. It is not persisted (crop_key is NOT NULL), so it is absent from facts.values() and
        // invisible to the loop above. lab10 was one value, out of range, dropped for want of a crop: the only
        // finding on the page, silently discarded by the rule meant to catch it.
        //
        // raise() only ever moves urgency up, so an unverified value cannot lower a verdict the shown values
        // already reached. The reason carries the marker name, never the number: a value we could not verify
        // does not get to put a figure in front of a family.
        for (Map<String, Object> v : unverifiedValues(facts)) {
            String name = String.valueOf(v.getOrDefault("canonical_name", v.get("name"))).toLowerCase(Locale.ROOT);
            switch (criticalKind(v, text)) {
                case CRITICAL_HIGH, CRITICAL_LOW -> a = a.raise(Urgency.NOW, UrgencyReason.UNVERIFIED_VALUE_CRITICAL.of(name, null));
                case OUTSIDE -> a = a.raise(Urgency.THIS_WEEK, UrgencyReason.UNVERIFIED_VALUE_OUTSIDE_RANGE.of(name, null));
                default -> { }
            }
        }

        LocalDate today = PatientCalendar.today(clock);   // the patient's day (F7), not UTC's
        for (Map<String, Object> f : facts.followUps()) {
            Object due = f.get("due_date");
            if (due != null && !String.valueOf(due).isBlank()) {
                try {
                    if (LocalDate.parse(String.valueOf(due)).isBefore(today)) {
                        a = a.raise(Urgency.THIS_WEEK, UrgencyReason.FOLLOW_UP_PAST_DUE.of(null, null));
                    }
                } catch (RuntimeException ignored) {
                    // an unparseable date is not a reason to escalate or to fail
                }
            }
        }

        // A diagnosis line means "see a doctor" only where a doctor has not just been seen. On a prescription
        // the patient was seen and the doctor wrote it — expected, not a reason (PO ruling 2026-09-18, after the
        // first real walkthrough parked a message for exactly this). Urgency reasons are evaluated against
        // document_type, never in isolation.
        boolean prescription = "prescription".equalsIgnoreCase(facts.document().documentType());
        if (!prescription && facts.clinicalContext() != null
                && facts.clinicalContext().get("diagnosis") instanceof List<?> dx && !dx.isEmpty()) {
            a = a.raise(Urgency.THIS_WEEK, UrgencyReason.DIAGNOSIS_PRESENT.of(null, null));
        }

        if (facts.document().extractionJson() != null && containsEmergencyPhrase(facts.document().extractionJson())) {
            a = a.raise(Urgency.NOW, UrgencyReason.TEXT_EMERGENCY_PHRASE.of(null, null));
        }
        // A shown value without an observation id raised the document above and has no row to hang a verdict on: it
        // emits none (as DR-28's unverified values), so the port's refusal of an id-less verdict cannot cost the level.
        return a.withValues(verdicts.stream().filter(v -> v.observationId() != null).toList());
    }

    /**
     * Marks a reason as produced by the stopgap rather than by a signed-off threshold.
     *
     * <p>Visible in {@code urgency_reasons} on purpose: a verdict reached by a multiple nobody has approved
     * should not be indistinguishable from one a clinician set. It is also how the dry-run table tells the
     * doctor which rule produced each row.</p>
     */
    static final String STOPGAP = "|stopgap";

    /** A per-marker verdict and the entry that produced it, so the reason can name the rule. */
    private record Verdict(Urgency level, MarkerThreshold threshold, double value) {
        String reasonFor(String name) {
            UrgencyReason kind = level == Urgency.NOW ? UrgencyReason.VALUE_CRITICAL : UrgencyReason.VALUE_OUTSIDE_RANGE;
            return kind.of(name, "|threshold:" + threshold.source());
        }

        ValueVerdict.Side side() {
            return threshold.side(value);
        }
    }

    /**
     * F2: the verdict of a row that raises nothing. INSIDE only when the code proves it ({@link #insideByCode}); OUTSIDE
     * (side from the printed range) when the printed range places it outside but the rule in force does not escalate
     * (DR-17's never-escalating markers, a signed-off tier not reached); every other shape is UNKNOWN, which withholds
     * reassurance. {@code rule} is the rule that declined to raise: {@code |stopgap} or {@code |threshold:<source>}.
     */
    private ValueVerdict unraised(UUID id, Map<String, Object> v, String name, String rule, String text) {
        if (insideByCode(v)) {
            return new ValueVerdict(id, ValueVerdict.Kind.INSIDE, Urgency.ROUTINE, null, "value_inside:" + name + rule);
        }
        if (criticalKind(v, text) != Kind.NONE) {
            return new ValueVerdict(id, ValueVerdict.Kind.OUTSIDE, Urgency.ROUTINE, printedSide(v),
                    "value_outside_no_escalation:" + name + rule);
        }
        return new ValueVerdict(id, ValueVerdict.Kind.UNKNOWN, Urgency.ROUTINE, null, "value_unknown:" + name + rule);
    }

    /** Which end of the printed range a value outside it sits at; only called once criticalKind placed it outside. */
    private static ValueVerdict.Side printedSide(Map<String, Object> v) {
        Optional<Double> value = number(v.get("value"));
        Optional<Double> high = number(v.get("ref_high"));
        return value.isPresent() && high.isPresent() && value.get() > high.get() ? ValueVerdict.Side.HIGH : ValueVerdict.Side.LOW;
    }

    /**
     * The signed-off threshold for this value, if there is one.
     *
     * <p>Empty means "no live entry covers this marker in this unit" — a missing marker, a PROPOSED entry, a
     * symptom-gated one, or the right marker in the wrong unit — and the caller falls back to the stopgap.
     * Returning empty rather than ROUTINE is the safe direction: an entry nobody approved must not be able to
     * silence an escalation the stopgap would have made.</p>
     */
    private Optional<Verdict> perMarkerVerdict(Map<String, Object> v) {
        List<MarkerThreshold> configured = properties.getOutbound().getMarkerThresholds();
        if (configured == null || configured.isEmpty()) {
            return Optional.empty();
        }
        Object canonical = v.getOrDefault("canonical_name", v.get("name"));
        if (canonical == null) {
            return Optional.empty();
        }
        String unit = v.get("unit") == null ? "" : String.valueOf(v.get("unit"));
        Optional<Double> value = number(v.get("value"));
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return configured.stream()
                .filter(MarkerThreshold::isLive)
                .filter(t -> t.matches(String.valueOf(canonical), unit))
                .findFirst()
                .flatMap(t -> t.assess(value.get()).map(level -> new Verdict(level, t, value.get())));
    }

    /** Whole-token match against the config phrases, both scripts; "urgently" is a token, "surgent" is not. */
    boolean containsEmergencyPhrase(String text) {
        return com.elioo.baymax.outbound.domain.EmergencyPhrases.contains(text, properties.getOutbound().getEmergencyPhrases());
    }


    /**
     * Values the model read that never became observations, because no crop could be located for them.
     *
     * <p>Recovered from the stored extraction and matched against what IS persisted: anything in the
     * extraction with no corresponding observation was dropped. Returns nothing when the document records no
     * drops, so a document with a complete set costs no work and behaves exactly as before.
     */
    private List<Map<String, Object>> unverifiedValues(DocumentFacts facts) {
        if (facts.document().unverified() == null || facts.document().unverified().values() == 0) {
            return List.of();
        }
        String json = facts.document().extractionJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode values = MAPPER.readTree(json).path("values");
            Set<String> shown = facts.values().stream()
                    .map(v -> key(String.valueOf(v.get("name")), String.valueOf(v.get("value"))))
                    .collect(java.util.stream.Collectors.toSet());
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode v : values) {
                String name = v.path("name").asText("");
                String value = v.path("value").asText("");
                if (shown.contains(key(name, value))) {
                    continue;
                }
                Map<String, Object> row = new HashMap<>();
                row.put("name", name);
                row.put("canonical_name", v.path("canonical_name").asText(null));
                row.put("value", value);
                row.put("ref_low", v.path("ref_low").isNull() ? null : v.path("ref_low").asText(null));
                row.put("ref_high", v.path("ref_high").isNull() ? null : v.path("ref_high").asText(null));
                out.add(row);
            }
            return out;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // an unreadable extraction is not a reason to escalate or to fail
            log.warn("[baymax] could not read unverified values documentId={}: {}",
                    facts.document().id(), e.toString());
            return List.of();
        }
    }

    private static String key(String name, String value) {
        return (name == null ? "" : name.trim().toLowerCase(Locale.ROOT)) + "|"
                + (value == null ? "" : value.trim());
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * The reading a cell states, or empty when it does not state one: {@link PrintedRange#number}, which owns the rule
     * (and its history) so the pages can read it without depending on this service.
     */
    static Optional<Double> number(Object raw) {
        return PrintedRange.number(raw);
    }

    /** Where a value sits against its printed range. NONE also covers "no printed range". */
    public enum Kind { NONE, OUTSIDE, CRITICAL_HIGH, CRITICAL_LOW }

    /**
     * STOPGAP (PO ruling 2026-09-18), shared by urgency and the explanation's standout lines: critical when
     * the value is ≥ {@code criticalHighMultiple} × ref_high, ≤ {@code criticalLowMultiple} × ref_low, or the
     * page's own text marks it critical within {@value #NEAR_CHARS} characters of the value. The correct
     * source is per-marker panic thresholds from a clinician, not a multiple — pending, blocking week 0.
     * No printed range → NONE, whatever the number.
     */
    public Kind criticalKind(Map<String, Object> v, String documentText) {
        Optional<Double> value = number(v.get("value"));
        Optional<Double> low = number(v.get("ref_low"));
        Optional<Double> high = number(v.get("ref_high"));
        if (value.isEmpty() || low.isEmpty() || high.isEmpty() || high.get() < low.get()) {
            return Kind.NONE;
        }
        double x = value.get();
        if (x >= low.get() && x <= high.get()) {
            return Kind.NONE;
        }
        BaymaxProperties.Outbound cfg = properties.getOutbound();
        boolean marked = markedCriticalNearValue(documentText, String.valueOf(v.get("value")));
        if (x > high.get()) {
            return x >= cfg.getCriticalHighMultiple() * high.get() || marked ? Kind.CRITICAL_HIGH : Kind.OUTSIDE;
        }
        return x <= cfg.getCriticalLowMultiple() * low.get() || marked ? Kind.CRITICAL_LOW : Kind.OUTSIDE;
    }

    /** INSIDE by code (D35, F2's INSIDE verdict): {@link PrintedRange#insideByCode}, the one copy of the rule. */
    static boolean insideByCode(Map<String, Object> v) {
        return PrintedRange.insideByCode(v);
    }

    static final int NEAR_CHARS = 60;

    /** The page's own marking: a critical marker word within NEAR_CHARS of an occurrence of the value's text. */
    boolean markedCriticalNearValue(String documentText, String value) {
        if (documentText == null || value == null || value.isBlank()) {
            return false;
        }
        String hay = " " + DocumentFacts.fold(documentText).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}.]+", " ") + " ";
        String needle = DocumentFacts.fold(value).toLowerCase(Locale.ROOT);
        for (int at = hay.indexOf(needle); at >= 0; at = hay.indexOf(needle, at + 1)) {
            String window = hay.substring(Math.max(0, at - NEAR_CHARS), Math.min(hay.length(), at + needle.length() + NEAR_CHARS));
            for (String marker : properties.getOutbound().getCriticalMarkers()) {
                if (window.contains(" " + marker.toLowerCase(Locale.ROOT).trim() + " ")) {
                    return true;
                }
            }
        }
        return false;
    }
}
