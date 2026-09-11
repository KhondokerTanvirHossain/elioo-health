package com.elioo.healthcare.medicalreport.adapter.out.gcp;

import com.elioo.healthcare.gcp.vision.api.VisionService;
import com.elioo.healthcare.gcp.vision.model.VisionOcrRequest;
import com.elioo.healthcare.gcp.vision.model.VisionOcrResponse;
import com.elioo.healthcare.medicalreport.application.port.out.OcrPort;
import com.elioo.healthcare.medicalreport.domain.TestResult;
import com.elioo.healthcare.medicalreport.domain.TestStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Cloud Vision implementation of OcrPort using the Vision Service library.
 *
 * <p>This adapter demonstrates the power of the elioo-gcp-vision library:</p>
 * <ul>
 *   <li>✅ <b>No manual GCP SDK calls</b> - library handles it</li>
 *   <li>✅ <b>Multi-language support</b> - Bangla, English, 50+ languages</li>
 *   <li>✅ <b>Language hints</b> - Improved accuracy for non-English text</li>
 *   <li>✅ <b>Clean DTO mapping</b> - library DTOs to domain objects</li>
 *   <li>✅ <b>Reusable across applications</b> - not tied to medscribe-ai</li>
 * </ul>
 *
 * <p>Architecture: Outbound Adapter (Driven Adapter) in Hexagonal Architecture</p>
 * <ul>
 *   <li>Implements the business-defined port interface ({@link OcrPort})</li>
 *   <li>Delegates to {@link VisionService} from library</li>
 *   <li>Maps between medscribe-ai domain objects and library DTOs</li>
 *   <li>Acts as Anti-Corruption Layer between domain and library</li>
 * </ul>
 *
 * <p><b>Provider Selection:</b></p>
 * <pre>
 * # application.properties
 * ocr.provider=gcp    # Activates this adapter
 * </pre>
 *
 * @see OcrPort
 * @see VisionService
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ocr.provider", havingValue = "gcp")
public class VisionAdapter implements OcrPort {

    private final VisionService visionService;

    @Override
    public Flux<TestResult> extractMedicalData(
            String imageBase64,
            String reportType,
            Map<String, Object> processingOptions
    ) {
        log.info("Starting GCP Vision OCR extraction for report type: {}", reportType);

        // Extract language hints from processing options
        List<String> languageHints = extractLanguageHints(processingOptions);
        log.debug("Using language hints: {}", languageHints);

        // Create request with language hints
        VisionOcrRequest request = VisionOcrRequest.withLanguages(imageBase64, languageHints);

        // Call library service and map to domain objects
        return visionService.detectDocumentText(request)
                .doOnSuccess(response -> log.info("Vision OCR completed. Blocks: {}, Avg Confidence: {}",
                        response.getBlockCount(), response.averageConfidence()))
                .doOnError(error -> log.error("Vision OCR failed", error))
                .flatMapMany(response -> extractTestResultsFromVisionResponse(response, reportType))
                .onErrorResume(error -> {
                    log.error("Error extracting medical data via GCP Vision", error);
                    return Flux.error(new OcrException("Failed to extract medical data", error));
                });
    }

    @Override
    public Mono<String> extractRawText(String imageBase64, String language) {
        log.info("Extracting raw text via GCP Vision in language: {}", language);

        // Map language code to language hints
        List<String> languageHints = buildLanguageHints(language);

        VisionOcrRequest request = VisionOcrRequest.withLanguages(imageBase64, languageHints);

        return visionService.detectDocumentText(request)
                .map(VisionOcrResponse::fullText)
                .doOnSuccess(text -> log.info("Raw text extracted. Length: {}", text.length()))
                .onErrorResume(error -> {
                    log.error("Error extracting raw text via GCP Vision", error);
                    return Mono.error(new OcrException("Failed to extract raw text", error));
                });
    }

    @Override
    public Mono<ImageQualityResult> validateImageQuality(String imageBase64) {
        log.info("Validating image quality via GCP Vision");

        return visionService.validateImageQuality(imageBase64)
                .map(libraryResult -> new ImageQualityResult(
                        libraryResult.isValid(),
                        libraryResult.qualityScore(),
                        libraryResult.message(),
                        libraryResult.metrics()
                ))
                .onErrorResume(error -> {
                    log.error("Error validating image quality via GCP Vision", error);
                    return Mono.error(new OcrException("Failed to validate image quality", error));
                });
    }

    @Override
    public Mono<Double> getProcessingConfidence(String reportType) {
        // GCP Vision confidence varies by report type
        // Generally similar to Textract but slightly lower for complex tables
        return Mono.just(switch (reportType) {
            case "BLOOD_TEST", "URINE_TEST" -> 0.88; // Good for tabular data
            case "RADIOLOGY" -> 0.72; // Lower for image-heavy reports
            case "PATHOLOGY" -> 0.78; // Medium confidence
            case "PRESCRIPTION" -> 0.82; // Good for forms
            default -> 0.68; // Default confidence
        });
    }

    // ========================================================================
    // Helper Methods: Language Hints and Text Parsing
    // ========================================================================

    /**
     * Extract language hints from processing options.
     * Defaults to Bangla + English for medical documents.
     */
    private List<String> extractLanguageHints(Map<String, Object> options) {
        if (options == null) {
            return List.of("bn", "en"); // Default: Bangla + English
        }

        Object langHints = options.get("languageHints");
        if (langHints instanceof List) {
            return ((List<?>) langHints).stream()
                    .map(Object::toString)
                    .toList();
        }

        Object lang = options.get("language");
        if (lang != null) {
            return buildLanguageHints(lang.toString());
        }

        return List.of("bn", "en"); // Default
    }

    /**
     * Build language hints from single language code.
     *
     * IMPORTANT: For medical documents in Bangladesh, ALWAYS include Bangla (bn)
     * because most prescriptions/reports are mixed language (Bangla + English).
     */
    private List<String> buildLanguageHints(String language) {
        if (language == null || language.isBlank()) {
            return List.of("bn", "en");
        }

        return switch (language.toLowerCase()) {
            case "bn", "bengali", "bangla" -> List.of("bn", "en");
            case "en", "english" -> List.of("bn", "en"); // Always include Bangla for BD medical docs
            case "hi", "hindi" -> List.of("hi", "en");
            default -> List.of(language, "en");
        };
    }

    /**
     * Extract test results from Vision OCR response.
     *
     * Strategy:
     * 1. Use full text for pattern-based parsing (same as Textract)
     * 2. Use block geometry for confidence scoring
     * 3. Support multi-language test names
     */
    private Flux<TestResult> extractTestResultsFromVisionResponse(
            VisionOcrResponse response,
            String reportType
    ) {
        log.debug("Extracting test results from Vision response with {} blocks", response.getBlockCount());

        List<TestResult> results = new ArrayList<>();

        // Strategy: Parse full text using pattern matching
        String rawText = response.fullText();
        if (rawText != null && !rawText.isEmpty()) {
            results.addAll(parseTestResultsFromRawText(rawText, reportType));
            log.info("Extracted {} test results from raw text analysis", results.size());
        }

        return Flux.fromIterable(results);
    }

    /**
     * Parse test results from raw text using pattern matching.
     * Reuses same parsing logic as TextractAdapter for consistency.
     */
    private List<TestResult> parseTestResultsFromRawText(String rawText, String reportType) {
        List<TestResult> results = new ArrayList<>();

        // Split text into lines for processing
        String[] lines = rawText.split("\n");

        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }

            // Try multiple parsing strategies
            TestResult result = null;

            // Pattern 1: "Test Name: Value Unit (Range)" - Common in lab reports
            result = tryPatternLabReport(line);
            if (result != null) {
                results.add(result);
                continue;
            }

            // Pattern 2: "Test Name Value Unit Range" - Space-separated format
            result = tryPatternSpaceSeparated(line);
            if (result != null) {
                results.add(result);
                continue;
            }

            // Pattern 3: "Test Name: Value" - Simple format
            result = tryPatternSimple(line);
            if (result != null) {
                results.add(result);
            }
        }

        log.debug("Parsed {} test results from {} lines of text", results.size(), lines.length);
        return results;
    }

    /**
     * Try to parse lab report pattern: "Test Name: Value Unit (Range)"
     * Example: "Serum Creatinine: 135.0 µmol/L (Male: 59-104, Female: 45-84)"
     */
    private TestResult tryPatternLabReport(String line) {
        Pattern pattern = Pattern.compile(
                "([\\w\\s\\-]+):\\s*([\\d.]+)\\s*([\\w/µ°]+)?\\s*(?:\\(([^)]+)\\))?",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            String testName = matcher.group(1).trim();
            String testValue = matcher.group(2).trim();
            String unit = matcher.group(3) != null ? matcher.group(3).trim() : "";
            String referenceRange = matcher.group(4) != null ? matcher.group(4).trim() : "";

            if (isLikelyTestName(testName)) {
                TestStatus status = determineTestStatusFromValue(testValue, referenceRange);

                return TestResult.builder()
                        .testName(testName)
                        .testValue(testValue)
                        .unit(unit)
                        .referenceRange(referenceRange)
                        .status(status)
                        .confidence(0.85)
                        .build();
            }
        }

        return null;
    }

    /**
     * Try to parse space-separated pattern: "Test Name Value Unit Range"
     */
    private TestResult tryPatternSpaceSeparated(String line) {
        String[] parts = line.trim().split("\\s{2,}|\\t+");

        if (parts.length >= 2) {
            String testName = parts[0].trim();
            String testValue = extractNumericValue(parts[1].trim());

            if (testValue != null && isLikelyTestName(testName)) {
                String unit = parts.length >= 3 ? parts[2].trim() : "";
                String referenceRange = parts.length >= 4 ? parts[3].trim() : "";

                TestStatus status = determineTestStatusFromValue(testValue, referenceRange);

                return TestResult.builder()
                        .testName(testName)
                        .testValue(testValue)
                        .unit(unit)
                        .referenceRange(referenceRange)
                        .status(status)
                        .confidence(0.75)
                        .build();
            }
        }

        return null;
    }

    /**
     * Try to parse simple pattern: "Test Name: Value"
     */
    private TestResult tryPatternSimple(String line) {
        Pattern pattern = Pattern.compile(
                "([\\w\\s\\-]+):\\s*([\\d.]+)",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            String testName = matcher.group(1).trim();
            String testValue = matcher.group(2).trim();

            if (isLikelyTestName(testName)) {
                return TestResult.builder()
                        .testName(testName)
                        .testValue(testValue)
                        .unit("")
                        .referenceRange("")
                        .status(TestStatus.NORMAL)
                        .confidence(0.60)
                        .build();
            }
        }

        return null;
    }

    /**
     * Check if a string is likely a medical test name.
     */
    private boolean isLikelyTestName(String name) {
        if (name == null || name.length() < 3) {
            return false;
        }

        String lower = name.toLowerCase();

        // Filter out common non-test terms
        String[] excludeTerms = {
                "date", "time", "patient", "doctor", "hospital", "page",
                "report", "laboratory", "phone", "address", "email"
        };

        for (String exclude : excludeTerms) {
            if (lower.equals(exclude) || lower.startsWith(exclude + " ")) {
                return false;
            }
        }

        // Allow both Latin and non-Latin characters (for Bangla, Hindi, etc.)
        return true;
    }

    /**
     * Extract numeric value from a string.
     */
    private String extractNumericValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        Pattern numericPattern = Pattern.compile("(-?\\d+\\.?\\d*)");
        Matcher matcher = numericPattern.matcher(value);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    /**
     * Determine test status from value and reference range.
     */
    private TestStatus determineTestStatusFromValue(String valueStr, String referenceRange) {
        if (referenceRange == null || referenceRange.isEmpty()) {
            return TestStatus.NORMAL;
        }

        try {
            double value = Double.parseDouble(valueStr);
            return determineTestStatus(value, referenceRange);
        } catch (NumberFormatException e) {
            return TestStatus.NORMAL;
        }
    }

    /**
     * Determine test status based on value and reference range.
     */
    private TestStatus determineTestStatus(double value, String referenceRange) {
        Pattern rangePattern = Pattern.compile("([\\d.]+)-([\\d.]+)");
        Matcher matcher = rangePattern.matcher(referenceRange);

        if (matcher.find()) {
            double minRange = Double.parseDouble(matcher.group(1));
            double maxRange = Double.parseDouble(matcher.group(2));

            if (value < minRange || value > maxRange) {
                double deviation = Math.max(
                        Math.abs(value - minRange) / minRange,
                        Math.abs(value - maxRange) / maxRange
                );

                if (deviation > 0.5) {
                    return TestStatus.CRITICAL;
                }
                return TestStatus.ABNORMAL;
            }
        }

        return TestStatus.NORMAL;
    }

    /**
     * Custom exception for OCR operations.
     */
    public static class OcrException extends RuntimeException {
        public OcrException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
