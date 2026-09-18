package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.outbound.domain.Urgency;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The checklist as code, run on every generated body (BMX-6). Returns violation codes; an empty list is the
 * only pass. Codes, never text, so they can be logged.
 */
@Component
@RequiredArgsConstructor
public class MessageSafetyCheck {

    private final BaymaxProperties properties;

    public List<String> violations(String body, DocumentFacts facts, Urgency urgency) {
        return violations(body, facts.numbers(), facts.link(), urgency);
    }

    /**
     * The same checklist for a body whose closed set of numbers comes from elsewhere — a nudge's stored strings
     * (BMX-8). {@code link} is stripped before digits are counted, as for a document link.
     */
    public List<String> violations(String body, Set<String> allowedNumbers, String link, Urgency urgency) {
        return violations(body, allowedNumbers, link, urgency, List.of());
    }

    /**
     * With verbatim strings (BMX-8 nudges, DR-16 medicines): each must appear in the body character for character or
     * the body fails ({@code verbatim_missing}); they are then stripped before the phrase and number checks, since they
     * ARE the extraction — "mg/dL" in a stored unit is not dose advice.
     */
    public List<String> violations(String body, Set<String> allowedNumbers, String link, Urgency urgency, Collection<String> verbatim) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isBlank()) {
            out.add("empty_body");
            return out;
        }
        if (body.length() > properties.getOutbound().getMaxChars()) {
            out.add("too_long:" + body.length());
        }
        String stripped = body;
        List<String> longestFirst = new ArrayList<>(verbatim);
        longestFirst.sort(Comparator.comparingInt(String::length).reversed());
        for (String v : longestFirst) {
            if (v == null || v.isBlank()) {
                continue;
            }
            if (!stripped.contains(v)) {
                out.add("verbatim_missing:" + v);
            } else {
                stripped = stripped.replace(v, " ");
            }
        }
        body = stripped;
        // every number in the body must be a number in the extraction
        String withoutLink = link == null ? body : body.replace(link, " ");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(DocumentFacts.fold(withoutLink));
        while (m.find()) {
            if (!allowedNumbers.contains(m.group())) {
                out.add("number_not_in_extraction:" + m.group());
            }
        }
        // no advice to start/stop/change a medicine or dose — whole tokens/phrases, so ডাক্তার দেখান does not
        // trip on the খান inside দেখান
        // two views of the body: one keeps "." inside tokens so "tab." and "cap." match as written, one drops
        // it so "tablet." at a sentence end is still the token "tablet"
        String lower = body.toLowerCase(Locale.ROOT);
        String withDots = " " + lower.replaceAll("[^\\p{L}\\p{M}\\p{N}.]+", " ") + " ";
        String noDots = " " + lower.replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ") + " ";
        for (String phrase : properties.getOutbound().getForbiddenPhrases()) {
            String needle = " " + phrase.toLowerCase(Locale.ROOT).trim() + " ";
            if (withDots.contains(needle) || noDots.contains(needle)) {
                out.add("forbidden_phrase:" + phrase);
            }
        }
        // doctor-first framing on anything urgent
        if (urgency.isAtLeast(Urgency.THIS_WEEK)) {
            boolean framed = properties.getOutbound().getDoctorFirstMarkers().stream().anyMatch(body::contains);
            if (!framed) {
                out.add("doctor_first_missing");
            }
        }
        return out;
    }
}
