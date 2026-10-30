package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.companion.application.port.in.AskUseCase.AskAnswer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The answer to {@code POST /app/api/patients/{id}/ask} (plan CHAT-api), snake case:
 * {@code {"turn_id", "kind": "answer"|"emergency"|"limit"|"fallback"|"declined", "question", "answer", "call_999", "at"}}.
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
        return m;
    }
}
