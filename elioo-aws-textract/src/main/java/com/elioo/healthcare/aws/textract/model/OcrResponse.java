package com.elioo.healthcare.aws.textract.model;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generic OCR response from Amazon Textract.
 *
 * This is a domain-agnostic model that encapsulates the output of Textract operations.
 * It provides convenient methods for filtering and accessing blocks by type.
 *
 * @param blocks         List of all extracted blocks
 * @param documentPages  Number of pages in the document
 * @param jobStatus      Status of the Textract job (if async)
 * @param metadata       Additional metadata (warnings, processing time, etc.)
 */
public record OcrResponse(
        List<ExtractedBlock> blocks,
        Integer documentPages,
        String jobStatus,
        Map<String, Object> metadata
) {
    /**
     * Gets all blocks of a specific type.
     */
    public List<ExtractedBlock> getBlocksByType(String blockType) {
        return blocks.stream()
                .filter(block -> blockType.equals(block.blockType()))
                .collect(Collectors.toList());
    }

    /**
     * Gets all table blocks.
     */
    public List<ExtractedBlock> getTables() {
        return getBlocksByType("TABLE");
    }

    /**
     * Gets all line blocks (text lines).
     */
    public List<ExtractedBlock> getLines() {
        return getBlocksByType("LINE");
    }

    /**
     * Gets all word blocks.
     */
    public List<ExtractedBlock> getWords() {
        return getBlocksByType("WORD");
    }

    /**
     * Gets all cell blocks (table cells).
     */
    public List<ExtractedBlock> getCells() {
        return getBlocksByType("CELL");
    }

    /**
     * Gets all key-value set blocks (forms).
     */
    public List<ExtractedBlock> getKeyValueSets() {
        return getBlocksByType("KEY_VALUE_SET");
    }

    /**
     * Gets blocks with confidence above threshold.
     */
    public List<ExtractedBlock> getHighConfidenceBlocks(double threshold) {
        return blocks.stream()
                .filter(block -> block.meetsConfidence(threshold))
                .collect(Collectors.toList());
    }

    /**
     * Extracts all text content as a single string.
     */
    public String extractAllText() {
        return blocks.stream()
                .filter(block -> block.text() != null && !block.text().isBlank())
                .filter(ExtractedBlock::isLine) // Only lines to avoid duplicates
                .map(ExtractedBlock::text)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Gets total number of blocks.
     */
    public int getBlockCount() {
        return blocks.size();
    }

    /**
     * Checks if response contains any blocks.
     */
    public boolean hasBlocks() {
        return blocks != null && !blocks.isEmpty();
    }

    /**
     * Checks if response contains tables.
     */
    public boolean hasTables() {
        return !getTables().isEmpty();
    }

    /**
     * Checks if response contains forms (key-value pairs).
     */
    public boolean hasForms() {
        return !getKeyValueSets().isEmpty();
    }
}
