package com.elioo.baymax.aicall.adapter.out.persistence;

import com.elioo.baymax.aicall.domain.AiCallPurpose;
import com.elioo.baymax.aicall.domain.AiCallRecord;
import com.elioo.baymax.config.BaymaxSchema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** R2DBC row of {@code baymax.ai_call_log}. Schema-qualified on purpose: the connection's search path is medscribe. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "ai_call_log", schema = BaymaxSchema.NAME)
public class AiCallLogEntity {

    @Id
    private UUID id;
    @Column("document_id")
    private UUID documentId;
    private String purpose;
    private String provider;
    private String model;
    @Column("input_tokens")
    private int inputTokens;
    @Column("output_tokens")
    private int outputTokens;
    @Column("cost_usd")
    private BigDecimal costUsd;
    @Column("latency_ms")
    private Integer latencyMs;
    private Double confidence;
    @Column("created_at")
    private Instant createdAt;
    private String status;

    static AiCallLogEntity from(AiCallRecord r) {
        return new AiCallLogEntity(r.id(), r.documentId(), r.purpose().dbValue(), r.provider(), r.model(),
                r.inputTokens(), r.outputTokens(), r.costUsd(),
                r.latencyMs() == null ? null : Math.toIntExact(Math.min(r.latencyMs(), Integer.MAX_VALUE)),
                r.confidence(), r.createdAt(), r.status().dbValue());
    }

    AiCallRecord toRecord() {
        return new AiCallRecord(id, documentId, AiCallPurpose.fromDbValue(purpose), provider, model,
                inputTokens, outputTokens, costUsd, latencyMs == null ? null : latencyMs.longValue(),
                confidence, createdAt, AiCallRecord.Status.fromDbValue(status));
    }
}
