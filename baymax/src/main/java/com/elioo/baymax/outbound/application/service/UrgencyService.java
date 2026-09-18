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
 *   <li>NOW — a value beyond its <em>printed</em> reference range by more than the critical band
 *       (range width × {@code criticalBandMultiplier}), or the document text carries a verbatim emergency
 *       phrase (config list, both scripts).</li>
 *   <li>THIS_WEEK — a value outside its printed range but inside the band, a diagnosis line present, or a
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
        double band = properties.getOutbound().getCriticalBandMultiplier();

        for (Map<String, Object> v : facts.values()) {
            Optional<Double> value = number(v.get("value"));
            Optional<Double> low = number(v.get("ref_low"));
            Optional<Double> high = number(v.get("ref_high"));
            if (value.isEmpty() || low.isEmpty() || high.isEmpty() || high.get() < low.get()) {
                continue;                                    // no printed range: values cannot escalate
            }
            String name = String.valueOf(v.getOrDefault("canonical_name", v.get("name"))).toLowerCase(Locale.ROOT);
            double width = high.get() - low.get();
            double x = value.get();
            if (x < low.get() || x > high.get()) {
                boolean critical = width > 0 && (x > high.get() + band * width || x < low.get() - band * width);
                a = critical ? a.raise(Urgency.NOW, "value_critical:" + name) : a.raise(Urgency.THIS_WEEK, "value_outside_range:" + name);
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
}
