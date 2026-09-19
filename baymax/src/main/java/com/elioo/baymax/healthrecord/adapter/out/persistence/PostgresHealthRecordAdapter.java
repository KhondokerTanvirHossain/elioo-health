package com.elioo.baymax.healthrecord.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.DeletionCounts;
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
import java.util.Map;
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
    public Mono<PatientProfile> updateChronicFlags(UUID patientId, List<String> chronicFlags) {
        return patients.findById(patientId).flatMap(entity -> {
            entity.setChronicFlags(chronicFlags == null ? new String[0] : chronicFlags.toArray(String[]::new));
            return patients.save(entity);
        }).map(PatientProfileEntity::toRecord);
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
        // from document itself, not stored_object: a document with no images was invisible to the delete
        return db.sql("SELECT id FROM " + S + ".document WHERE family_id = :family")
                .bind("family", familyId).map((row, meta) -> row.get(0, UUID.class)).all();
    }

    @Override
    public Flux<UUID> documentIdsOfPatient(UUID patientId) {
        return db.sql("SELECT id FROM " + S + ".document WHERE patient_id = :patient")
                .bind("patient", patientId).map((row, meta) -> row.get(0, UUID.class)).all();
    }

    @Override
    public Mono<Long> countDocumentsOfPatient(UUID patientId) {
        return db.sql("SELECT COUNT(DISTINCT document_id) FROM " + S + ".stored_object WHERE patient_id = :patient")
                .bind("patient", patientId).map((row, meta) -> row.get(0, Long.class)).one().defaultIfEmpty(0L);
    }

    /**
     * The whole graph for one family, in FK order, in one transaction. Every table in the schema that carries a
     * family, patient or document id is deleted here — including {@code audit_event} and {@code stored_object},
     * which carry the ids with no foreign key at all, so nothing in the database would ever have flagged them.
     *
     * <p>Before this, the method deleted only share_member, patient_profile and family_account: {@code document}
     * was never deleted, so the patient_profile delete violated {@code document_patient_id_fkey} and the route
     * had never once worked on a family that had documents — which is every real family. §6.3 (delete on request
     * within 24h) could not be honoured.
     */
    @Override
    @Transactional
    public Mono<DeletionCounts> deleteFamily(UUID familyId, Collection<UUID> documentIds) {
        String patientsOf = "(SELECT id FROM " + S + ".patient_profile WHERE family_id = :family)";
        return detachCostRows(documentIds)
                .then(count("audit_event", "DELETE FROM " + S + ".audit_event WHERE family_id = :family", "family", familyId))
                .flatMap(c -> add(c, "share_member", "DELETE FROM " + S + ".share_member WHERE patient_id IN " + patientsOf, "family", familyId))
                .flatMap(c -> add(c, "observation", "DELETE FROM " + S + ".observation WHERE patient_id IN " + patientsOf, "family", familyId))
                .flatMap(c -> add(c, "medication_event", "DELETE FROM " + S + ".medication_event WHERE patient_id IN " + patientsOf, "family", familyId))
                .flatMap(c -> add(c, "follow_up", "DELETE FROM " + S + ".follow_up WHERE patient_id IN " + patientsOf, "family", familyId))
                .flatMap(c -> add(c, "outbound_message", "DELETE FROM " + S + ".outbound_message WHERE family_id = :family", "family", familyId))
                .flatMap(c -> add(c, "nudge", "DELETE FROM " + S + ".nudge WHERE family_id = :family", "family", familyId))
                .flatMap(c -> add(c, "stored_object", "DELETE FROM " + S + ".stored_object WHERE family_id = :family", "family", familyId))
                .flatMap(c -> add(c, "document", "DELETE FROM " + S + ".document WHERE family_id = :family", "family", familyId))
                .flatMap(c -> add(c, "web_session", "DELETE FROM " + S + ".web_session WHERE family_id = :family", "family", familyId))
                .flatMap(c -> add(c, "otp_code", "DELETE FROM " + S + ".otp_code WHERE family_id = :family", "family", familyId))
                .flatMap(c -> add(c, "patient_profile", "DELETE FROM " + S + ".patient_profile WHERE family_id = :family", "family", familyId))
                .flatMap(c -> add(c, "family_account", "DELETE FROM " + S + ".family_account WHERE id = :family", "family", familyId));
    }

    /** The same graph scoped to one patient; the family and its own rows survive. */
    @Override
    @Transactional
    public Mono<DeletionCounts> deletePatient(UUID patientId, Collection<UUID> documentIds) {
        return detachCostRows(documentIds)
                .then(count("audit_event", "DELETE FROM " + S + ".audit_event WHERE patient_id = :patient", "patient", patientId))
                .flatMap(c -> add(c, "share_member", "DELETE FROM " + S + ".share_member WHERE patient_id = :patient", "patient", patientId))
                .flatMap(c -> add(c, "observation", "DELETE FROM " + S + ".observation WHERE patient_id = :patient", "patient", patientId))
                .flatMap(c -> add(c, "medication_event", "DELETE FROM " + S + ".medication_event WHERE patient_id = :patient", "patient", patientId))
                .flatMap(c -> add(c, "follow_up", "DELETE FROM " + S + ".follow_up WHERE patient_id = :patient", "patient", patientId))
                .flatMap(c -> add(c, "outbound_message", "DELETE FROM " + S + ".outbound_message WHERE patient_id = :patient", "patient", patientId))
                .flatMap(c -> add(c, "nudge", "DELETE FROM " + S + ".nudge WHERE patient_id = :patient", "patient", patientId))
                .flatMap(c -> add(c, "stored_object", "DELETE FROM " + S + ".stored_object WHERE patient_id = :patient", "patient", patientId))
                .flatMap(c -> add(c, "document", "DELETE FROM " + S + ".document WHERE patient_id = :patient", "patient", patientId))
                .flatMap(c -> add(c, "patient_profile", "DELETE FROM " + S + ".patient_profile WHERE id = :patient", "patient", patientId));
    }

    private Mono<DeletionCounts> count(String table, String sql, String bind, UUID id) {
        return db.sql(sql).bind(bind, id).fetch().rowsUpdated()
                .map(n -> new DeletionCounts(new java.util.LinkedHashMap<>(Map.of(table, n))));
    }

    private Mono<DeletionCounts> add(DeletionCounts soFar, String table, String sql, String bind, UUID id) {
        return db.sql(sql).bind(bind, id).fetch().rowsUpdated().map(n -> soFar.with(table, n));
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
