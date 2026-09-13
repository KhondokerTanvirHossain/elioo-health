package com.elioo.baymax.healthrecord.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
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
@Table(name = "family_account", schema = BaymaxSchema.NAME)
public class FamilyAccountEntity {

    @Id
    private UUID id;
    @Column("whatsapp_number")
    private String whatsappNumber;
    @Column("owner_name")
    private String ownerName;
    private String plan;
    @Column("terms_accepted_at")
    private Instant termsAcceptedAt;
    @Column("created_at")
    private Instant createdAt;

    static FamilyAccountEntity from(FamilyAccount f) {
        return new FamilyAccountEntity(f.id(), f.whatsappNumber(), f.ownerName(), f.plan().dbValue(),
                f.termsAcceptedAt(), f.createdAt());
    }

    FamilyAccount toRecord() {
        return new FamilyAccount(id, whatsappNumber, ownerName, FamilyAccount.Plan.fromDbValue(plan),
                termsAcceptedAt, createdAt);
    }
}
