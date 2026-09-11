package com.elioo.healthcare.aws.textract.api;

import com.elioo.healthcare.aws.textract.model.ImageQualityResult;
import com.elioo.healthcare.aws.textract.model.OcrRequest;
import com.elioo.healthcare.aws.textract.model.OcrResponse;
import reactor.core.publisher.Mono;

/**
 * OCR service contract for document text extraction.
 *
 * <p>This interface defines the contract for optical character recognition (OCR) services.
 * Implementations provide provider-specific OCR capabilities for extracting text and
 * structured data from document images.</p>
 *
 * <p>All operations are reactive and return {@link Mono} types for non-blocking execution.</p>
 *
 * <p><b>Implementations:</b></p>
 * <ul>
 *   <li>{@code TextractServiceImpl} - AWS Textract implementation</li>
 * </ul>
 *
 * @see OcrRequest
 * @see OcrResponse
 * @see ImageQualityResult
 * @since 0.1.0
 */
public interface TextractService {

    /**
     * Analyzes a document image and extracts structured data.
     *
     * <p>This method performs comprehensive document analysis including:</p>
     * <ul>
     *   <li>Text extraction (lines, words)</li>
     *   <li>Table detection and cell extraction</li>
     *   <li>Form detection (key-value pairs)</li>
     *   <li>Layout analysis</li>
     * </ul>
     *
     * @param request OCR request containing image and configuration
     * @return Mono emitting the OCR response with extracted blocks
     */
    Mono<OcrResponse> analyzeDocument(OcrRequest request);

    /**
     * Detects and extracts raw text from a document image.
     *
     * <p>This is a simpler operation compared to {@link #analyzeDocument(OcrRequest)}
     * that only extracts text without structured parsing. Faster for text-only use cases.</p>
     *
     * @param imageBase64 Base64-encoded image data
     * @return Mono emitting the extracted text as a single string
     */
    Mono<String> detectText(String imageBase64);

    /**
     * Validates image quality before OCR processing.
     *
     * <p>Checks image against quality requirements:</p>
     * <ul>
     *   <li>File size within acceptable range</li>
     *   <li>Image format supported</li>
     *   <li>Resolution adequate for OCR</li>
     * </ul>
     *
     * @param imageBase64 Base64-encoded image data
     * @return Mono emitting validation result with quality metrics
     */
    Mono<ImageQualityResult> validateImageQuality(String imageBase64);
}
