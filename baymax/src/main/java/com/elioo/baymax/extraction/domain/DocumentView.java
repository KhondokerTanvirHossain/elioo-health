package com.elioo.baymax.extraction.domain;

import java.util.List;
import java.util.Map;

/**
 * What {@code GET /documents/{id}} returns: the status always, the result only once the document is DONE.
 * Every value, medicine and follow-up carries the storage key of the crop that proves it, so the timeline
 * can show the number beside the strip of paper it came from.
 */
public record DocumentView(
        String documentId,
        String status,
        String reason,
        String documentType,
        String documentDate,
        String facility,
        Double confidence,
        String model,
        Integer pageCount,
        List<Map<String, Object>> values,
        List<Map<String, Object>> medicines,
        List<Map<String, Object>> followUp
) {
    /** Status only: the document is still being worked on, or it needs a retake, or it failed. */
    public static DocumentView pending(Document document) {
        return new DocumentView(document.id().toString(), document.status().name(), document.statusReason(),
                null, null, null, null, null, document.pageCount(), null, null, null);
    }
}
