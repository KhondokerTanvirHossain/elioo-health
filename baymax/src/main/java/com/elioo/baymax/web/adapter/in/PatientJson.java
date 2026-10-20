package com.elioo.baymax.web.adapter.in;

import com.elioo.baymax.web.domain.PatientSummary;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A patient as both JSON clients see it ({@code /api/v1/baymax/me} and {@code /app/api/me}): the one mapping of a
 * {@link PatientSummary}'s fields, so the two never drift apart. Each caller adds its own fields after these.
 */
public final class PatientJson {

    private PatientJson() {
    }

    /** {@code id, name, age, sex, owner, documents, last_document_at}, in that order; {@code sex} null when unrecorded. */
    public static Map<String, Object> fields(PatientSummary p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.patient().id().toString());
        m.put("name", p.patient().name());
        m.put("age", p.patient().age());
        m.put("sex", p.patient().sex() == null ? null : p.patient().sex().dbValue());
        m.put("owner", p.owner());
        m.put("documents", p.documents());
        m.put("last_document_at", p.lastDocumentAt() == null ? null : p.lastDocumentAt().toString());
        return m;
    }
}
