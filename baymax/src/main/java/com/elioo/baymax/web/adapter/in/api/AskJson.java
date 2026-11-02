package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.companion.application.port.in.AskUseCase.AskAnswer;
import com.elioo.baymax.companion.domain.CitedMedicine;
import com.elioo.baymax.web.adapter.in.ImageRefs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The answer to {@code POST /app/api/patients/{id}/ask} (plan CHAT-api), snake case:
 * {@code {"turn_id", "kind": "answer"|"emergency"|"limit"|"fallback"|"declined", "question", "answer", "call_999", "at",
 * "medicines"}}. {@code medicines} is {@code [{"document_id", "crop": {"src"}}]}: each prescription line the answer quotes,
 * in the order quoted, with its crop (review of #166, finding 5), so the line is shown beside the paper's own image of it;
 * empty when it quotes none.
 * {@code answer} is exactly what the turn stored, the appended lines included; React renders it as text, verbatim. No
 * handler, violation, model, token or cost field ever reaches the browser. {@code at} is ISO-8601 UTC.
 */
final class AskJson {

    private AskJson() {
    }

    static Map<String, Object> of(AskAnswer a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("turn_id", a.turnId().toString());
        m.put("kind", a.kind().dbValue());
        m.put("question", a.question());
        m.put("answer", a.answer());
        m.put("call_999", a.call999());
        m.put("at", a.at().toString());
        m.put("medicines", medicines(a.medicines()));
        return m;
    }

    /** Each quoted line's paper and the href of its crop (ImageRefs, never a signed URL). */
    static List<Map<String, Object>> medicines(List<CitedMedicine> quoted) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CitedMedicine c : quoted) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("document_id", c.documentId().toString());
            m.put("crop", Map.of("src", ImageRefs.crop(c.documentId(), c.cropKey())));
            out.add(m);
        }
        return out;
    }
}
