package com.elioo.baymax.companion.adapter.out.persistence;

import com.elioo.baymax.companion.application.port.out.ChatTurnPort;
import com.elioo.baymax.companion.domain.AnswerKind;
import com.elioo.baymax.companion.domain.ChatOutcome;
import com.elioo.baymax.companion.domain.ChatTurn;
import com.elioo.baymax.companion.domain.CitedMedicine;
import com.elioo.baymax.config.BaymaxSchema;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The only {@link ChatTurnPort} (V29). Every statement is schema-qualified; nothing here logs, because every column but the
 * ids and the counts is the family's text or what they were shown.
 *
 * <p>The daily cap is reserved in one transaction: the account's {@code family_account} row is locked {@code FOR NO KEY
 * UPDATE} first, which serialises the reservations of one account (the lock conflicts with itself) without blocking the
 * key-share locks a foreign-key insert takes (a paper, a দেখেছি, a WhatsApp window row), while {@code deleteFamily}'s
 * {@code FOR UPDATE} still excludes it. The count then runs in the INSERT's own statement, after the lock is granted, so
 * under READ COMMITTED it sees every reservation committed before it: twenty concurrent presses at a cap of one store one
 * row. Without the lock all twenty counts can read zero at once.
 */
@Component
public class PostgresChatTurnAdapter implements ChatTurnPort {

    private static final String S = BaymaxSchema.NAME;

    private static final String LOCK_ACCOUNT = "SELECT id FROM " + S + ".family_account WHERE id = :family FOR NO KEY UPDATE";

    private static final String RESERVE_COUNTED = """
            INSERT INTO %1$s.chat_turn (family_id, patient_id, lang, question, counted)
            SELECT CAST(:family AS uuid), CAST(:patient AS uuid), CAST(:lang AS varchar), CAST(:question AS text), true
            WHERE (SELECT count(*) FROM %1$s.chat_turn
                    WHERE family_id = :family AND counted AND created_at >= :dayStart) < :cap
            RETURNING id
            """.formatted(S);

    private static final String RESERVE_UNCOUNTED = """
            INSERT INTO %1$s.chat_turn (family_id, patient_id, lang, question, counted)
            VALUES (:family, :patient, :lang, :question, false)
            RETURNING id
            """.formatted(S);

    private static final String COMPLETE = """
            UPDATE %1$s.chat_turn
               SET answer = :answer, kind = :kind, handler = :handler, violations = :violations, model_calls = :calls,
                   input_tokens = :inTokens, output_tokens = :outTokens, cost_usd = :cost, latency_ms = :latency,
                   document_ids = :documents, medicine_document_ids = :medicineDocuments, medicine_crop_keys = :medicineCrops,
                   answered_at = now()
             WHERE id = :id
            """.formatted(S);

    /** S10: a turn is listed only while every paper it cites still exists; a turn citing none is listed. */
    private static final String ANSWERED = """
            SELECT t.* FROM %1$s.chat_turn t
             WHERE t.family_id = :family AND t.patient_id = :patient AND t.answer IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM unnest(t.document_ids) d
                                WHERE NOT EXISTS (SELECT 1 FROM %1$s.document x WHERE x.id = d))
             ORDER BY t.created_at DESC, t.id DESC
            """.formatted(S);

    private final DatabaseClient db;
    private final TransactionalOperator transactional;

    /** The operator is built on this client's own connection factory, so the lock and the insert share one transaction. */
    public PostgresChatTurnAdapter(DatabaseClient db) {
        this.db = db;
        this.transactional = TransactionalOperator.create(new R2dbcTransactionManager(db.getConnectionFactory()));
    }

    @Override
    public Mono<UUID> reserveCounted(UUID familyId, UUID patientId, String lang, String question, Instant dayStart, int cap) {
        Mono<UUID> work = db.sql(LOCK_ACCOUNT).bind("family", familyId).fetch().all().then()
                .then(db.sql(RESERVE_COUNTED)
                        .bind("family", familyId).bind("patient", patientId).bind("lang", lang).bind("question", question)
                        .bind("dayStart", OffsetDateTime.ofInstant(dayStart, ZoneOffset.UTC)).bind("cap", (long) cap)
                        .map(row -> row.get("id", UUID.class)).one());
        return transactional.transactional(work);
    }

    @Override
    public Mono<UUID> reserveUncounted(UUID familyId, UUID patientId, String lang, String question) {
        return db.sql(RESERVE_UNCOUNTED)
                .bind("family", familyId).bind("patient", patientId).bind("lang", lang).bind("question", question)
                .map(row -> row.get("id", UUID.class)).one();
    }

    @Override
    public Mono<Void> complete(UUID turnId, ChatOutcome outcome) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(COMPLETE)
                .bind("id", turnId)
                .bind("answer", outcome.answer())
                .bind("kind", outcome.kind().dbValue())
                .bind("handler", outcome.handler())
                .bind("calls", outcome.modelCalls())
                .bind("inTokens", outcome.inputTokens())
                .bind("outTokens", outcome.outputTokens())
                .bind("latency", outcome.latencyMs())
                .bind("documents", outcome.documentIds().toArray(UUID[]::new))
                .bind("medicineDocuments", outcome.medicines().stream().map(CitedMedicine::documentId).toArray(UUID[]::new))
                .bind("medicineCrops", outcome.medicines().stream().map(CitedMedicine::cropKey).toArray(String[]::new));
        spec = outcome.violationCodes().isEmpty() ? spec.bindNull("violations", String.class)
                : spec.bind("violations", String.join(",", outcome.violationCodes()));
        spec = outcome.costUsd() == null ? spec.bindNull("cost", BigDecimal.class) : spec.bind("cost", outcome.costUsd());
        return spec.fetch().rowsUpdated().then();
    }

    @Override
    public Flux<ChatTurn> answered(UUID familyId, UUID patientId) {
        return db.sql(ANSWERED).bind("family", familyId).bind("patient", patientId).map(PostgresChatTurnAdapter::turn).all();
    }

    private static ChatTurn turn(Readable row) {
        String kind = row.get("kind", String.class);
        UUID[] documents = row.get("document_ids", UUID[].class);
        return new ChatTurn(row.get("id", UUID.class), row.get("family_id", UUID.class), row.get("patient_id", UUID.class),
                row.get("lang", String.class), row.get("question", String.class), row.get("answer", String.class),
                kind == null ? null : AnswerKind.fromDbValue(kind), Boolean.TRUE.equals(row.get("counted", Boolean.class)),
                row.get("handler", String.class), row.get("violations", String.class), intOf(row, "model_calls"),
                intOf(row, "input_tokens"), intOf(row, "output_tokens"), row.get("cost_usd", BigDecimal.class),
                row.get("latency_ms", Long.class), documents == null ? List.of() : Arrays.asList(documents),
                instant(row, "created_at"), instant(row, "answered_at"), medicines(row));
    }

    /** V30's two arrays, paired by position: the quoted lines, each with its crop. */
    private static List<CitedMedicine> medicines(Readable row) {
        return CitedMedicine.paired(row.get("medicine_document_ids", UUID[].class), row.get("medicine_crop_keys", String[].class));
    }

    private static int intOf(Readable row, String column) {
        Integer v = row.get(column, Integer.class);
        return v == null ? 0 : v;
    }

    private static Instant instant(Readable row, String column) {
        OffsetDateTime t = row.get(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
