package com.elioo.baymax.web.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;

/**
 * SQL fragments over {@code document} that more than one reader needs, written once so the readers cannot disagree about
 * the same paper (review of #122, finding 8: the chat stack and the health screen each had their own idea of a retaken
 * paper).
 */
public final class DocumentSql {

    private DocumentSql() {
    }

    /**
     * D1 (migration retake_replaces): a paper the family was asked to send again ({@code NEEDS_RETAKE}) or that failed on our
     * side ({@code FAILED}) is no longer owed once a new photo was sent in its place ({@code replaces_document_id}), unless
     * that photo FAILED too: then the family still owes a readable one. A predicate on the document aliased {@code d};
     * every other status passes it.
     */
    public static final String NOT_REPLACED = """
            NOT (d.status IN ('NEEDS_RETAKE', 'FAILED') AND EXISTS (SELECT 1 FROM %1$s.document r
                                                                   WHERE r.replaces_document_id = d.id AND r.status <> 'FAILED'))"""
            .formatted(BaymaxSchema.NAME);
}
