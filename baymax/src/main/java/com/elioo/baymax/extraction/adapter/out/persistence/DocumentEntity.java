package com.elioo.baymax.extraction.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.VerifiedItems;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "document", schema = BaymaxSchema.NAME)
public class DocumentEntity {

    @Id
    private UUID id;
    @Column("patient_id")
    private UUID patientId;
    @Column("family_id")
    private UUID familyId;
    @Column("document_type")
    private String documentType;
    @Column("doc_date")
    private LocalDate docDate;
    private String facility;
    /** JSONB; Spring Data R2DBC maps it as String and Postgres casts on write. */
    @Column("extraction_json")
    private String extractionJson;
    @Column("confidence_overall")
    private Double confidenceOverall;
    private String status;
    @Column("status_reason")
    private String statusReason;
    @Column("model_final")
    private String modelFinal;
    @Column("cost_usd")
    private BigDecimal costUsd;
    @Column("page_count")
    private int pageCount;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;
    @Column("unverified_values")
    private int unverifiedValues;
    @Column("unverified_medicines")
    private int unverifiedMedicines;
    @Column("unverified_follow_up")
    private int unverifiedFollowUp;

    static DocumentEntity from(Document d) {
        return new DocumentEntity(d.id(), d.patientId(), d.familyId(), d.documentType(), d.docDate(),
                d.facility(), d.extractionJson(), d.confidenceOverall(), d.status().name(), d.statusReason(),
                d.modelFinal(), d.costUsd(), d.pageCount(), d.createdAt(), d.updatedAt(),
                d.unverified().values(), d.unverified().medicines(), d.unverified().followUp());
    }

    Document toRecord() {
        return new Document(id, patientId, familyId, documentType, docDate, facility, extractionJson,
                confidenceOverall, Document.Status.fromDbValue(status), statusReason, modelFinal, costUsd,
                pageCount, createdAt, updatedAt,
                new VerifiedItems.Unverified(unverifiedValues, unverifiedMedicines, unverifiedFollowUp));
    }
}
