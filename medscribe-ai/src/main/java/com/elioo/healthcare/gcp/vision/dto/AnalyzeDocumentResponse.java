package com.elioo.healthcare.gcp.vision.dto;

import com.elioo.healthcare.gcp.vision.model.TextBlock;
import com.elioo.healthcare.gcp.vision.model.VisionOcrResponse;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for comprehensive document analysis.
 *
 * <p>Contains structured OCR results including full text, text blocks with geometry,
 * page information, and confidence scores.</p>
 *
 * <p><b>Structure Hierarchy:</b></p>
 * <pre>
 * Document
 *  ├── fullText (complete text)
 *  └── blocks (List&lt;TextBlock&gt;)
 *       └── paragraphs (List&lt;TextParagraph&gt;)
 *            └── words (List&lt;TextWord&gt;)
 *                 └── symbols
 * </pre>
 *
 * <p><b>Example Response:</b></p>
 * <pre>{@code
 * {
 *   "fullText": "Blood Pressure: 120/80 mmHg\nGlucose: 95 mg/dL",
 *   "blocks": [
 *     {
 *       "text": "Blood Pressure: 120/80 mmHg",
 *       "confidence": 0.95,
 *       "geometry": { ... }
 *     }
 *   ],
 *   "pageCount": 1,
 *   "averageConfidence": 0.95,
 *   "metadata": {
 *     "blockCount": 2,
 *     "detectedLanguage": "en"
 *   }
 * }
 * }</pre>
 *
 * @param fullText          Complete extracted text from the entire document
 * @param blocks            List of text blocks (paragraphs/sections) with geometry
 * @param pageCount         Number of pages in the document
 * @param averageConfidence Average confidence score across all blocks (0.0 - 1.0)
 * @param metadata          Additional metadata (block count, detected language, etc.)
 *
 * @since 1.0.0
 */
public record AnalyzeDocumentResponse(
        String fullText,
        List<TextBlock> blocks,
        int pageCount,
        double averageConfidence,
        Map<String, Object> metadata
) {
    /**
     * Factory method to create AnalyzeDocumentResponse from VisionOcrResponse.
     *
     * <p>Transforms the library response into a web API response DTO.</p>
     *
     * @param visionResponse Response from VisionService
     * @return AnalyzeDocumentResponse with mapped fields
     */
    public static AnalyzeDocumentResponse from(VisionOcrResponse visionResponse) {
        return new AnalyzeDocumentResponse(
                visionResponse.fullText(),
                visionResponse.blocks(),
                visionResponse.getPageCount(),
                visionResponse.averageConfidence(),
                visionResponse.metadata() != null ? visionResponse.metadata() : Map.of(
                        "blockCount", visionResponse.getBlockCount(),
                        "detectedLanguage", visionResponse.getDetectedLanguage()
                )
        );
    }
}
