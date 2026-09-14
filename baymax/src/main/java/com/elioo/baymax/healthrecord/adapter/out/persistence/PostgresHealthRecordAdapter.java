package com.elioo.baymax.healthrecord.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.FamilyActivity;
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
import java.time.YearMonth;
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
    public Mono<Long> countDocumentsInMonth(UUID familyId, YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return db.sql("SELECT COUNT(DISTINCT document_id) FROM " + S + ".stored_object "
                        + "WHERE family_id = :family AND created_at >= :from AND created_at < :to")
                .bind("family", familyId).bind("from", odt(from)).bind("to", odt(to))
                .map((row, meta) -> row.get(0, Long.class)).one().defaultIfEmpty(0L);
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
                               (SELECT COUNT(DISTINCT s.document_id) FROM %1$s.stored_object s
                                 WHERE s.family_id = f.id AND s.created_at >= :from AND s.created_at < :to) AS documents,
                               (SELECT MAX(s.created_at) FROM %1$s.stored_object s WHERE s.family_id = f.id) AS last_document_at
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
}
