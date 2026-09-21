package com.elioo.baymax.outbound.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * DR-16: medicines enter a family message only as verbatim transcription of what the prescription states —
 * name, dose_text, frequency_text, timing_text exactly as stored — appended after the model has phrased the
 * rest. The model never sees them; nothing here is composed. {@link #verify} is the assertion the PO asked
 * for: every stored medicine string is in the body character for character, or the message fails closed.
 */
final class MedicineTranscription {

    static final List<String> FIELDS = List.of("name", "dose_text", "frequency_text", "timing_text", "duration_text");
    static final String SEPARATOR = " · ";

    /**
     * Ellipsis in any form. A medicine line may never contain one: on 2026-09-21 a two-phase regimen reached a
     * family as "১+০+০ ... ১+০+১", where the ellipsis had swallowed "৩০ দিন। তারপর" — the words that make it a
     * sequence rather than two simultaneous doses. An abbreviated dosing instruction is not a transcription.
     */
    static final Pattern ELLIPSIS = Pattern.compile("\\.\\.\\.|…|\\u2026");

    private MedicineTranscription() {
    }

    /**
     * The block to append: the header and one line per medicine, or an empty string when there are none.
     *
     * <p>A medicine whose instruction does not fit the fields carries {@code instruction_text} — its whole
     * original line — and that is sent verbatim instead, prefixed with the name. Decomposing a multi-phase
     * regimen into a single frequency loses the sequence, and an ellipsis standing in for the missing words is
     * worse than the full line.
     */
    static String block(String header, List<Map<String, Object>> medicines) {
        List<String> lines = lines(medicines);
        return lines.isEmpty() ? "" : header + "\n" + String.join("\n", lines);
    }

    /** One verbatim line per medicine, in order; empty entries are skipped. */
    static List<String> lines(List<Map<String, Object>> medicines) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> m : medicines) {
            String line = line(m);
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * The single line a family sees for one medicine: its whole original instruction when the fields could not
     * hold it, otherwise the fields joined verbatim. One definition, so the block and the splitter can never
     * disagree about what a medicine line is.
     */
    static String line(Map<String, Object> m) {
        String whole = text(m.get("instruction_text"));
        if (!whole.isEmpty()) {
            String name = text(m.get("name"));
            return name.isEmpty() || whole.startsWith(name) ? whole : name + SEPARATOR + whole;
        }
        List<String> parts = new ArrayList<>();
        for (String f : FIELDS) {
            String s = text(m.get(f));
            if (!s.isEmpty()) {
                parts.add(s);
            }
        }
        return String.join(SEPARATOR, parts);
    }

    /**
     * Medicine strings that contain an ellipsis, which may never reach a family. Returned so the caller fails
     * closed the way the digit and phrase checks do — an abbreviated dosing instruction is a safety defect,
     * not a formatting one.
     */
    static List<String> elided(List<Map<String, Object>> medicines) {
        List<String> found = new ArrayList<>();
        for (Map<String, Object> m : medicines) {
            for (String f : List.of("name", "dose_text", "frequency_text", "timing_text",
                    "duration_text", "instruction_text")) {
                String s = text(m.get(f));
                if (!s.isEmpty() && ELLIPSIS.matcher(s).find()) {
                    found.add(f + ":" + s);
                }
            }
        }
        return found;
    }

    /** Every non-empty stored medicine string, exactly as stored, must be in the body; returns what is missing. */
    static List<String> verify(String body, List<Map<String, Object>> medicines) {
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> m : medicines) {
            // a medicine sent as its whole original line is verified on that line, not on the fields it
            // could not be decomposed into
            String whole = text(m.get("instruction_text"));
            if (!whole.isEmpty()) {
                if (body == null || !body.contains(whole)) {
                    missing.add("instruction_text:" + whole);
                }
                continue;
            }
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
