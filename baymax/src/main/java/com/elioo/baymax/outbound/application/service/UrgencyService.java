package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.outbound.domain.MarkerThreshold;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyContext;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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

        for (Map<String, Object> v : facts.values()) {
            String name = String.valueOf(v.getOrDefault("canonical_name", v.get("name"))).toLowerCase(Locale.ROOT);
            Optional<Verdict> perMarker = perMarkerVerdict(v);
            if (perMarker.isPresent()) {
                Verdict verdict = perMarker.get();
                if (verdict.level() != Urgency.ROUTINE) {
                    a = a.raise(verdict.level(), verdict.reasonFor(name));
                }
                continue;                                    // a signed-off threshold replaces the stopgap
            }
            switch (criticalKind(v, text)) {
                case CRITICAL_HIGH, CRITICAL_LOW -> a = a.raise(Urgency.NOW, "value_critical:" + name + STOPGAP);
                case OUTSIDE -> a = a.raise(Urgency.THIS_WEEK, "value_outside_range:" + name + STOPGAP);
                default -> { }                               // inside the range, or no printed range: nothing
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
                case CRITICAL_HIGH, CRITICAL_LOW -> a = a.raise(Urgency.NOW, "unverified_value_critical:" + name);
                case OUTSIDE -> a = a.raise(Urgency.THIS_WEEK, "unverified_value_outside_range:" + name);
                default -> { }
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

        // A diagnosis line means "see a doctor" only where a doctor has not just been seen. On a prescription
        // the patient was seen and the doctor wrote it — expected, not a reason (PO ruling 2026-09-18, after the
        // first real walkthrough parked a message for exactly this). Urgency reasons are evaluated against
        // document_type, never in isolation.
        boolean prescription = "prescription".equalsIgnoreCase(facts.document().documentType());
        if (!prescription && facts.clinicalContext() != null
                && facts.clinicalContext().get("diagnosis") instanceof List<?> dx && !dx.isEmpty()) {
            a = a.raise(Urgency.THIS_WEEK, "diagnosis_present");
        }

        if (facts.document().extractionJson() != null && containsEmergencyPhrase(facts.document().extractionJson())) {
            a = a.raise(Urgency.NOW, "text_emergency_phrase");
        }
        return a;
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
    private record Verdict(Urgency level, MarkerThreshold threshold) {
        String reasonFor(String name) {
            String kind = level == Urgency.NOW ? "value_critical:" : "value_outside_range:";
            return kind + name + "|threshold:" + threshold.source();
        }
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
                .flatMap(t -> t.assess(value.get()).map(level -> new Verdict(level, t)));
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

    /** A comparison operator: the lab declined to give a number, so there is no reading to compare. */
    private static final java.util.regex.Pattern BOUNDED =
            java.util.regex.Pattern.compile("[<>\u2264\u2265]|<=|>=");

    /** The number itself, with optional sign, thousands groups and a decimal point or comma. */
    private static final java.util.regex.Pattern NUMBER =
            java.util.regex.Pattern.compile("-?\\d+(?:[.,]\\d+)*");

    /**
     * The reading a cell states, or empty when it does not state one.
     *
     * <p>This used to delete every character except digits, {@code .} and {@code -} and parse what was left.
     * On batch 2 that gave the right answer every time — by luck, not by reading: {@code "80.30 L"},
     * {@code "> 89"}, {@code "6,100"} and {@code "08"} all happened to survive it. <b>The luck runs out on a
     * decimal comma:</b> a European {@code "1,5"} stripped to {@code "15"}, a tenfold error, silently, on a
     * number a family is shown and a threshold is applied to.</p>
     *
     * <p>Empty is the safe answer and is used freely. A value with no reading escalates nothing and is shown
     * as printed; a value read WRONGLY reaches a family as a number. So a bound ({@code "> 89"}), a range
     * ({@code "0-2"}), two numbers in one cell ({@code "130 / 80"}) and an ambiguous comma ({@code "1,23"} —
     * 1.23 or 123?) are all refused rather than guessed.</p>
     */
    static Optional<Double> number(Object raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = DocumentFacts.fold(String.valueOf(raw));
        if (text.isBlank() || BOUNDED.matcher(text).find()) {
            return Optional.empty();
        }
        // A unit can carry digits ("10^3/uL"), and they are not part of the reading. The value is printed
        // first, so the unit is cut away before the number is looked for.
        text = text.replaceAll("\\b10\\s*\\^?\\s*\\d+\\s*/.*$", " ").trim();

        java.util.regex.Matcher m = NUMBER.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        String first = m.group();
        // A minus that is not the sign — "1.5-", "--2" — is a mark we do not understand, not a reading. The
        // number pattern never captures it, so it has to be caught on the surrounding text.
        int after = m.end();
        if ((after < text.length() && text.charAt(after) == '-')
                || (m.start() > 0 && text.charAt(m.start() - 1) == '-')) {
            return Optional.empty();
        }
        // Two separate numbers in one cell is a range or a pair (a blood pressure), not a reading. A second
        // match that is not simply the same digits repeated means we cannot say which number is THE value.
        if (m.find()) {
            return Optional.empty();
        }
        // Anything left over that is a digit means the cut above missed something; refuse rather than read a
        // fragment. Letters and symbols (a flag "L", a unit, brackets) are fine and expected.
        String remainder = text.replace(first, " ");
        if (remainder.chars().anyMatch(Character::isDigit)) {
            return Optional.empty();
        }
        return parseGrouped(first);
    }

    /**
     * Turns one matched number into a value, deciding what a comma means.
     *
     * <p>{@code "1,234"} is a thousands group; {@code "1,5"} is a decimal comma; {@code "1,23"} is genuinely
     * both — 1.23 in Europe, an impossible group of two digits elsewhere — and is refused. The rule is the
     * group's length: exactly three digits after every comma is a separator, one or more than three is a
     * decimal comma, and two is ambiguous.</p>
     */
    private static Optional<Double> parseGrouped(String token) {
        boolean negative = token.startsWith("-");
        String digits = negative ? token.substring(1) : token;
        if (digits.startsWith(".") || digits.startsWith(",") || digits.endsWith("-")) {
            return Optional.empty();
        }
        String[] parts = digits.split("[.,]", -1);
        if (parts.length > 1) {
            char separator = digits.charAt(digits.indexOf(parts[0]) + parts[0].length());
            String tail = parts[parts.length - 1];
            if (separator == ',' && parts.length == 2) {
                if (tail.length() == 2) {
                    return Optional.empty();                 // "1,23": decimal comma or bad grouping?
                }
                if (tail.length() != 3) {
                    digits = parts[0] + "." + tail;           // "1,5" is one and a half
                } else {
                    digits = parts[0] + tail;                // "6,100" is six thousand one hundred
                }
            } else if (separator == ',') {
                digits = String.join("", parts);             // "1,234,567"
            }
        }
        try {
            double value = Double.parseDouble(digits);
            return Optional.of(negative ? -value : value);
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
