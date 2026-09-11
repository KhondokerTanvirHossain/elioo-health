package com.elioo.healthcare.gcp.vision.api;

import com.elioo.healthcare.gcp.vision.model.ImageQualityResult;
import com.elioo.healthcare.gcp.vision.model.VisionOcrRequest;
import com.elioo.healthcare.gcp.vision.model.VisionOcrResponse;
import reactor.core.publisher.Mono;

/**
 * OCR service contract for Google Cloud Vision API.
 *
 * <p>This interface defines the contract for optical character recognition (OCR) services
 * using Google Cloud Vision API. Implementations provide OCR capabilities for extracting
 * text and structured data from document images.</p>
 *
 * <p>All operations are reactive and return {@link Mono} types for non-blocking execution.</p>
 *
 * <p><b>Key Features:</b></p>
 * <ul>
 *   <li><b>DOCUMENT_TEXT_DETECTION:</b> Structured document analysis with page/block/paragraph/word hierarchy</li>
 *   <li><b>Multi-language Support:</b> 50+ languages including Bangla (Bengali), Hindi, English</li>
 *   <li><b>Language Hints:</b> Improved accuracy by specifying expected languages</li>
 *   <li><b>Confidence Scores:</b> Word-level and block-level confidence metrics</li>
 *   <li><b>Geometry Information:</b> Bounding boxes for all text elements</li>
 *   <li><b>Large Image Support:</b> Up to 20 MB images (vs 10 MB for AWS Textract)</li>
 * </ul>
 *
 * <p><b>Implementations:</b></p>
 * <ul>
 *   <li>{@code VisionServiceImpl} - Google Cloud Vision implementation</li>
 * </ul>
 *
 * <p><b>Supported Languages (partial list):</b></p>
 * <ul>
 *   <li>bn - Bangla/Bengali</li>
 *   <li>en - English</li>
 *   <li>hi - Hindi</li>
 *   <li>es - Spanish</li>
 *   <li>fr - French</li>
 *   <li>de - German</li>
 *   <li>ja - Japanese</li>
 *   <li>ko - Korean</li>
 *   <li>zh - Chinese</li>
 *   <li>ar - Arabic</li>
 * </ul>
 *
 * @see VisionOcrRequest
 * @see VisionOcrResponse
 * @see ImageQualityResult
 * @since 0.1.0
 */
public interface VisionService {

    /**
     * Performs DOCUMENT_TEXT_DETECTION on an image.
     *
     * <p>This method uses the DOCUMENT_TEXT_DETECTION feature which is optimized
     * for structured documents (medical reports, invoices, forms, receipts). It provides:</p>
     * <ul>
     *   <li><b>Full Text Annotation:</b> Complete text with page/block/paragraph/word/symbol hierarchy</li>
     *   <li><b>Bounding Boxes:</b> Geometry information for all text elements</li>
     *   <li><b>Language Detection:</b> Automatic language identification</li>
     *   <li><b>Confidence Scores:</b> Per-word and per-block confidence metrics</li>
     *   <li><b>Reading Order:</b> Natural reading order preserved</li>
     * </ul>
     *
     * <p><b>Performance Characteristics:</b></p>
     * <ul>
     *   <li>Processing Time: ~1-3 seconds for typical medical reports</li>
     *   <li>Max Image Size: 20 MB</li>
     *   <li>Supported Formats: JPEG, PNG, GIF, BMP, WEBP, RAW, ICO, PDF, TIFF</li>
     * </ul>
     *
     * <p><b>Language Hints Best Practices:</b></p>
     * <ul>
     *   <li>Provide language hints for better accuracy (especially for non-Latin scripts)</li>
     *   <li>List primary language first: ["bn", "en"] for Bangla-primary documents</li>
     *   <li>Include fallback languages: ["bn", "en"] allows mixed-language documents</li>
     * </ul>
     *
     * <p><b>Example Usage:</b></p>
     * <pre>{@code
     * // Bangla medical report
     * VisionOcrRequest request = VisionOcrRequest.banglaEnglish(imageBase64);
     * VisionOcrResponse response = visionService.detectDocumentText(request).block();
     * String fullText = response.fullText();  // Contains Bangla + English text
     * }</pre>
     *
     * @param request OCR request containing image and language hints
     * @return Mono emitting the OCR response with extracted text and geometry
     */
    Mono<VisionOcrResponse> detectDocumentText(VisionOcrRequest request);

    /**
     * Detects and extracts raw text from an image.
     *
     * <p>This is a simpler operation compared to {@link #detectDocumentText(VisionOcrRequest)}
     * that returns only the concatenated text without structure or geometry information.
     * Faster for text-only use cases where layout and bounding boxes are not needed.</p>
     *
     * <p><b>When to use this vs detectDocumentText:</b></p>
     * <ul>
     *   <li><b>Use detectText:</b> When you only need plain text (e.g., for NLP processing)</li>
     *   <li><b>Use detectDocumentText:</b> When you need structure, geometry, or parsing logic</li>
     * </ul>
     *
     * <p><b>Example Usage:</b></p>
     * <pre>{@code
     * String text = visionService.detectText(imageBase64).block();
     * // Process text with NLP, entity extraction, etc.
     * }</pre>
     *
     * @param imageBase64 Base64-encoded image data
     * @return Mono emitting the extracted text as a single string
     */
    Mono<String> detectText(String imageBase64);

    /**
     * Validates image quality before OCR processing.
     *
     * <p>Performs pre-flight checks to ensure the image meets quality requirements:</p>
     * <ul>
     *   <li><b>File Size:</b> Within acceptable range (0.05 MB - 20 MB)</li>
     *   <li><b>Image Format:</b> Supported by Vision API</li>
     *   <li><b>Resolution:</b> Adequate for accurate OCR</li>
     *   <li><b>Base64 Encoding:</b> Valid and decodable</li>
     * </ul>
     *
     * <p><b>Quality Scoring:</b></p>
     * <ul>
     *   <li><b>1.0:</b> Excellent quality (optimal size, high resolution)</li>
     *   <li><b>0.8-0.9:</b> Good quality (acceptable for OCR)</li>
     *   <li><b>0.6-0.7:</b> Fair quality (may have accuracy issues)</li>
     *   <li><b>&lt; 0.6:</b> Poor quality (not recommended)</li>
     * </ul>
     *
     * <p><b>Example Usage:</b></p>
     * <pre>{@code
     * ImageQualityResult result = visionService.validateImageQuality(imageBase64).block();
     * if (!result.isValid()) {
     *     throw new ValidationException(result.reason());
     * }
     * if (result.qualityScore() < 0.7) {
     *     log.warn("Low image quality: {}", result.qualityScore());
     * }
     * }</pre>
     *
     * @param imageBase64 Base64-encoded image data
     * @return Mono emitting validation result with quality metrics
     */
    Mono<ImageQualityResult> validateImageQuality(String imageBase64);
}
