package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.web.domain.ThreadItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The চ্যাট thread's JSON (plan W5-api), snake case: {@code {"items": [...], "next_cursor": string|null}}. An item is
 * <ul>
 *   <li>{@code {"kind": "paper", "at", "card"}}: {@code at} is the paper's {@code received_at}, {@code card} the paper
 *       JSON's own body;</li>
 *   <li>{@code {"kind": "message", "at", "message_kind", "document_id", "body"}}: {@code at} is when the provider accepted
 *       it, {@code message_kind} its stored kind, {@code body} the text as sent.</li>
 * </ul>
 * A message item carries no wamid, delivery status, reviewer, reject reason, gate status or model; a paper's card is the
 * paper JSON exactly as {@code GET /app/api/documents/{id}} sends it (its {@code gate}, {@code urgency}, {@code reasons}
 * and {@code wording} included). The newest explanation of a DONE paper is not a message item: the card carries it as
 * {@code wording}. {@code body} is the WhatsApp text as stored. Instants are ISO-8601 UTC.
 */
final class ThreadJson {

    private ThreadJson() {
    }

    static Map<String, Object> page(List<Map<String, Object>> items, String nextCursor) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("next_cursor", nextCursor);
        return m;
    }

    static Map<String, Object> paper(ThreadItem.Paper paper, Map<String, Object> card) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "paper");
        m.put("at", paper.at().toString());
        m.put("card", card);
        return m;
    }

    static Map<String, Object> message(ThreadItem.Message message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "message");
        m.put("at", message.at().toString());
        m.put("message_kind", message.kind().dbValue());
        m.put("document_id", message.documentId() == null ? null : message.documentId().toString());
        m.put("body", message.body());
        return m;
    }
}
