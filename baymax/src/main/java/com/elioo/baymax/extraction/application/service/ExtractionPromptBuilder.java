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
                  "values":    [ { "name", "canonical_name", "value", "unit", "ref_low", "ref_high", "flag", "specimen", "source_span", "source_region" } ],
                  "medicines": [ { "name", "dose_text", "route", "frequency_text", "timing_text", "duration_text", "source_span" } ],
                  "follow_up": [ { "instruction", "due_date", "source_span" } ],
                  "clinical_context": {
                    "chief_complaint":        [ { "text", "duration", "source_span" } ],
                    "history":                [ { "text", "source_span" } ],
                    "examination":            [ { "text", "source_span" } ],
                    "diagnosis":              [ { "text", "source_span" } ],
                    "investigations_advised": [ { "text", "source_span" } ],
                    "advice":                 [ { "text", "source_span" } ],
                    "referral":               { "text", "source_span" } | null
                  },
                  "free_text_summary": string | null, // one neutral sentence naming what the document is
                  "confidence": { "overall": 0..1, "values": 0..1, "medicines": 0..1, "follow_up": 0..1, "clinical_context": 0..1 }
                }
                """);

        p.append("\nTRANSCRIBE, DO NOT INFER\n");
        p.append("This is the rule that matters most. You are copying what is on the page, not working out ")
                .append("what it means.\n");
        p.append("- If the page has no diagnosis line, diagnosis is an empty list. Do not derive a diagnosis ")
                .append("from the medicines, the tests ordered, or the symptoms. A page that prescribes ")
                .append("insulin is not thereby a diagnosis of diabetes.\n");
        p.append("- Do not connect items to each other. A diagnosis, a medicine and a test on the same page ")
                .append("are three separate transcriptions, not a story.\n");
        p.append("- A line that stops mid-sentence stays that way: \"Amenorrhoea due to\" is transcribed as ")
                .append("\"Amenorrhoea due to\". Do not complete it.\n");
        p.append("- Illegible means left out, not guessed. Lower your confidence instead.\n");
        p.append("- EVERY ROW THAT HAS A PRINTED RESULT IS LISTED, including negative and normal ones: ")
                .append("\"Nil\", \"Negative\", \"Absent\", \"Not seen\", \"Normal\", \"0-2/HPF\". ")
                .append("A negative result is a result — the page says this was looked for and not found, ")
                .append("which is different from the test not being done. Never leave a row out because it ")
                .append("looks unremarkable; a report listing 28 findings must produce 28 values, not the ")
                .append("handful that are abnormal. Only a row with the result column BLANK is skipped.\n");

        p.append("\nRULES\n");
        p.append("- value and unit exactly as printed: \"8.2\" and \"%\", not \"8.20\" or \"percent\".\n");
        p.append("- flag is one of low, normal, high, critical, or null. Use the reference range printed on ")
                .append("the page. Use critical only when the page itself marks it so.\n");
        p.append("- Medicine text is verbatim, every field: dose_text \"5 mg+20 mg\", frequency_text ")
                .append("\"1+0+1\", timing_text \"রাত\", duration_text \"চলবে\". No expansion, no generic or ")
                .append("molecule name, no normalisation, no translation. Brand name as written, including ")
                .append("\"Tab.\" or \"Cap.\" if the page has it.\n");
        p.append("- Bangla stays Bangla. Advice, timing and duration written in Bangla are transcribed in ")
                .append("Bangla, not translated.\n");
        p.append("- follow_up.due_date: resolve a relative instruction against document_date. ")
                .append("\"৩ মাস পর আসবেন\" on a document dated 2026-08-06 gives due_date 2026-11-06, with the ")
                .append("instruction still transcribed verbatim. Null only when the page states no timing.\n");
        p.append("- Dates as YYYY-MM-DD. A date with no year on the page is null, not guessed.\n");
        p.append("- DATES ARE DAY-FIRST. Bangladeshi reports write the day first: 04/07/2024 is 4 July 2024, ")
                .append("not 4 April. Read day-first unless the page itself proves otherwise — a month ")
                .append("spelled out, or a companion date that only makes sense one way.\n");
        p.append("- When the page CANNOT settle the order, still fill document_date with the day-first ")
                .append("reading and set document_date_uncertain to true. 07/11/2021 with no other date on ")
                .append("the page and no spelled-out month is 7 November by convention, not by reading, and ")
                .append("we need to know which it was. A day above 12 (25/03/2024), a spelled-out month or a ")
                .append("second date that proves the order means it is NOT uncertain.\n");
        p.append("- A prescription has medicines and clinical context, usually no lab values. A lab report ")
                .append("has values and usually no medicines. Do not pad the empty one.\n");
        p.append("- For an imaging report, record only what the radiologist wrote. Never describe the image.\n");
        p.append("- specimen: which SAMPLE the value was measured in — \"blood\", \"urine\", \"stool\" or ")
                .append("\"other\" — taken from the section heading the row sits under (\"URINE R/E\", ")
                .append("\"COMPLETE BLOOD COUNT\", \"BIOCHEMISTRY\"). Null when the page gives no heading. ")
                .append("Never infer it from the test name: a urine glucose and a blood glucose are the same ")
                .append("word and completely different results.\n");

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
        p.append("Report honestly, per section. Handwriting you are unsure of, a smudged number, a cut-off ")
                .append("page: all lower confidence. A low score costs nothing; a wrong number reaches a family.\n");

        if (imagesAttached) {
            p.append("\nThe page images are attached in order. The OCR text below is a machine reading of them ")
                    .append("and may contain errors; when the image and the text disagree, trust the image, but ")
                    .append("keep source_span pointing at the OCR text.\n");

            p.append("\nSOURCE REGIONS (for every value)\n");
            p.append("""
                    Each value must also carry source_region: {"page": n, "left": x1, "top": y1, "right": x2, \
                    "bottom": y2}, where the value sits on the page IMAGE, as fractions of page width and \
                    height from the top-left corner (0.0 to 1.0). Take these from what you SEE, not from the \
                    OCR text.

                    This is the only way to keep a value the OCR text does not contain. The OCR often splits \
                    or drops a table cell, and a value the server cannot find in the text is discarded and \
                    never reaches the family however clearly you read it — a value whose region you give \
                    survives that.

                    Point at the value's own row: tight around the label and the number, the way a person \
                    would underline it. A region covering more than about a third of the page is treated as \
                    pointing at nothing and discarded. A region that covers the wrong row is worse than none, \
                    because the family would be shown a picture of a different number, so where you are not \
                    sure of the position, omit source_region rather than guess.
                    """);
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
