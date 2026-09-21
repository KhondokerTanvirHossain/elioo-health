package com.elioo.baymax.outbound.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DR-26: the document's diagnosis lines reach the family as verbatim transcription of what the doctor wrote —
 * never explained, never paraphrased, never linked to a medicine or a symptom, and never passed to the model.
 *
 * <p>On 2026-09-21 a family was sent a medicine list for a prescription whose diagnosis lines read "HTN
 * (known)", "HTN + DM (known)" and "Parkinson Disease". Listing the medicines while omitting the diagnosis
 * they treat is the silent failure: the family's first question is whether anything is wrong, and the doctor
 * has written the answer. Transcription is grounded; interpretation is where the risk lives — the same line
 * DR-16 draws for medicines.
 *
 * <p>Chief complaints, examination and history stay on the timeline and in the detail reply.
 */
final class DiagnosisTranscription {

    private DiagnosisTranscription() {
    }

    /** The diagnosis lines exactly as stored, in document order. */
    static List<String> lines(DocumentFacts facts) {
        List<String> out = new ArrayList<>();
        Map<String, Object> context = facts.clinicalContext();
        if (context == null || !(context.get("diagnosis") instanceof List<?> entries)) {
            return out;
        }
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> m && m.get("text") != null) {
                String text = String.valueOf(m.get("text")).trim();
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
        }
        return out;
    }

    static String block(String header, DocumentFacts facts) {
        List<String> lines = lines(facts);
        return lines.isEmpty() ? "" : header + "\n" + String.join("\n", lines);
    }

    /** Every stored diagnosis line must appear in the body exactly as stored; returns what is missing. */
    static List<String> verify(String body, DocumentFacts facts) {
        List<String> missing = new ArrayList<>();
        for (String line : lines(facts)) {
            if (body == null || !body.contains(line)) {
                missing.add(line);
            }
        }
        return missing;
    }
}
