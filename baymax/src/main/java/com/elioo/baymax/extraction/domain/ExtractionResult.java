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
            @JsonProperty("ref_low") String refLow,
            @JsonProperty("ref_high") String refHigh,
            String flag,
            @JsonProperty("source_span") SourceSpan sourceSpan,
            /**
             * Where the model SAW this value on the page image. The span points into the OCR text and is
             * lost whenever Vision split or missed the cell; the region survives that, which is the whole
             * point of it. Always a proposal — {@code CropVerifier} decides what may be stored.
             */
            @JsonProperty("source_region") SourceRegion sourceRegion,
            /**
             * The sample this value was measured in, from the section heading the row sits under — not
             * guessed from the test name. "Calcium-Oxalate" under URINE R/E is a crystal, not serum calcium;
             * without the specimen the two are one word and the wrong reference range gets applied.
             */
            String specimen
    ) {
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

    /**
     * Where on the page IMAGE the model read an item, as a fraction of page width and height.
     *
     * <p>{@link SourceSpan} points into the OCR text, which is why 30 of 85 cropped values in batch 2 were
     * lost as {@code TEXT_NOT_ON_PAGE}: extraction reads the image and can see a table cell that Vision
     * split or missed, and the locator could only search text that Vision produced. The region is the model
     * pointing at pixels instead, so a value readable in the image is croppable even when OCR never
     * produced its text.</p>
     *
     * <p>Normalised, never pixels: the page is downscaled before it is sent, so the model does not know the
     * rendered size. Absolute coordinates would mean a different part of the page at a different scale, and
     * would do so silently.</p>
     *
     * <p>A region is a proposal, never evidence. It says where to cut; {@code CropVerifier} decides whether
     * what was cut may be stored (DR-12).</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceRegion(int page, double left, double top, double right, double bottom) {

        /** Above this fraction of the page, a "region" is the page — a shrug, not a pointer. */
        private static final double MAX_PAGE_FRACTION = 0.35;

        public boolean isUsable() {
            return page >= 1 && right > left && bottom > top
                    && left >= 0 && top >= 0 && right <= 1 && bottom <= 1;
        }

        /**
         * True when the region actually points at something: usable, and small enough that cropping it
         * shows one row rather than the whole report. A crop of most of the page as the source of a single
         * number is DR-12's 116-of-137 failure in a new costume.
         */
        public boolean isPointer() {
            return isUsable() && (right - left) * (bottom - top) <= MAX_PAGE_FRACTION;
        }

        /** The box in pixels on an image of this size, clamped so it can never escape the image. */
        public PixelBox toPixels(int width, int height) {
            return new PixelBox(
                    clamp(left * width, width), clamp(top * height, height),
                    clamp(right * width, width), clamp(bottom * height, height));
        }

        private static int clamp(double v, int max) {
            return (int) Math.max(0, Math.min(max, Math.round(v)));
        }

        public record PixelBox(int left, int top, int right, int bottom) {
            public int width() {
                return right - left;
            }

            public int height() {
                return bottom - top;
            }
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
