package com.elioo.baymax.healthrecord.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "patient_profile", schema = BaymaxSchema.NAME)
public class PatientProfileEntity {

    @Id
    private UUID id;
    @Column("family_id")
    private UUID familyId;
    private String name;
    private int age;
    private String sex;
    @Column("chronic_flags")
    private String[] chronicFlags;
    @Column("proxy_consent_at")
    private Instant proxyConsentAt;
    @Column("created_at")
    private Instant createdAt;

    static PatientProfileEntity from(PatientProfile p) {
        return new PatientProfileEntity(p.id(), p.familyId(), p.name(), p.age(), p.sex().dbValue(),
                p.chronicFlags() == null ? new String[0] : p.chronicFlags().toArray(String[]::new),
                p.proxyConsentAt(), p.createdAt());
    }

    PatientProfile toRecord() {
        return new PatientProfile(id, familyId, name, age, PatientProfile.Sex.fromDbValue(sex),
                chronicFlags == null ? List.of() : List.of(chronicFlags), proxyConsentAt, createdAt);
    }
}
