package com.elioo.healthcare.aws.textract.service;

import com.elioo.healthcare.aws.common.exception.AwsValidationException;
import com.elioo.healthcare.aws.textract.config.TextractProperties;
import com.elioo.healthcare.aws.textract.model.*;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.textract.TextractAsyncClient;
import software.amazon.awssdk.services.textract.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AWS Textract implementation of OCR service.
 *
 * <p>This implementation uses Amazon Textract for document text extraction.
 * It provides domain-agnostic OCR operations:</p>
 * <ul>
 *   <li>Document text detection</li>
 *   <li>Document analysis (tables, forms, layout)</li>
 *   <li>Image quality validation</li>
 * </ul>
 *
 * <p>Domain-specific parsing (e.g., medical test results) should be implemented
 * in the consuming application by processing the generic {@link OcrResponse}.</p>
 *
 * @see com.elioo.healthcare.aws.textract.api.TextractService
 * @since 0.1.0
 */
@Slf4j
public class TextractServiceImpl implements com.elioo.healthcare.aws.textract.api.TextractService {

    private final TextractAsyncClient textractClient;
    private final TextractProperties properties;

    public TextractServiceImpl(TextractAsyncClient textractClient, TextractProperties properties) {
        this.textractClient = textractClient;
        this.properties = properties;
    }

    /**
     * Analyzes a document and extracts structured data (tables, forms, layout).
     *
     * @param request OCR request with image and feature types
     * @return OCR response with extracted blocks
     */
    public Mono<OcrResponse> analyzeDocument(OcrRequest request) {
        log.info("Starting Textract analysis with features: {}", request.featureTypes());

        return Mono.fromCallable(() -> Base64.getDecoder().decode(request.imageBase64()))
                .flatMap(imageBytes -> {
                    SdkBytes imageData = SdkBytes.fromByteArray(imageBytes);

                    Document document = Document.builder()
                            .bytes(imageData)
                            .build();

                    AnalyzeDocumentRequest awsRequest = AnalyzeDocumentRequest.builder()
                            .document(document)
                            .featureTypes(request.featureTypes())
                            .build();

                    return Mono.fromFuture(textractClient.analyzeDocument(awsRequest));
                })
                .map(this::mapToOcrResponse)
                .doOnSuccess(response -> log.info("Textract analysis completed. Blocks: {}", response.getBlockCount()))
                .doOnError(error -> log.error("Textract analysis failed", error));
    }

    /**
     * Detects text in a document (simpler operation, no structured data).
     *
     * @param imageBase64 Base64-encoded image
     * @return Plain text extracted from the document
     */
    public Mono<String> detectText(String imageBase64) {
        log.info("Starting Textract text detection");

        return Mono.fromCallable(() -> Base64.getDecoder().decode(imageBase64))
                .flatMap(imageBytes -> {
                    SdkBytes imageData = SdkBytes.fromByteArray(imageBytes);

                    Document document = Document.builder()
                            .bytes(imageData)
                            .build();

                    DetectDocumentTextRequest request = DetectDocumentTextRequest.builder()
                            .document(document)
                            .build();

                    return Mono.fromFuture(textractClient.detectDocumentText(request));
                })
                .map(response -> {
                    StringBuilder text = new StringBuilder();
                    response.blocks().stream()
                            .filter(block -> block.blockType() == BlockType.LINE)
                            .forEach(block -> text.append(block.text()).append("\n"));
                    return text.toString();
                })
                .doOnSuccess(text -> log.info("Text detection completed. Length: {}", text.length()))
                .doOnError(error -> log.error("Text detection failed", error));
    }

    /**
     * Validates image quality before processing.
     *
     * Checks:
     * - Image size (min/max limits)
     * - File format (via Base64 decoding)
     *
     * @param imageBase64 Base64-encoded image
     * @return Image quality validation result
     */
    public Mono<ImageQualityResult> validateImageQuality(String imageBase64) {
        log.debug("Validating image quality");

        return Mono.fromCallable(() -> {
            byte[] imageBytes = Base64.getDecoder().decode(imageBase64);
            double sizeInMB = imageBytes.length / (1024.0 * 1024.0);

            Map<String, Object> metrics = new HashMap<>();
            metrics.put("sizeInMB", String.format("%.2f", sizeInMB));
            metrics.put("sizeBytes", imageBytes.length);
            metrics.put("maxAllowedMB", properties.getMaxImageSizeMb());
            metrics.put("minAllowedMB", properties.getMinImageSizeMb());

            // Check max size
            if (sizeInMB > properties.getMaxImageSizeMb()) {
                throw new AwsValidationException(
                        "imageSize",
                        sizeInMB,
                        String.format("Image size %.2f MB exceeds maximum allowed size %d MB",
                                sizeInMB, properties.getMaxImageSizeMb())
                );
            }

            // Check min size
            if (sizeInMB < properties.getMinImageSizeMb()) {
                return ImageQualityResult.warning(
                        0.4,
                        String.format("Image size %.2f MB is below recommended minimum %.2f MB. May lack detail.",
                                sizeInMB, properties.getMinImageSizeMb()),
                        metrics
                );
            }

            // Validation passed
            return ImageQualityResult.valid(0.85, metrics);
        });
    }

    /**
     * Maps AWS Textract response to generic OCR response.
     */
    private OcrResponse mapToOcrResponse(AnalyzeDocumentResponse awsResponse) {
        List<ExtractedBlock> blocks = awsResponse.blocks().stream()
                .map(this::mapToExtractedBlock)
                .collect(Collectors.toList());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("pages", awsResponse.documentMetadata() != null ? awsResponse.documentMetadata().pages() : null);
        metadata.put("analyzeDocumentModelVersion", awsResponse.analyzeDocumentModelVersion());

        return new OcrResponse(
                blocks,
                awsResponse.documentMetadata() != null ? awsResponse.documentMetadata().pages() : null,
                "SUCCEEDED",
                metadata
        );
    }

    /**
     * Maps AWS Textract Block to generic ExtractedBlock.
     */
    private ExtractedBlock mapToExtractedBlock(Block awsBlock) {
        // Convert AWS SDK Relationship objects to serializable BlockRelationship
        List<BlockRelationship> relationships = null;
        if (awsBlock.relationships() != null && !awsBlock.relationships().isEmpty()) {
            relationships = awsBlock.relationships().stream()
                    .map(BlockRelationship::from)
                    .collect(Collectors.toList());
        }

        // Convert AWS SDK Geometry to serializable BlockGeometry
        BlockGeometry geometry = BlockGeometry.from(awsBlock.geometry());

        return new ExtractedBlock(
                awsBlock.id(),
                awsBlock.blockTypeAsString(),
                awsBlock.text(),
                awsBlock.confidence(),
                relationships,
                geometry,
                awsBlock.rowIndex(),
                awsBlock.columnIndex(),
                awsBlock.page()
        );
    }
}
