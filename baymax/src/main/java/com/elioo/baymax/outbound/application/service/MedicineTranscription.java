package com.elioo.baymax.outbound.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DR-16: medicines enter a family message only as verbatim transcription of what the prescription states —
 * name, dose_text, frequency_text, timing_text exactly as stored — appended after the model has phrased the
 * rest. The model never sees them; nothing here is composed. {@link #verify} is the assertion the PO asked
 * for: every stored medicine string is in the body character for character, or the message fails closed.
 */
final class MedicineTranscription {

    static final List<String> FIELDS = List.of("name", "dose_text", "frequency_text", "timing_text");
    static final String SEPARATOR = " · ";

    private MedicineTranscription() {
    }

    /** The block to append: the header and one line per medicine, or an empty string when there are none. */
    static String block(String header, List<Map<String, Object>> medicines) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> m : medicines) {
            List<String> parts = new ArrayList<>();
            for (String f : FIELDS) {
                String s = text(m.get(f));
                if (!s.isEmpty()) {
                    parts.add(s);
                }
            }
            if (!parts.isEmpty()) {
                lines.add(String.join(SEPARATOR, parts));
            }
        }
        return lines.isEmpty() ? "" : header + "\n" + String.join("\n", lines);
    }

    /** Every non-empty stored medicine string, exactly as stored, must be in the body; returns what is missing. */
    static List<String> verify(String body, List<Map<String, Object>> medicines) {
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> m : medicines) {
            for (String f : FIELDS) {
                String s = text(m.get(f));
                if (!s.isEmpty() && (body == null || !body.contains(s))) {
                    missing.add(f + ":" + s);
                }
            }
        }
        return missing;
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
