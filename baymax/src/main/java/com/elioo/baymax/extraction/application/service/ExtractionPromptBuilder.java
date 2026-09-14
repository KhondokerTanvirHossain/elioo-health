package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.PageOcr;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the single structured extraction call. The prompt does three jobs: it fixes the output shape,
 * it forbids inventing anything that is not on the page, and it makes the model report where each item
 * came from so the server can cut a crop for it.
 *
 * <p>No explanation, no urgency and no advice are requested here; those belong to BMX-6.</p>
 */
@Component
public class ExtractionPromptBuilder {

    private final BaymaxProperties properties;

    public ExtractionPromptBuilder(BaymaxProperties properties) {
        this.properties = properties;
    }

    public String systemPrompt() {
        return """
                You read Bangladeshi medical documents: lab reports, prescriptions, discharge summaries and \
                imaging reports, written in Bangla, English or both.

                Your only job is to transcribe what is written into structured JSON. You do not interpret, \
                diagnose, advise, or explain. You do not translate values. You never invent a number, a \
                medicine or a date that is not on the page: if something is illegible, leave it out and let \
                your confidence reflect that.

                Reply with JSON only. No prose before or after, no markdown fence.""";
    }

    public String userPrompt(List<PageOcr> pages, boolean imagesAttached) {
        StringBuilder p = new StringBuilder();
        p.append("Extract this document into the exact JSON shape below.\n\n");

        p.append("SHAPE\n");
        p.append("""
                {
                  "document_type": "lab_report" | "prescription" | "discharge_summary" | "imaging_report" | "other",
                  "patient_hint": string | null,      // the patient name or id as printed, if any
                  "document_date": "YYYY-MM-DD" | null,
                  "facility": string | null,          // hospital, clinic or lab name as printed
                  "values":    [ { "name", "canonical_name", "value", "unit", "ref_low", "ref_high", "flag", "source_span" } ],
                  "medicines": [ { "name", "dose_text", "frequency_text", "duration_text", "source_span" } ],
                  "follow_up": [ { "instruction", "due_date", "source_span" } ],
                  "free_text_summary": string | null, // one neutral sentence naming what the document is
                  "confidence": { "overall": 0..1, "values": 0..1, "medicines": 0..1, "follow_up": 0..1 }
                }
                """);

        p.append("\nRULES\n");
        p.append("- value and unit exactly as printed: \"8.2\" and \"%\", not \"8.20\" or \"percent\".\n");
        p.append("- flag is one of low, normal, high, critical, or null. Use the reference range printed on ")
                .append("the page. Use critical only when the page itself marks it so.\n");
        p.append("- frequency_text keeps the local convention, e.g. \"1+0+1\".\n");
        p.append("- Dates as YYYY-MM-DD. A date with no year on the page is null, not guessed.\n");
        p.append("- Bangla text: transcribe the value, keep the name as printed.\n");
        p.append("- A prescription has medicines and usually no values. A lab report has values and usually ")
                .append("no medicines. Do not pad the empty one.\n");
        p.append("- For an imaging report, record only what the radiologist wrote. Never describe the image.\n");

        p.append("\nSOURCE SPANS (required for every item)\n");
        p.append("""
                Each item must carry source_span: {"page": n, "start": i, "end": j}, character offsets into \
                that page's OCR TEXT below, covering the text the item came from. Offsets are counted from 0 \
                at the start of that page's text. An item without a usable span is discarded by the server and \
                never shown to the family, so place them carefully.
                """);

        String markers = properties.getMarkers().stream()
                .map(BaymaxProperties.Marker::getCanonical)
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.joining(", "));
        if (!markers.isEmpty()) {
            p.append("\nCANONICAL NAMES\n");
            p.append("When a value clearly is one of these, set canonical_name to it, otherwise null: ")
                    .append(markers).append(".\n");
            p.append("Match on meaning, not spelling: local spellings and abbreviations are common.\n");
        }

        p.append("\nCONFIDENCE\n");
        p.append("Report honestly. Handwriting you are unsure of, a smudged number, a cut-off page: all lower ")
                .append("confidence. A low score costs nothing; a wrong number reaches a family.\n");

        if (imagesAttached) {
            p.append("\nThe page images are attached in order. The OCR text below is a machine reading of them ")
                    .append("and may contain errors; when the image and the text disagree, trust the image, but ")
                    .append("keep source_span pointing at the OCR text.\n");
        }

        p.append("\nOCR TEXT\n");
        for (PageOcr page : pages) {
            p.append("=== page ").append(page.pageNo()).append(" ===\n");
            p.append(page.text()).append('\n');
        }
        return p.toString();
    }

    /** Appended to the original prompt when the first reply failed schema validation. */
    public String repairPrompt(String originalPrompt, String validationError) {
        return originalPrompt + """

                YOUR PREVIOUS REPLY WAS REJECTED
                """ + validationError + """

                Return the corrected JSON only. Same content, valid shape. Do not add commentary.""";
    }
}
