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
 *   <li>{@code {"kind": "message", "at", "message_kind", "document_id", "body", "web_body"}}: {@code at} is when the
 *       provider accepted it, {@code message_kind} its stored kind, {@code body} the text as sent.</li>
 * </ul>
 * A message item also carries {@code web_body}: {@code body} without its link lines ({@link #webBody}), which the
 * desktop conversation shows; the phone pages keep reading {@code body}.
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

    /**
     * Plan CHAT-api, only with {@code ?asks=1}: {@code {"kind": "ask", "at", "turn_id", "question", "answer", "answer_kind",
     * "call_999", "medicines"}}, {@code medicines} as AskJson's (each quoted line with its crop). {@code at} is when it was
     * asked; {@code answer} is exactly what Java sent; no handler,
     * violation, model, token or cost field.
     */
    static Map<String, Object> ask(ThreadItem.Ask ask) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "ask");
        m.put("at", ask.at().toString());
        m.put("turn_id", ask.id().toString());
        m.put("question", ask.question());
        m.put("answer", ask.answer());
        m.put("answer_kind", ask.kind().dbValue());
        m.put("call_999", ask.call999());
        m.put("medicines", AskJson.medicines(ask.medicines()));
        return m;
    }

    /**
     * The text the web shows for a message (plan desktop-parity Task 3): {@code body} with every line that contains
     * {@code http://} or {@code https://} removed, and nothing else changed. WhatsApp needs the links (the opt-out, the
     * timeline); in the web app they would be raw URLs inside a bubble. A body with no link comes back character for
     * character; blank lines and a trailing newline are kept.
     */
    static String webBody(String body) {
        if (body == null) {
            return null;
        }
        String[] lines = body.split("\n", -1);
        StringBuilder kept = new StringBuilder(body.length());
        boolean first = true;
        for (String line : lines) {
            if (line.contains("http://") || line.contains("https://")) {
                continue;
            }
            if (!first) {
                kept.append('\n');
            }
            kept.append(line);
            first = false;
        }
        return kept.toString();
    }

    static Map<String, Object> message(ThreadItem.Message message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "message");
        m.put("at", message.at().toString());
        m.put("message_kind", message.kind().dbValue());
        m.put("document_id", message.documentId() == null ? null : message.documentId().toString());
        m.put("body", message.body());
        m.put("web_body", webBody(message.body()));
        return m;
    }
}
