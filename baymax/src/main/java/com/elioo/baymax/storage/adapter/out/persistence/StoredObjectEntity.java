package com.elioo.baymax.storage.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.storage.domain.ObjectKind;
import com.elioo.baymax.storage.domain.StoredObject;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/** R2DBC row of {@code baymax.stored_object}: keys, sizes and pixel dimensions, never bytes. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "stored_object", schema = BaymaxSchema.NAME)
public class StoredObjectEntity {

    @Id
    private UUID id;
    @Column("family_id")
    private UUID familyId;
    @Column("patient_id")
    private UUID patientId;
    @Column("document_id")
    private UUID documentId;
    private String kind;
    @Column("page_no")
    private Integer pageNo;
    @Column("item_id")
    private String itemId;
    @Column("storage_key")
    private String storageKey;
    @Column("content_type")
    private String contentType;
    @Column("size_bytes")
    private long sizeBytes;
    @Column("created_at")
    private Instant createdAt;
    @Column("width_px")
    private Integer widthPx;
    @Column("height_px")
    private Integer heightPx;
    @Column("dimensions_checked_at")
    private Instant dimensionsCheckedAt;

    static StoredObjectEntity from(StoredObject o) {
        return new StoredObjectEntity(o.id(), o.familyId(), o.patientId(), o.documentId(), o.kind().dbValue(),
                o.pageNo(), o.itemId(), o.storageKey(), o.contentType(), o.sizeBytes(), o.createdAt(), o.widthPx(), o.heightPx(),
                o.dimensionsCheckedAt());
    }

    StoredObject toRecord() {
        return new StoredObject(id, familyId, patientId, documentId, ObjectKind.fromDbValue(kind), pageNo, itemId,
                storageKey, contentType, sizeBytes, createdAt, widthPx, heightPx, dimensionsCheckedAt);
    }
}
