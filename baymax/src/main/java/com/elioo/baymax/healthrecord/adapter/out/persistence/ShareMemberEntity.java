package com.elioo.baymax.healthrecord.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.healthrecord.domain.ShareMember;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "share_member", schema = BaymaxSchema.NAME)
public class ShareMemberEntity {

    @Id
    private UUID id;
    @Column("patient_id")
    private UUID patientId;
    @Column("whatsapp_number")
    private String whatsappNumber;
    @Column("added_at")
    private Instant addedAt;
    @Column("notified_at")
    private Instant notifiedAt;

    static ShareMemberEntity from(ShareMember m) {
        return new ShareMemberEntity(m.id(), m.patientId(), m.whatsappNumber(), m.addedAt(), m.notifiedAt());
    }

    ShareMember toRecord() {
        return new ShareMember(id, patientId, whatsappNumber, addedAt, notifiedAt);
    }
}
