package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Urgency, computed in code from the persisted extraction and nothing else (BMX-6):
 * <ul>
 *   <li>NOW — a value outside its <em>printed</em> reference range that is critical under the STOPGAP rule
 *       ({@link #criticalKind}: ≥ 2.0 × ref_high, ≤ 0.5 × ref_low, or the page's own text marks it critical
 *       near the value), or the document text carries a verbatim emergency phrase (config list, both scripts).</li>
 *   <li>THIS_WEEK — a value outside its printed range but not critical, a diagnosis line present, or a
 *       follow-up due date already past.</li>
 *   <li>ROUTINE — everything else. <b>No printed range → no escalation from values</b>, whatever the number.</li>
 * </ul>
 * The model's own {@code flag} is ignored here. Assessment only ever raises.
 */
@Service
@RequiredArgsConstructor
public class UrgencyService {

    private final BaymaxProperties properties;
    private final Clock clock;

    public UrgencyAssessment assess(DocumentFacts facts) {
        UrgencyAssessment a = UrgencyAssessment.routine();
        String text = facts.document().extractionJson() == null ? "" : facts.document().extractionJson();

        for (Map<String, Object> v : facts.values()) {
            String name = String.valueOf(v.getOrDefault("canonical_name", v.get("name"))).toLowerCase(Locale.ROOT);
            switch (criticalKind(v, text)) {
                case CRITICAL_HIGH, CRITICAL_LOW -> a = a.raise(Urgency.NOW, "value_critical:" + name);
                case OUTSIDE -> a = a.raise(Urgency.THIS_WEEK, "value_outside_range:" + name);
                default -> { }                               // inside the range, or no printed range: nothing
            }
        }

        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        for (Map<String, Object> f : facts.followUps()) {
            Object due = f.get("due_date");
            if (due != null && !String.valueOf(due).isBlank()) {
                try {
                    if (LocalDate.parse(String.valueOf(due)).isBefore(today)) {
                        a = a.raise(Urgency.THIS_WEEK, "follow_up_past_due");
                    }
                } catch (RuntimeException ignored) {
                    // an unparseable date is not a reason to escalate or to fail
                }
            }
        }

        if (facts.clinicalContext() != null && facts.clinicalContext().get("diagnosis") instanceof List<?> dx && !dx.isEmpty()) {
            a = a.raise(Urgency.THIS_WEEK, "diagnosis_present");
        }

        if (facts.document().extractionJson() != null && containsEmergencyPhrase(facts.document().extractionJson())) {
            a = a.raise(Urgency.NOW, "text_emergency_phrase");
        }
        return a;
    }

    /** Whole-token match against the config phrases, both scripts; "urgently" is a token, "surgent" is not. */
    boolean containsEmergencyPhrase(String text) {
        String hay = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ") + " ";
        for (String phrase : properties.getOutbound().getEmergencyPhrases()) {
            if (hay.contains(" " + phrase.toLowerCase(Locale.ROOT).trim() + " ")) {
                return true;
            }
        }
        return false;
    }

    static Optional<Double> number(Object raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = DocumentFacts.fold(String.valueOf(raw)).replaceAll("[^0-9.\\-]", "");
        if (s.isEmpty() || s.equals("-") || s.equals(".")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
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
