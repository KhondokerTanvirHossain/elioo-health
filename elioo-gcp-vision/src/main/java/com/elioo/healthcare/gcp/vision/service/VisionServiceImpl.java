package com.elioo.healthcare.gcp.vision.service;

import com.elioo.healthcare.gcp.common.exception.GcpServiceException;
import com.elioo.healthcare.gcp.common.exception.GcpValidationException;
import com.elioo.healthcare.gcp.vision.api.VisionService;
import com.elioo.healthcare.gcp.vision.config.VisionProperties;
import com.elioo.healthcare.gcp.vision.model.*;
import com.google.cloud.vision.v1.*;
import com.google.protobuf.ByteString;
import com.google.rpc.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;

/**
 * Implementation of VisionService using Google Cloud Vision API.
 *
 * <p>This class provides OCR capabilities through Google Cloud Vision's
 * DOCUMENT_TEXT_DETECTION feature, optimized for structured documents.</p>
 *
 * <p><b>Key Features:</b></p>
 * <ul>
 *   <li>Multi-language OCR with language hints (Bangla, English, 50+ languages)</li>
 *   <li>Structured text extraction with hierarchy (page/block/paragraph/word)</li>
 *   <li>Bounding box geometry for all text elements</li>
 *   <li>Confidence scores at word and block level</li>
 *   <li>Image quality validation before processing</li>
 * </ul>
 *
 * <p><b>Implementation Details:</b></p>
 * <ul>
 *   <li>All operations are reactive using Project Reactor</li>
 *   <li>Blocking Vision API calls wrapped in Mono.fromCallable()</li>
 *   <li>Executed on bounded elastic scheduler for I/O operations</li>
 *   <li>Comprehensive error handling with GCP-specific exceptions</li>
 * </ul>
 *
 * @since 0.1.0
 */
@Slf4j
@RequiredArgsConstructor
public class VisionServiceImpl implements VisionService {

    private ImageAnnotatorClient client;
    private final VisionProperties properties;

    /**
     * Constructor for production use.
     */
    public VisionServiceImpl(ImageAnnotatorClient client, VisionProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * Setter for testing purposes only.
     * Allows injection of mock ImageAnnotatorClient in unit tests.
     */
    void setVisionClient(ImageAnnotatorClient client) {
        this.client = client;
    }

    @Override
    public Mono<VisionOcrResponse> detectDocumentText(VisionOcrRequest request) {
        log.debug("Starting DOCUMENT_TEXT_DETECTION for image with language hints: {}", request.languageHints());

        return Mono.fromCallable(() -> {
            // 1. Validate and decode image
            byte[] imageBytes = decodeBase64Image(request.imageBase64());
            ByteString imgBytes = ByteString.copyFrom(imageBytes);

            // 2. Build Image object
            Image image = Image.newBuilder()
                    .setContent(imgBytes)
                    .build();

            // 3. Build ImageContext with language hints
            ImageContext.Builder contextBuilder = ImageContext.newBuilder();
            if (request.languageHints() != null && !request.languageHints().isEmpty()) {
                contextBuilder.addAllLanguageHints(request.languageHints());
                log.debug("Using language hints: {}", request.languageHints());
            }

            // 4. Configure DOCUMENT_TEXT_DETECTION feature
            Feature feature = Feature.newBuilder()
                    .setType(Feature.Type.DOCUMENT_TEXT_DETECTION)
                    .build();

            // 5. Build annotation request
            AnnotateImageRequest annotateRequest = AnnotateImageRequest.newBuilder()
                    .setImage(image)
                    .addFeatures(feature)
                    .setImageContext(contextBuilder.build())
                    .build();

            // 6. Execute Vision API call
            log.debug("Calling Google Cloud Vision API");
            BatchAnnotateImagesResponse response = client.batchAnnotateImages(
                    Collections.singletonList(annotateRequest)
            );

            // 7. Check for errors
            if (response.getResponsesCount() == 0) {
                throw new GcpServiceException(
                        "Vision API",
                        "EMPTY_RESPONSE",
                        "No response received from Vision API",
                        500
                );
            }

            AnnotateImageResponse imageResponse = response.getResponses(0);
            if (imageResponse.hasError()) {
                Status error = imageResponse.getError();
                throw new GcpServiceException(
                        "Vision API",
                        String.valueOf(error.getCode()),
                        error.getMessage(),
                        error.getCode()
                );
            }

            // 8. Map to domain response
            VisionOcrResponse ocrResponse = mapToVisionOcrResponse(imageResponse);
            log.info("DOCUMENT_TEXT_DETECTION completed successfully. Blocks: {}, Avg Confidence: {}",
                    ocrResponse.getBlockCount(), ocrResponse.averageConfidence());

            return ocrResponse;

        }).subscribeOn(Schedulers.boundedElastic()) // Execute on I/O scheduler
                .doOnError(error -> log.error("Vision API call failed", error))
                .onErrorMap(this::mapException);
    }

    @Override
    public Mono<String> detectText(String imageBase64) {
        log.debug("Starting simple text detection");

        // Reuse detectDocumentText but only extract full text
        VisionOcrRequest request = VisionOcrRequest.textOnly(imageBase64);
        return detectDocumentText(request)
                .map(VisionOcrResponse::fullText)
                .doOnSuccess(text -> log.debug("Text detection completed. Length: {} chars", text.length()));
    }

    @Override
    public Mono<ImageQualityResult> validateImageQuality(String imageBase64) {
        log.debug("Validating image quality");

        return Mono.fromCallable(() -> {
            Map<String, Object> metrics = new HashMap<>();

            // 1. Validate Base64 encoding
            if (imageBase64 == null || imageBase64.isBlank()) {
                return ImageQualityResult.invalid("Image data is null or empty", metrics);
            }

            // 2. Decode and measure size
            byte[] imageBytes;
            try {
                imageBytes = Base64.getDecoder().decode(imageBase64);
            } catch (IllegalArgumentException e) {
                return ImageQualityResult.invalid("Invalid Base64 encoding", metrics);
            }

            long sizeBytes = imageBytes.length;
            double sizeMb = sizeBytes / (1024.0 * 1024.0);

            metrics.put("sizeBytes", sizeBytes);
            metrics.put("sizeInMB", Math.round(sizeMb * 100.0) / 100.0);
            metrics.put("maxAllowedMB", properties.getMaxImageSizeMb());
            metrics.put("minAllowedMB", properties.getMinImageSizeMb());

            log.debug("Image size: {} MB ({} bytes)", String.format("%.2f", sizeMb), sizeBytes);

            // 3. Check size limits
            if (sizeMb > properties.getMaxImageSizeMb()) {
                String message = String.format(
                        "Image size exceeds maximum allowed: %.2f MB > %d MB",
                        sizeMb,
                        properties.getMaxImageSizeMb()
                );
                return ImageQualityResult.invalid(message, metrics);
            }

            if (sizeMb < properties.getMinImageSizeMb()) {
                String message = String.format(
                        "Image size below minimum recommended: %.2f MB < %.2f MB",
                        sizeMb,
                        properties.getMinImageSizeMb()
                );
                return ImageQualityResult.warning(0.5, message, metrics);
            }

            // 4. Calculate quality score based on size
            double qualityScore = calculateQualityScore(sizeMb);
            metrics.put("qualityScore", Math.round(qualityScore * 100.0) / 100.0);

            // 5. Return result
            if (qualityScore >= 0.8) {
                return ImageQualityResult.valid(qualityScore, metrics);
            } else if (qualityScore >= 0.6) {
                return ImageQualityResult.warning(
                        qualityScore,
                        "Image quality is acceptable but not optimal",
                        metrics
                );
            } else {
                return ImageQualityResult.warning(
                        qualityScore,
                        "Image quality is low, OCR accuracy may be affected",
                        metrics
                );
            }

        }).subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(result -> log.info("Image validation result: valid={}, quality={}",
                        result.isValid(), result.qualityScore()))
                .onErrorMap(this::mapException);
    }

    // ========================================================================
    // Private Helper Methods
    // ========================================================================

    /**
     * Map Google Cloud Vision response to domain VisionOcrResponse.
     */
    private VisionOcrResponse mapToVisionOcrResponse(AnnotateImageResponse imageResponse) {
        if (!imageResponse.hasFullTextAnnotation()) {
            // No text detected
            return new VisionOcrResponse(
                    "",
                    List.of(),
                    List.of(),
                    0.0,
                    Map.of("pageCount", 0, "textDetected", false)
            );
        }

        TextAnnotation fullTextAnnotation = imageResponse.getFullTextAnnotation();

        // Extract full text
        String fullText = fullTextAnnotation.getText();

        // Extract blocks with geometry
        List<TextBlock> blocks = new ArrayList<>();
        double totalConfidence = 0.0;
        int blockCount = 0;
        int pageIndex = 0;

        for (Page page : fullTextAnnotation.getPagesList()) {
            pageIndex++;
            int blockIndex = 0;

            for (Block block : page.getBlocksList()) {
                blockIndex++;

                // Detect language from first block (if available)
                String detectedLanguage = extractDetectedLanguage(block);

                TextBlock textBlock = TextBlock.from(block, blockIndex, detectedLanguage);
                blocks.add(textBlock);

                if (textBlock.confidence() != null) {
                    totalConfidence += textBlock.confidence();
                    blockCount++;
                }
            }
        }

        double avgConfidence = blockCount > 0 ? totalConfidence / blockCount : 0.0;

        // Build metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("pageCount", fullTextAnnotation.getPagesCount());
        metadata.put("textDetected", true);
        metadata.put("blockCount", blocks.size());

        // Extract detected language from first block (if any)
        if (!blocks.isEmpty() && blocks.get(0).detectedLanguage() != null) {
            metadata.put("detectedLanguage", blocks.get(0).detectedLanguage());
        }

        return new VisionOcrResponse(
                fullText,
                blocks,
                List.of(), // Page info can be added if needed
                Math.round(avgConfidence * 100.0) / 100.0, // Round to 2 decimals
                metadata
        );
    }

    /**
     * Extract detected language from block (if available).
     */
    private String extractDetectedLanguage(Block block) {
        // Language detection is at page level in Vision API
        // For now, return null; can be enhanced if needed
        return null;
    }

    /**
     * Decode Base64 image with validation.
     */
    private byte[] decodeBase64Image(String imageBase64) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            throw new GcpValidationException("Image data is null or empty");
        }

        try {
            return Base64.getDecoder().decode(imageBase64);
        } catch (IllegalArgumentException e) {
            throw new GcpValidationException("Invalid Base64 encoding: " + e.getMessage(), e);
        }
    }

    /**
     * Calculate quality score based on image size.
     *
     * Optimal range: 1-15 MB
     * - 1-15 MB: Score 1.0
     * - 0.5-1 MB: Score 0.9
     * - 0.1-0.5 MB: Score 0.7
     * - < 0.1 MB: Score 0.5
     * - > 15 MB: Score 0.8 (large but acceptable)
     */
    private double calculateQualityScore(double sizeMb) {
        if (sizeMb >= 1.0 && sizeMb <= 15.0) {
            return 1.0; // Optimal size
        } else if (sizeMb >= 0.5 && sizeMb < 1.0) {
            return 0.9; // Good size
        } else if (sizeMb >= 0.1 && sizeMb < 0.5) {
            return 0.7; // Fair size
        } else if (sizeMb < 0.1) {
            return 0.5; // Too small
        } else {
            // > 15 MB but < max (20 MB)
            return 0.8; // Large but acceptable
        }
    }

    /**
     * Map exceptions to GCP-specific exceptions.
     */
    private Throwable mapException(Throwable error) {
        if (error instanceof GcpServiceException || error instanceof GcpValidationException) {
            return error; // Already mapped
        }

        // Wrap other exceptions
        return new GcpServiceException(
                "Vision API",
                "UNKNOWN_ERROR",
                "Vision API call failed: " + error.getMessage(),
                500,
                error
        );
    }
}
