package com.elioo.baymax.extraction.domain;

import java.util.List;
import java.util.Map;

/**
 * What {@code GET /documents/{id}} returns: the status always, the result only once the document is DONE.
 * Every value, medicine and follow-up carries the storage key of the crop that proves it, so the timeline
 * can show the number beside the strip of paper it came from.
 *
 * @param reasonCode F5: why a retake, as its {@link RetakeReason} code; null for every other status. Never prose.
 */
public record DocumentView(
        String documentId,
        String status,
        String reasonCode,
        String documentType,
        String documentDate,
        String facility,
        Double confidence,
        String model,
        Integer pageCount,
        List<Map<String, Object>> values,
        List<Map<String, Object>> medicines,
        List<Map<String, Object>> followUp,
        Map<String, Object> clinicalContext,
        Map<String, Object> unverified,
        String urgency
) {
    /**
     * Without a stored urgency: what intake builds. The web adds the stored level ({@link #withUrgency}) from
     * {@code DocumentUrgencyUseCase} (F1); {@code urgency} is the level's db value, null when never assessed.
     */
    public DocumentView(String documentId, String status, String reasonCode, String documentType, String documentDate,
                        String facility, Double confidence, String model, Integer pageCount,
                        List<Map<String, Object>> values, List<Map<String, Object>> medicines,
                        List<Map<String, Object>> followUp, Map<String, Object> clinicalContext,
                        Map<String, Object> unverified) {
        this(documentId, status, reasonCode, documentType, documentDate, facility, confidence, model, pageCount,
                values, medicines, followUp, clinicalContext, unverified, null);
    }

    /**
     * D2: a view and the one document row it was built from, so a caller that needs the row's filed day
     * ({@link Document#filed()}) takes it from the same snapshot as the view's status and items, never from an earlier
     * read of a paper that may have finished in between.
     */
    public record Read(Document document, DocumentView view) {
    }

    /** This view with the document's stored urgency level (db value), or null for a document never assessed. */
    public DocumentView withUrgency(String level) {
        return new DocumentView(documentId, status, reasonCode, documentType, documentDate, facility, confidence, model,
                pageCount, values, medicines, followUp, clinicalContext, unverified, level);
    }

    /**
     * Status only: the document is still being worked on, or it needs a retake, or it failed. F5: a retake carries its
     * reason CODE ({@code status_reason_code}); the developer prose ({@code status_reason}) never enters a view.
     */
    public static DocumentView pending(Document document) {
        return new DocumentView(document.id().toString(), document.status().name(), document.statusReasonCode(),
                null, null, null, null, null, document.pageCount(), null, null, null, null, null);
    }

    /**
     * Per-section counts of items we read but could not locate on the page, so they were not stored.
     * Present only when something was actually dropped: a clean document says nothing rather than zeroes.
     */
    public static Map<String, Object> unverifiedOf(Document document) {
        var counts = document.unverified();
        if (!counts.any()) {
            return null;
        }
        return Map.of("values", counts.values(), "medicines", counts.medicines(),
                "follow_up", counts.followUp(), "total", counts.total());
    }
}
