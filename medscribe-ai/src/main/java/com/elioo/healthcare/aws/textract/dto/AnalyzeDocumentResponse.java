package com.elioo.healthcare.aws.textract.dto;

import com.elioo.healthcare.aws.textract.model.ExtractedBlock;
import com.elioo.healthcare.aws.textract.model.OcrResponse;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for Textract analyze document endpoint.
 *
 * @param blocks Extracted blocks from the document
 * @param pageCount Number of pages in the document
 * @param documentPages Number of pages processed
 * @param metadata Additional metadata about the analysis
 */
public record AnalyzeDocumentResponse(
        List<ExtractedBlock> blocks,
        int pageCount,
        int documentPages,
        Map<String, Object> metadata
) {
    public static AnalyzeDocumentResponse from(OcrResponse ocrResponse) {
        return new AnalyzeDocumentResponse(
                ocrResponse.blocks(),
                ocrResponse.documentPages(),
                ocrResponse.documentPages(),
                ocrResponse.metadata() != null ? ocrResponse.metadata() : Map.of(
                        "jobStatus", ocrResponse.jobStatus() != null ? ocrResponse.jobStatus() : "COMPLETED",
                        "blockCount", ocrResponse.getBlockCount()
                )
        );
    }
}
