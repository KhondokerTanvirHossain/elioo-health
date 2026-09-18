package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.Upload;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The persisted extraction as the explanation may use it: values with their printed ranges, follow-ups,
 * clinical lines — and not the medicines: they never reach the skeleton or the model, and enter a message only as
 * the verbatim block {@link MedicineTranscription} appends after generation (DR-16). {@link #numbers}
 * is the closed set of digit runs a message body may contain.
 */
public record DocumentFacts(Document document, String patientName, List<Map<String, Object>> values,
                            List<Map<String, Object>> followUps, Map<String, Object> clinicalContext, String link) {

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** Every digit run (digits of any script folded to ASCII) that appears anywhere in the facts. */
    public Set<String> numbers() {
        Set<String> out = new HashSet<>();
        for (Map<String, Object> v : values) {
            for (String k : List.of("value", "unit", "ref_low", "ref_high", "name")) {
                addRuns(out, v.get(k));
            }
        }
        for (Map<String, Object> f : followUps) {
            addRuns(out, f.get("instruction"));
            addRuns(out, f.get("due_date"));
        }
        if (clinicalContext != null) {
            for (Object section : clinicalContext.values()) {
                if (section instanceof List<?> items) {
                    for (Object i : items) {
                        if (i instanceof Map<?, ?> m) {
                            addRuns(out, m.get("text"));
                            addRuns(out, m.get("duration"));
                        }
                    }
                }
            }
        }
        if (document.docDate() != null) {
            addRuns(out, document.docDate().toString());                                   // 2026-09-14
            addRuns(out, String.format("%02d/%02d/%d", document.docDate().getDayOfMonth(),
                    document.docDate().getMonthValue(), document.docDate().getYear()));     // 14/09/2026
            out.add(String.valueOf(document.docDate().getDayOfMonth()));
            out.add(String.valueOf(document.docDate().getMonthValue()));
        }
        // the link is NOT a source of allowed numbers: its UUID carries random digit runs that would let a
        // fabricated "42" pass by luck. The safety check strips the link from the body before counting digits.
        return out;
    }

    static void addRuns(Set<String> out, Object value) {
        if (value == null) {
            return;
        }
        Matcher m = DIGITS.matcher(fold(String.valueOf(value)));
        while (m.find()) {
            out.add(m.group());
        }
    }

    /** Digits of any script to ASCII, so ৮.২ and 8.2 are one number. */
    public static String fold(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(Character.isDigit(c) ? (char) ('0' + Character.digit(c, 10)) : c);
        }
        return sb.toString();
    }

    /** Digit runs in a body, with the timeline link removed first: its UUID is not a number the family reads. */
    public List<String> digitRunsIn(String body) {
        List<String> runs = new ArrayList<>();
        String withoutLink = link == null ? body : body.replace(link, " ");
        Matcher m = DIGITS.matcher(fold(withoutLink));
        while (m.find()) {
            runs.add(m.group());
        }
        return runs;
    }
}
