package com.elioo.healthcare.medicalreport.application.port.out;

import com.elioo.healthcare.medicalreport.domain.TestResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Outbound port for OCR (Optical Character Recognition) operations.
 * This interface defines the contract for extracting text and structured data from medical documents.
 *
 * Implementation Note: This is a driven port in hexagonal architecture.
 * The business logic depends on this interface, not on specific OCR providers.
 *
 * Possible implementations:
 * - AWS Textract
 * - Google Cloud Vision
 * - Azure Computer Vision
 * - Local Tesseract OCR
 */
public interface OcrPort {

    /**
     * Extract structured data from a medical document image.
     *
     * @param imageBase64 Base64-encoded image data
     * @param reportType Type of medical report (BLOOD_TEST, URINE_TEST, etc.)
     * @param processingOptions Optional processing configuration (language, quality, etc.)
     * @return Flux of extracted test results with confidence scores
     */
    Flux<TestResult> extractMedicalData(
            String imageBase64,
            String reportType,
            Map<String, Object> processingOptions
    );

    /**
     * Extract raw text from a medical document image without structure.
     *
     * @param imageBase64 Base64-encoded image data
     * @param language Language code (e.g., "bn" for Bangla, "en" for English)
     * @return Mono containing extracted raw text
     */
    Mono<String> extractRawText(String imageBase64, String language);

    /**
     * Validate image quality before processing.
     * Checks resolution, clarity, file size, and format.
     *
     * @param imageBase64 Base64-encoded image data
     * @return Mono containing quality score (0.0 to 1.0) and validation result
     */
    Mono<ImageQualityResult> validateImageQuality(String imageBase64);

    /**
     * Get confidence score for OCR processing capability.
     * Different report types may have different confidence levels.
     *
     * @param reportType Type of medical report
     * @return Mono containing confidence score (0.0 to 1.0)
     */
    Mono<Double> getProcessingConfidence(String reportType);

    // ==================== Multi-Image Batch Processing Methods ====================

    /**
     * Validate multiple images in parallel.
     *
     * <p>Default implementation processes images concurrently using flatMap.
     * Implementations can override for optimized batch validation if the underlying
     * OCR provider supports batch operations.</p>
     *
     * @param imagesBase64 List of base64-encoded images to validate
     * @return Flux of validation results (one per image, order not guaranteed)
     */
    default Flux<ImageQualityResult> validateImageQualityBatch(List<String> imagesBase64) {
        return Flux.fromIterable(imagesBase64)
                .flatMap(this::validateImageQuality);
    }

    /**
     * Extract raw text from multiple images and concatenate.
     *
     * <p>Images are processed in parallel, and results are concatenated with
     * "\n\n" separator between each image's text.</p>
     *
     * <p>Default implementation uses parallel flatMap for maximum throughput.
     * Order of images in concatenated text may differ from input order.</p>
     *
     * @param imagesBase64 List of base64-encoded images
     * @param language Language code (e.g., "bn" for Bangla, "en" for English)
     * @return Mono containing concatenated raw text from all images
     */
    default Mono<String> extractRawTextBatch(List<String> imagesBase64, String language) {
        return Flux.fromIterable(imagesBase64)
                .flatMap(image -> extractRawText(image, language))
                .reduce("", (acc, text) -> acc + "\n\n" + text)
                .map(String::trim);
    }

    /**
     * Extract structured medical data from multiple images in parallel.
     *
     * <p>Returns a single Flux stream containing test results from ALL images.
     * Results are not grouped by image - downstream processing must handle merging
     * and deduplication.</p>
     *
     * <p>Default implementation processes images concurrently. Order of results
     * in the Flux is not guaranteed to match input image order.</p>
     *
     * @param imagesBase64 List of base64-encoded images
     * @param reportType Type of medical report (BLOOD_TEST, URINE_TEST, etc.)
     * @param processingOptions Optional processing configuration (language, quality, etc.)
     * @return Flux of all extracted test results from all images (unordered)
     */
    default Flux<TestResult> extractMedicalDataBatch(
            List<String> imagesBase64,
            String reportType,
            Map<String, Object> processingOptions
    ) {
        return Flux.fromIterable(imagesBase64)
                .flatMap(image -> extractMedicalData(image, reportType, processingOptions));
    }

    /**
     * Result object for image quality validation.
     */
    record ImageQualityResult(
            boolean isValid,
            double qualityScore,
            String reason,
            Map<String, Object> metrics
    ) {}
}
