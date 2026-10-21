package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.adapter.in.ImageRefs;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.domain.AttentionItem;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /app/api/patients/{id}/attention} (plan B3): the চ্যাট attention stack as {@link AttentionUseCase#attention}
 * orders it ({@link AttentionItem#ORDER}: NOW › THIS_WEEK › retake › the doctor's follow-up › being read), one object per
 * card, each with exactly one action, {@code "action_key": "attn.open"}. Every paper card carries the day it is filed under
 * ({@code date}, {@code date_is_upload_day}: D2's {@code timeline_date}, read; plan D1 for the retake and reading cards). ROUTINE has no card. Copy is a key, never prose;
 * the follow-up's instruction is the doctor's words as written, beside the crop it was read from. A patient this family
 * cannot see is 404 {@code NOT_FOUND}, the same as one that does not exist.
 */
@Component
@RequiredArgsConstructor
public class AttentionApiHandler {

    static final String ACTION = "attn.open";

    private final AttentionUseCase attention;

    public Mono<ServerResponse> attention(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID patientId = patientId(request);
        return attention.attention(session.familyId(), patientId)
                .flatMap(items -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("items", items.stream().map(AttentionApiHandler::card).toList())));
    }

    static Map<String, Object> card(AttentionItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (item) {
            case AttentionItem.Urgent u -> {
                m.put("kind", "urgent");
                m.put("document_id", u.documentId().toString());
                m.put("level", u.level().dbValue());
                m.put("document_type", u.documentType());
                m.put("date", u.date().toString());
                m.put("date_is_upload_day", u.dateIsFallback());
                m.put("date_uncertain", u.dateUncertain());
                m.put("received_at", u.receivedAt().toString());
            }
            case AttentionItem.Retake r -> {
                m.put("kind", "retake");
                m.put("document_id", r.documentId().toString());
                m.put("date", r.date().toString());
                m.put("date_is_upload_day", r.dateIsFallback());
                m.put("received_at", r.receivedAt().toString());
                m.put("title_key", "attn.retake.title");
            }
            case AttentionItem.FollowUp f -> {
                m.put("kind", "follow_up");
                m.put("document_id", f.documentId().toString());
                m.put("instruction", f.instruction());
                m.put("due_date", f.dueDate().toString());
                m.put("crop", Map.of("src", ImageRefs.crop(f.documentId(), f.cropKey())));
                m.put("title_key", "attn.follow.title");
            }
            case AttentionItem.Reading r -> {
                m.put("kind", "reading");
                m.put("document_id", r.documentId().toString());
                m.put("date", r.date().toString());
                m.put("date_is_upload_day", r.dateIsFallback());
                m.put("received_at", r.receivedAt().toString());
                m.put("title_key", "attn.reading.title");
            }
        }
        m.put("action_key", ACTION);
        return m;
    }

    /** A path id that is not a UUID names no patient this family can see: 404, never a 400 that tells ids apart. */
    static UUID patientId(ServerRequest request) {
        try {
            return UUID.fromString(request.pathVariable("id"));
        } catch (IllegalArgumentException e) {
            throw BaymaxException.notFound("patient_not_found", "no such patient");
        }
    }
}
