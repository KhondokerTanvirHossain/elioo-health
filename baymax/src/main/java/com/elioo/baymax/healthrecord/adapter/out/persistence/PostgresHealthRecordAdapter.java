package com.elioo.baymax.healthrecord.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.FamilyActivity;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.healthrecord.domain.ShareMember;
import com.elioo.baymax.common.error.BaymaxException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** The only {@link HealthRecordPort} implementation in v1. Every table name is schema-qualified. */
@Component
@RequiredArgsConstructor
public class PostgresHealthRecordAdapter implements HealthRecordPort {

    private static final String S = BaymaxSchema.NAME;

    private final FamilyAccountRepository families;
    private final PatientProfileRepository patients;
    private final ShareMemberRepository shares;
    private final DatabaseClient db;

    @Override
    public Mono<FamilyAccount> createFamily(FamilyAccount family) {
        FamilyAccountEntity entity = FamilyAccountEntity.from(family);
        entity.setId(null);
        return families.save(entity).map(FamilyAccountEntity::toRecord)
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> BaymaxException.conflict("family_exists", "a family with this WhatsApp number already exists"));
    }

    @Override
    public Mono<FamilyAccount> findFamily(UUID familyId) {
        return families.findById(familyId).map(FamilyAccountEntity::toRecord);
    }

    @Override
    public Mono<FamilyAccount> findFamilyByWhatsapp(String whatsappNumber) {
        return families.findByWhatsappNumber(whatsappNumber).map(FamilyAccountEntity::toRecord);
    }

    @Override
    public Mono<PatientProfile> createPatient(PatientProfile patient) {
        PatientProfileEntity entity = PatientProfileEntity.from(patient);
        entity.setId(null);
        return patients.save(entity).map(PatientProfileEntity::toRecord);
    }

    @Override
    public Mono<PatientProfile> findPatient(UUID patientId) {
        return patients.findById(patientId).map(PatientProfileEntity::toRecord);
    }

    @Override
    public Flux<PatientProfile> patientsOf(UUID familyId) {
        return patients.findByFamilyIdOrderByCreatedAt(familyId).map(PatientProfileEntity::toRecord);
    }

    @Override
    public Mono<Long> countPatients(UUID familyId) {
        return patients.countByFamilyId(familyId);
    }

    @Override
    public Mono<ShareMember> addShareMember(ShareMember member) {
        ShareMemberEntity entity = ShareMemberEntity.from(member);
        entity.setId(null);
        return shares.save(entity).map(ShareMemberEntity::toRecord)
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> BaymaxException.conflict("share_member_exists", "this number is already a share member of the patient"));
    }

    @Override
    public Mono<Long> countShareMembers(UUID patientId) {
        return shares.countByPatientId(patientId);
    }

    @Override
    public Flux<UUID> documentIdsOf(UUID familyId) {
        return db.sql("SELECT DISTINCT document_id FROM " + S + ".stored_object WHERE family_id = :family")
                .bind("family", familyId).map((row, meta) -> row.get(0, UUID.class)).all();
    }

    @Override
    public Flux<UUID> documentIdsOfPatient(UUID patientId) {
        return db.sql("SELECT DISTINCT document_id FROM " + S + ".stored_object WHERE patient_id = :patient")
                .bind("patient", patientId).map((row, meta) -> row.get(0, UUID.class)).all();
    }

    @Override
    public Mono<Long> countDocumentsOfPatient(UUID patientId) {
        return db.sql("SELECT COUNT(DISTINCT document_id) FROM " + S + ".stored_object WHERE patient_id = :patient")
                .bind("patient", patientId).map((row, meta) -> row.get(0, Long.class)).one().defaultIfEmpty(0L);
    }

    @Override
    @Transactional
    public Mono<Long> deleteFamily(UUID familyId, Collection<UUID> documentIds) {
        return detachCostRows(documentIds)
                .then(db.sql("DELETE FROM " + S + ".share_member WHERE patient_id IN "
                        + "(SELECT id FROM " + S + ".patient_profile WHERE family_id = :family)")
                        .bind("family", familyId).fetch().rowsUpdated())
                .then(db.sql("DELETE FROM " + S + ".patient_profile WHERE family_id = :family")
                        .bind("family", familyId).fetch().rowsUpdated())
                .flatMap(patientsRemoved -> db.sql("DELETE FROM " + S + ".family_account WHERE id = :family")
                        .bind("family", familyId).fetch().rowsUpdated()
                        .thenReturn(patientsRemoved));
    }

    @Override
    @Transactional
    public Mono<Long> deletePatient(UUID patientId, Collection<UUID> documentIds) {
        return detachCostRows(documentIds)
                .then(db.sql("DELETE FROM " + S + ".share_member WHERE patient_id = :patient")
                        .bind("patient", patientId).fetch().rowsUpdated())
                .then(db.sql("DELETE FROM " + S + ".patient_profile WHERE id = :patient")
                        .bind("patient", patientId).fetch().rowsUpdated());
    }

    /** ai_call_log keeps its numbers (no PHI) but loses the link to the deleted subject. */
    private Mono<Void> detachCostRows(Collection<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Mono.empty();
        }
        return db.sql("UPDATE " + S + ".ai_call_log SET document_id = NULL WHERE document_id = ANY(:ids)")
                .bind("ids", List.copyOf(documentIds).toArray(UUID[]::new)).fetch().rowsUpdated().then();
    }

    @Override
    public Flux<FamilyActivity> familyActivity(Instant from, Instant to) {
        return db.sql("""
                        SELECT f.id AS family_id, f.plan,
                               (SELECT COUNT(*) FROM %1$s.patient_profile p WHERE p.family_id = f.id) AS patients,
                               (SELECT COUNT(*) FROM %1$s.document d
                                 WHERE d.family_id = f.id AND d.created_at >= :from AND d.created_at < :to) AS documents,
                               (SELECT MAX(d.created_at) FROM %1$s.document d WHERE d.family_id = f.id) AS last_document_at
                        FROM %1$s.family_account f
                        ORDER BY f.created_at
                        """.formatted(S))
                .bind("from", odt(from)).bind("to", odt(to))
                .map((row, meta) -> new FamilyActivity(
                        row.get("family_id", UUID.class),
                        FamilyAccount.Plan.fromDbValue(row.get("plan", String.class)),
                        row.get("patients", Long.class),
                        row.get("documents", Long.class),
                        row.get("last_document_at", OffsetDateTime.class) == null ? null
                                : row.get("last_document_at", OffsetDateTime.class).toInstant()))
                .all();
    }

    private static OffsetDateTime odt(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // --- BMX-5: who may see a patient, decided in one query -------------------------------------------

    private static final String VISIBLE_SQL = """
            SELECT p.id, p.family_id, p.name, p.age, p.sex, p.chronic_flags, p.proxy_consent_at, p.created_at,
                   (p.family_id = :family) AS owner
            FROM %1$s.patient_profile p
            WHERE (p.family_id = :family
                   OR EXISTS (SELECT 1 FROM %1$s.share_member s JOIN %1$s.family_account f ON f.whatsapp_number = s.whatsapp_number
                              WHERE s.patient_id = p.id AND f.id = :family))
            """.formatted(S);

    @Override
    public Flux<PatientAccess> visiblePatients(UUID familyId) {
        return db.sql(VISIBLE_SQL + " ORDER BY owner DESC, p.created_at")
                .bind("family", familyId)
                .map((row, meta) -> access(row)).all();
    }

    @Override
    public Mono<PatientAccess> visiblePatient(UUID familyId, UUID patientId) {
        return db.sql(VISIBLE_SQL + " AND p.id = :patient")
                .bind("family", familyId).bind("patient", patientId)
                .map((row, meta) -> access(row)).one();
    }

    private static PatientAccess access(io.r2dbc.spi.Row row) {
        String[] flags = row.get("chronic_flags", String[].class);
        PatientProfile profile = new PatientProfile(row.get("id", UUID.class), row.get("family_id", UUID.class),
                row.get("name", String.class), row.get("age", Integer.class),
                PatientProfile.Sex.fromDbValue(row.get("sex", String.class)),
                flags == null ? List.of() : List.of(flags),
                offset(row, "proxy_consent_at"), offset(row, "created_at"));
        return new PatientAccess(profile, Boolean.TRUE.equals(row.get("owner", Boolean.class)));
    }

    private static Instant offset(io.r2dbc.spi.Row row, String col) {
        OffsetDateTime v = row.get(col, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }
}
