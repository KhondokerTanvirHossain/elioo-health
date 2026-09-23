package com.elioo.baymax.extraction.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The model's structured answer, as defined by {@code extraction-schema.json}. Every item carries the
 * span of OCR text it came from, so the server can cut the crop that proves it. An item whose span cannot
 * be resolved to a rectangle is dropped before anything is persisted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractionResult(
        @JsonProperty("document_type") String documentType,
        @JsonProperty("patient_hint") String patientHint,
        @JsonProperty("document_date") String documentDate,
        String facility,
        List<Value> values,
        List<Medicine> medicines,
        @JsonProperty("follow_up") List<FollowUp> followUp,
        @JsonProperty("clinical_context") ClinicalContext clinicalContext,
        @JsonProperty("free_text_summary") String freeTextSummary,
        Confidence confidence
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Value(
            String name,
            @JsonProperty("canonical_name") String canonicalName,
            String value,
            String unit,
            /**
             * The SELECTED range, filled by code (DR-30) — not by the model. Present only when the page
             * printed a numeric range and, where several were printed, one of them applies.
             */
            @JsonProperty("ref_low") String refLow,
            @JsonProperty("ref_high") String refHigh,
            /**
             * The reference exactly as printed, whenever it is not a plain pair of numbers: "Nil",
             * "Negative", "Upto 37", "Male: 13.0-18.0, Female: 11.5-16.5", a tier table. DR-29 exists
             * because this field did not: a word reference had nowhere to go and the model invented a
             * numeric range instead — lab2's "Nil" arrived as ref_low 0, ref_high 2, driving urgency.
             */
            @JsonProperty("ref_text") String refText,
            /** The printed tier a value falls in, for tiered references ("Borderline High"). */
            String band,
            /** Every printed range with its qualifier; code selects the applicable one (DR-30). */
            List<Range> ranges,
            /**
             * A mark the PAGE prints — a letter, a symbol, printed wording, a colour. Never the model's own
             * comparison: that is {@code status}, and it is computed in code.
             */
            String flag,
            @JsonProperty("source_span") SourceSpan sourceSpan
    ) {

        /** One printed reference range and the qualifier it was printed under ("Male", "Adult", "1-5y"). */
        public record Range(String low, String high, String qualifier) {
        }

        public List<Range> ranges() {
            return ranges == null ? List.of() : ranges;
        }
        public boolean isCritical() {
            return "critical".equalsIgnoreCase(flag);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Medicine(
            String name,
            @JsonProperty("dose_text") String doseText,
            String route,
            @JsonProperty("frequency_text") String frequencyText,
            @JsonProperty("timing_text") String timingText,
            @JsonProperty("duration_text") String durationText,
            /**
             * The medicine's whole instruction line, exactly as written, when it does not fit the fields above
             * — a multi-phase regimen ("১+০+০ ৩০ দিন। তারপর ১+০+১ ২ সপ্তাহ") is one instruction, not two doses.
             * Set by the model when it cannot decompose the line without losing meaning; the family then
             * receives this instead of the fields (DR-16).
             */
            @JsonProperty("instruction_text") String instructionText,
            @JsonProperty("source_span") SourceSpan sourceSpan
    ) {
    }

    /**
     * The narrative of the page, verbatim. An absent section is an empty list, never a guess: a page with
     * no diagnosis line yields no diagnosis.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClinicalContext(
            @JsonProperty("chief_complaint") List<Complaint> chiefComplaint,
            List<Line> history,
            List<Line> examination,
            List<Line> diagnosis,
            @JsonProperty("investigations_advised") List<Line> investigationsAdvised,
            List<Line> advice,
            Line referral
    ) {
        public List<Complaint> chiefComplaintOrEmpty() {
            return chiefComplaint == null ? List.of() : chiefComplaint;
        }

        public List<Line> or(List<Line> lines) {
            return lines == null ? List.of() : lines;
        }

        /** Every line in the order the page presents them, for counting and for verification. */
        public int itemCount() {
            return chiefComplaintOrEmpty().size() + or(history).size() + or(examination).size()
                    + or(diagnosis).size() + or(investigationsAdvised).size() + or(advice).size()
                    + (referral == null ? 0 : 1);
        }
    }

    /** One transcribed line, with the span that proves it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String text, @JsonProperty("source_span") SourceSpan sourceSpan) {
    }

    /** A complaint, with its duration as written ("3 days", "৩ মাস"). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Complaint(String text, String duration,
                            @JsonProperty("source_span") SourceSpan sourceSpan) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FollowUp(
            String instruction,
            @JsonProperty("due_date") String dueDate,
            @JsonProperty("source_span") SourceSpan sourceSpan
    ) {
    }

    /** Character offsets into one page's OCR text. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceSpan(int page, int start, int end) {
        public boolean isUsable() {
            return page >= 1 && end > start && start >= 0;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Confidence(Double overall, Double values, Double medicines,
                             @JsonProperty("follow_up") Double followUp,
                             @JsonProperty("clinical_context") Double clinicalContext) {

        public double overallOrZero() {
            return overall == null ? 0d : overall;
        }

        /** The lowest section confidence that was actually reported; 1.0 when none were. */
        /**
         * @deprecated the min across every section, including ones the document never had. A prescription has
         *     no lab values, so values[] scored 0.0 and clear photos were rejected as unreadable. The gate now
         *     uses {@code DocumentExtractionService.lowestPresentSection} with the expected-sections table.
         */
        @Deprecated
        public double lowestSection() {
            return java.util.stream.Stream.of(values, medicines, followUp, clinicalContext)
                    .filter(java.util.Objects::nonNull)
                    .mapToDouble(Double::doubleValue)
                    .min()
                    .orElse(1d);
        }
    }

    public List<Value> valuesOrEmpty() {
        return values == null ? List.of() : values;
    }

    public List<Medicine> medicinesOrEmpty() {
        return medicines == null ? List.of() : medicines;
    }

    public List<FollowUp> followUpOrEmpty() {
        return followUp == null ? List.of() : followUp;
    }

    public boolean hasCriticalValue() {
        return valuesOrEmpty().stream().anyMatch(Value::isCritical);
    }

    public ClinicalContext clinicalContextOrEmpty() {
        return clinicalContext == null
                ? new ClinicalContext(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null)
                : clinicalContext;
    }

    public int itemCount() {
        return valuesOrEmpty().size() + medicinesOrEmpty().size() + followUpOrEmpty().size()
                + clinicalContextOrEmpty().itemCount();
    }
}
