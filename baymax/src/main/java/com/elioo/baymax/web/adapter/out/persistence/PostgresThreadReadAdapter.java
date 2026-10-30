package com.elioo.baymax.web.adapter.out.persistence;

import com.elioo.baymax.companion.domain.AnswerKind;
import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.outbound.adapter.out.persistence.PostgresOutboundMessageAdapter;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.web.application.port.out.ThreadReadPort;
import com.elioo.baymax.web.domain.ThreadItem;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The only {@link ThreadReadPort}: one SQL statement per page over {@code document} and {@code outbound_message},
 * schema-qualified and read only. A message is listed only when all four hold, each in the SQL where every caller meets it:
 * <ul>
 *   <li>{@code sent_at IS NOT NULL AND delivery_status = 'sent'}: the provider accepted it (a wamid by V14's second CHECK).
 *       {@code sent_at} alone is not enough: the log adapter stamps it with no delivery status, and nothing reached the
 *       family (S11);</li>
 *   <li>{@code patient_id} is this patient;</li>
 *   <li>{@code family_id} is the viewer's own account, the one WhatsApp delivered to (S4): a share member sees the papers
 *       and none of the owner's messages;</li>
 *   <li>{@code document_id} is NULL or still names a {@code document} row (S10): a deleted paper's delivered explanation
 *       stays stored (P1 is the PO's) and is not shown.</li>
 * </ul>
 * And one message is left out because the thread already shows it (review of #135, finding 6): a DONE paper's card
 * carries its newest explanation's body as {@code wording} ({@code ResultService.gate}, which reads
 * {@code latestOfKind}), so that one explanation is not listed a second time as a bubble after the card. It is picked by
 * the very order {@code latestOfKind} uses ({@link PostgresOutboundMessageAdapter#LATEST_OF_KIND_ORDER}); an older or
 * re-sent explanation of the same paper is still listed. A share member's card carries that wording too, as the paper
 * page does: S4 withholds the owner's message rows, not the card's wording.
 * The order is the upload or acceptance instant, then {@code paper} above {@code message} on a tie, then id, all newest
 * first, and the cursor compares the same triple.
 */
@Component
@RequiredArgsConstructor
public class PostgresThreadReadAdapter implements ThreadReadPort {

    private static final String S = BaymaxSchema.NAME;

    private static final String PAGE = """
            SELECT t.item, t.id, t.at, t.message_kind, t.document_id, t.body FROM (
                SELECT 'paper' AS item, d.id, d.created_at AS at, NULL::text AS message_kind, d.id AS document_id, NULL::text AS body
                  FROM %1$s.document d
                 WHERE d.patient_id = :patient
                UNION ALL
                SELECT 'message', m.id, m.sent_at, m.kind, m.document_id, m.body
                  FROM %1$s.outbound_message m
                 WHERE m.patient_id = :patient
                   AND m.family_id = :viewer
                   AND m.sent_at IS NOT NULL AND m.delivery_status = 'sent'
                   AND (m.document_id IS NULL OR EXISTS (SELECT 1 FROM %1$s.document x WHERE x.id = m.document_id))
                   AND NOT (m.kind = 'explanation' AND EXISTS (
                        SELECT 1 FROM %1$s.document c
                         WHERE c.id = m.document_id AND c.patient_id = :patient AND c.status = 'DONE'
                           AND m.id = (SELECT e.id FROM %1$s.outbound_message e
                                        WHERE e.document_id = c.id AND e.kind = 'explanation'
                                        ORDER BY %2$s LIMIT 1)))
            ) t
            WHERE (t.at, t.item, t.id) < (:at, :item, :id)
            ORDER BY t.at DESC, t.item DESC, t.id DESC
            LIMIT :limit
            """.formatted(S, PostgresOutboundMessageAdapter.LATEST_OF_KIND_ORDER);

    /**
     * {@link #PAGE} with a third source (plan CHAT-api): every answered চ্যাট turn ({@code answer IS NOT NULL}) the viewer's own
     * account asked about this patient, at the instant it was asked, whose cited papers all still exist (S10, the rule
     * {@code PostgresChatTurnAdapter.answered} applies). {@code 'ask'} sorts below {@code 'message'} on a tie, and the
     * cursor compares the same triple, so pages neither skip nor repeat across the three sources.
     */
    private static final String PAGE_WITH_ASKS = """
            SELECT t.item, t.id, t.at, t.message_kind, t.document_id, t.body, t.question FROM (
                SELECT 'paper' AS item, d.id, d.created_at AS at, NULL::text AS message_kind, d.id AS document_id, NULL::text AS body,
                       NULL::text AS question
                  FROM %1$s.document d
                 WHERE d.patient_id = :patient
                UNION ALL
                SELECT 'message', m.id, m.sent_at, m.kind, m.document_id, m.body, NULL::text
                  FROM %1$s.outbound_message m
                 WHERE m.patient_id = :patient
                   AND m.family_id = :viewer
                   AND m.sent_at IS NOT NULL AND m.delivery_status = 'sent'
                   AND (m.document_id IS NULL OR EXISTS (SELECT 1 FROM %1$s.document x WHERE x.id = m.document_id))
                   AND NOT (m.kind = 'explanation' AND EXISTS (
                        SELECT 1 FROM %1$s.document c
                         WHERE c.id = m.document_id AND c.patient_id = :patient AND c.status = 'DONE'
                           AND m.id = (SELECT e.id FROM %1$s.outbound_message e
                                        WHERE e.document_id = c.id AND e.kind = 'explanation'
                                        ORDER BY %2$s LIMIT 1)))
                UNION ALL
                SELECT 'ask', q.id, q.created_at, q.kind, NULL::uuid, q.answer, q.question
                  FROM %1$s.chat_turn q
                 WHERE q.patient_id = :patient
                   AND q.family_id = :viewer
                   AND q.answer IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM unnest(q.document_ids) cited
                                    WHERE NOT EXISTS (SELECT 1 FROM %1$s.document y WHERE y.id = cited))
            ) t
            WHERE (t.at, t.item, t.id) < (:at, :item, :id)
            ORDER BY t.at DESC, t.item DESC, t.id DESC
            LIMIT :limit
            """.formatted(S, PostgresOutboundMessageAdapter.LATEST_OF_KIND_ORDER);

    private final DatabaseClient db;

    @Override
    public Flux<ThreadItem> page(UUID viewerFamilyId, UUID patientId, ThreadItem.Cursor after, int limit) {
        return page(viewerFamilyId, patientId, after, limit, false);
    }

    @Override
    public Flux<ThreadItem> page(UUID viewerFamilyId, UUID patientId, ThreadItem.Cursor after, int limit, boolean asks) {
        return db.sql(asks ? PAGE_WITH_ASKS : PAGE)
                .bind("patient", patientId)
                .bind("viewer", viewerFamilyId)
                .bind("at", OffsetDateTime.ofInstant(after.at(), ZoneOffset.UTC))
                .bind("item", after.order())
                .bind("id", after.id())
                .bind("limit", limit)
                .map(PostgresThreadReadAdapter::item).all();
    }

    private static ThreadItem item(Readable row) {
        UUID id = row.get("id", UUID.class);
        var at = row.get("at", OffsetDateTime.class).toInstant();
        if ("paper".equals(row.get("item", String.class))) {
            return new ThreadItem.Paper(id, at);
        }
        if ("ask".equals(row.get("item", String.class))) {
            // call999 is the viewer's, decided by the caller at read time (ThreadItem.Ask#forViewer)
            return new ThreadItem.Ask(id, at, row.get("question", String.class), row.get("body", String.class),
                    AnswerKind.fromDbValue(row.get("message_kind", String.class)), false);
        }
        // the stored kind through the one enum every writer uses: a kind the database allows and Java does not know fails
        // loudly here, never as a message silently dropped (everyOutboundKindIsListedWithItsKind reads the kinds from V23's CHECK)
        return new ThreadItem.Message(id, at, OutboundMessage.Kind.fromDbValue(row.get("message_kind", String.class)),
                row.get("document_id", UUID.class), row.get("body", String.class));
    }
}
