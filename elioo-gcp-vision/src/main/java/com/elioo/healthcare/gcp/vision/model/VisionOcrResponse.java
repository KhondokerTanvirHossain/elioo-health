package com.elioo.healthcare.gcp.vision.model;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Response object containing OCR results from Google Cloud Vision API.
 *
 * <p>This record encapsulates the complete text extraction results including
 * full text, structured blocks, page information, and metadata.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>
 * VisionOcrResponse response = visionService.detectDocumentText(request).block();
 *
 * // Get full text
 * String fullText = response.fullText();
 *
 * // Get high-confidence blocks
 * List&lt;TextBlock&gt; highConfBlocks = response.getHighConfidenceBlocks(0.9);
 *
 * // Check if document has structured blocks
 * if (response.hasBlocks()) {
 *     response.blocks().forEach(block -> {
 *         System.out.println("Block: " + block.text());
 *     });
 * }
 * </pre>
 *
 * @param fullText          Complete extracted text from the entire document
 * @param blocks            List of text blocks (paragraphs/sections) with geometry
 * @param pages             Page-level information (for multi-page documents)
 * @param averageConfidence Average confidence score across all blocks (0.0 - 1.0)
 * @param metadata          Additional metadata (page count, model version, etc.)
 *
 * @since 0.1.0
 */
public record VisionOcrResponse(
        String fullText,
        List<TextBlock> blocks,
        List<PageInfo> pages,
        double averageConfidence,
        Map<String, Object> metadata
) {
    /**
     * Page information for multi-page documents.
     *
     * @param pageNumber Page number (1-indexed)
     * @param width      Page width in pixels
     * @param height     Page height in pixels
     * @param confidence Average confidence for this page
     */
    public record PageInfo(
            int pageNumber,
            int width,
            int height,
            Float confidence
    ) {
    }

    /**
     * Get blocks that meet or exceed a confidence threshold.
     *
     * @param threshold Minimum confidence (0.0 - 1.0)
     * @return List of blocks with confidence >= threshold
     */
    public List<TextBlock> getHighConfidenceBlocks(double threshold) {
        if (blocks == null) {
            return List.of();
        }
        return blocks.stream()
                .filter(block -> block.meetsConfidence(threshold))
                .collect(Collectors.toList());
    }

    /**
     * Check if the response contains any blocks.
     *
     * @return true if blocks exist and list is not empty
     */
    public boolean hasBlocks() {
        return blocks != null && !blocks.isEmpty();
    }

    /**
     * Get the total number of blocks.
     *
     * @return Block count
     */
    public int getBlockCount() {
        return blocks != null ? blocks.size() : 0;
    }

    /**
     * Get blocks of a specific type.
     *
     * @param blockType Block type to filter (e.g., "TEXT", "TABLE", "PICTURE")
     * @return List of blocks matching the type
     */
    public List<TextBlock> getBlocksByType(String blockType) {
        if (blocks == null) {
            return List.of();
        }
        return blocks.stream()
                .filter(block -> blockType.equals(block.blockType()))
                .collect(Collectors.toList());
    }

    /**
     * Get all text blocks (excluding non-text elements).
     *
     * @return List of text blocks
     */
    public List<TextBlock> getTextBlocks() {
        return getBlocksByType("TEXT");
    }

    /**
     * Check if the OCR result has acceptable quality.
     *
     * @param minConfidence Minimum acceptable average confidence
     * @return true if averageConfidence >= minConfidence
     */
    public boolean hasAcceptableQuality(double minConfidence) {
        return averageConfidence >= minConfidence;
    }

    /**
     * Get the number of pages in the document.
     *
     * @return Page count from metadata, or 1 if not available
     */
    public int getPageCount() {
        if (metadata != null && metadata.containsKey("pageCount")) {
            return ((Number) metadata.get("pageCount")).intValue();
        }
        return pages != null ? pages.size() : 1;
    }

    /**
     * Get detected language from metadata.
     *
     * @return Language code (e.g., "bn", "en") or null if not detected
     */
    public String getDetectedLanguage() {
        if (metadata != null && metadata.containsKey("detectedLanguage")) {
            return (String) metadata.get("detectedLanguage");
        }
        return null;
    }
}
