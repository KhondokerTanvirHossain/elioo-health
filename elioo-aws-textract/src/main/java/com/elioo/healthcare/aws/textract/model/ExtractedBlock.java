package com.elioo.healthcare.aws.textract.model;

import java.util.List;

/**
 * Generic representation of a Textract block.
 *
 * This is a simplified, domain-agnostic model that extracts the most commonly used
 * fields from AWS Textract's Block structure. Consuming applications can further
 * process these blocks based on their specific domain requirements.
 *
 * @param id            Unique identifier for the block
 * @param blockType     Type of block (PAGE, LINE, WORD, TABLE, CELL, etc.)
 * @param text          Extracted text content (null for non-text blocks)
 * @param confidence    Confidence score (0-100)
 * @param relationships List of relationships to other blocks
 * @param geometry      Geometric information (bounding box, polygon)
 * @param rowIndex      Row index (for CELL blocks)
 * @param columnIndex   Column index (for CELL blocks)
 * @param page          Page number
 */
public record ExtractedBlock(
        String id,
        String blockType,
        String text,
        Float confidence,
        List<BlockRelationship> relationships,
        BlockGeometry geometry,
        Integer rowIndex,
        Integer columnIndex,
        Integer page
) {
    /**
     * Checks if this block represents a table.
     */
    public boolean isTable() {
        return "TABLE".equals(blockType);
    }

    /**
     * Checks if this block represents a cell.
     */
    public boolean isCell() {
        return "CELL".equals(blockType);
    }

    /**
     * Checks if this block represents a line of text.
     */
    public boolean isLine() {
        return "LINE".equals(blockType);
    }

    /**
     * Checks if this block represents a word.
     */
    public boolean isWord() {
        return "WORD".equals(blockType);
    }

    /**
     * Checks if this block represents a key-value pair.
     */
    public boolean isKeyValueSet() {
        return "KEY_VALUE_SET".equals(blockType);
    }

    /**
     * Checks if confidence meets a minimum threshold.
     */
    public boolean meetsConfidence(double threshold) {
        return confidence != null && confidence >= threshold;
    }

    /**
     * Gets a simple representation for debugging.
     */
    public String toSimpleString() {
        return String.format("%s[%s] confidence=%.2f text='%s'",
                blockType, id, confidence != null ? confidence : 0.0, text != null ? text : "");
    }
}
