package com.elioo.healthcare.medicalreport.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal context for tracking multi-image processing state.
 *
 * <p>Used internally by {@link com.elioo.healthcare.medicalreport.application.service.MedicalReportOrchestrationService}
 * to track partial results, errors, and aggregated data across multiple images.</p>
 *
 * <p>This is NOT exposed in the API response - it's an internal orchestration object.</p>
 *
 * <h3>Workflow Tracking:</h3>
 * <ul>
 *   <li>Per-image OCR results (success/failure, extracted data, confidence)</li>
 *   <li>Aggregated merged results (deduplicated by confidence)</li>
 *   <li>Error tracking with image index and stage</li>
 *   <li>Success/failure counts for partial success handling</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiImageProcessingContext {

    /**
     * Unique report identifier for this multi-image processing request.
     */
    private String reportId;

    /**
     * Total number of images submitted in the request.
     */
    private int totalImages;

    /**
     * OCR results for each individual image.
     *
     * <p>Includes both successful and failed OCR attempts.
     * Each result contains image index, extracted data, raw text, and confidence scores.</p>
     */
    @Builder.Default
    private List<ImageOcrResult> imageOcrResults = new ArrayList<>();

    /**
     * Merged test results from all successfully processed images.
     *
     * <p>Duplicates are resolved by keeping the result with highest confidence score.
     * Test names are normalized (case-insensitive) for duplicate detection.</p>
     */
    private List<TestResult> mergedTestResults;

    /**
     * Concatenated raw text from all successfully processed images.
     *
     * <p>Images are joined with "\n\n" separator. Used as input for
     * AWS Comprehend Medical classification stage.</p>
     */
    private String concatenatedRawText;

    /**
     * Translated raw text (if translation was performed).
     *
     * <p>Contains English translation of concatenatedRawText if the original
     * text contained non-English content. Null if no translation was needed.</p>
     */
    private String translatedRawText;

    /**
     * Translated merged test results (if translation was performed).
     *
     * <p>Contains test results with translated text fields if the original
     * text contained non-English content. Null if no translation was needed.</p>
     */
    private List<TestResult> translatedTestResults;

    /**
     * Whether translation was performed on the merged text.
     *
     * <p>True if non-English content was detected and translated to English.
     * False if text was already in English or translation was skipped.</p>
     */
    private boolean wasTranslated;

    /**
     * Original language detected in the merged text.
     *
     * <p>ISO 639-1 language code (e.g., "bn" for Bangla, "en" for English).
     * Set during translation stage.</p>
     */
    private String originalLanguage;

    /**
     * Total character count of concatenated raw text.
     *
     * <p>Must not exceed 20,000 characters (AWS Comprehend Medical limit).
     * Validation occurs after OCR before classification.</p>
     */
    private int totalTextLength;

    /**
     * Error tracking for individual image failures.
     *
     * <p>Tracks which images failed at which stage (VALIDATION, OCR, etc.)
     * with error messages and recoverability status.</p>
     */
    @Builder.Default
    private List<ImageProcessingError> imageErrors = new ArrayList<>();

    /**
     * Count of images that were successfully processed through OCR.
     */
    private int successfulImagesCount;

    /**
     * Count of images that failed validation or OCR processing.
     */
    private int failedImagesCount;

    /**
     * General-purpose metadata storage for intermediate results.
     *
     * <p>Used to store classification results, clinical insights, and other
     * stage outputs that don't have dedicated fields.</p>
     *
     * <p>Common keys:</p>
     * <ul>
     *   <li>"classificationResult" - MedicalClassificationPort.ClassificationResult</li>
     *   <li>"clinicalInsights" - ClinicalInsightPort.ClinicalInsightResult</li>
     * </ul>
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * Result from OCR processing of a single image.
     *
     * <p>Tracks individual image processing outcome including extracted data,
     * raw text, confidence, and success/failure status.</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageOcrResult {

        /**
         * Zero-based index of this image in the original request list.
         */
        private int imageIndex;

        /**
         * Structured test results extracted from this image.
         *
         * <p>Null if OCR failed for this image.</p>
         */
        private List<TestResult> extractedData;

        /**
         * Raw text extracted from this image.
         *
         * <p>Null if OCR failed for this image.</p>
         */
        private String rawText;

        /**
         * Average confidence score of all test results extracted from this image.
         *
         * <p>Range: 0.0 to 1.0. Null if OCR failed.</p>
         */
        private Double confidence;

        /**
         * Whether OCR processing succeeded for this image.
         */
        private boolean success;

        /**
         * Error message if OCR failed.
         *
         * <p>Null if success is true.</p>
         */
        private String errorMessage;
    }

    /**
     * Error information for a failed image at a specific stage.
     *
     * <p>Enables partial success handling by tracking which images failed
     * and whether failures are recoverable (e.g., retryable errors).</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageProcessingError {

        /**
         * Zero-based index of the image that failed.
         */
        private int imageIndex;

        /**
         * Processing stage where the error occurred.
         *
         * <p>Possible values: "VALIDATION", "OCR", "CLASSIFICATION", etc.</p>
         */
        private String stage;

        /**
         * Human-readable error message.
         */
        private String errorMessage;

        /**
         * Whether this error is recoverable/retryable.
         *
         * <p>Examples of recoverable errors: timeout, rate limit, transient service errors.
         * Examples of non-recoverable errors: invalid image format, corrupted file.</p>
         */
        private boolean isRecoverable;
    }
}
