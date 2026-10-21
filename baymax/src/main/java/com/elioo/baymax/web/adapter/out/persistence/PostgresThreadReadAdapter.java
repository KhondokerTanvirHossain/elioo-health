package com.elioo.baymax.web.adapter.out.persistence;

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

    private final DatabaseClient db;

    @Override
    public Flux<ThreadItem> page(UUID viewerFamilyId, UUID patientId, ThreadItem.Cursor after, int limit) {
        return db.sql(PAGE)
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
        // the stored kind through the one enum every writer uses: a kind the database allows and Java does not know fails
        // loudly here, never as a message silently dropped (everyOutboundKindIsListedWithItsKind reads the kinds from V23's CHECK)
        return new ThreadItem.Message(id, at, OutboundMessage.Kind.fromDbValue(row.get("message_kind", String.class)),
                row.get("document_id", UUID.class), row.get("body", String.class));
    }
}
