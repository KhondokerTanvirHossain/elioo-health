package com.elioo.baymax.family.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.family.application.port.in.FamilyAccountUseCase;
import com.elioo.baymax.family.application.port.in.FreeTierUseCase;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.DeletionCounts;
import com.elioo.baymax.healthrecord.domain.DeletionReceipt;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.healthrecord.domain.ShareMember;
import com.elioo.baymax.storage.domain.DeletionReport;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Creates families, patients and share members with consent recorded server-side, and performs
 * delete-on-request as a hard delete inside the request: images first (objects, then ledger), then rows.
 * Log lines carry UUIDs only; a phone number never reaches the log.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FamilyAccountService implements FamilyAccountUseCase {

    /** E.164: leading +, 8 to 15 digits. Spaces and dashes are stripped before checking. */
    static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    static final int MAX_CHRONIC_FLAGS = 20;
    static final int MAX_FLAG_LENGTH = 40;

    private final HealthRecordPort records;
    private final FreeTierUseCase freeTier;
    private final DocumentStorageUseCase storage;
    private final Clock clock;

    @Override
    public Mono<FamilyAccount> createFamily(CreateFamilyCommand command) {
        if (!command.termsAccepted()) {
            return Mono.error(BaymaxException.badRequest("terms_not_accepted", "terms_accepted must be true"));
        }
        String number;
        String owner;
        try {
            number = normalizeNumber(command.whatsappNumber());
            owner = requireText(command.ownerName(), "owner_name", 120);
        } catch (IllegalArgumentException e) {
            return Mono.error(BaymaxException.badRequest("invalid_request", e.getMessage()));
        }
        Instant now = clock.instant();
        return records.findFamilyByWhatsapp(number)
                .flatMap(existing -> Mono.<FamilyAccount>error(
                        BaymaxException.conflict("family_exists", "a family with this WhatsApp number already exists")))
                .switchIfEmpty(Mono.defer(() -> records.createFamily(
                        new FamilyAccount(null, number, owner, FamilyAccount.Plan.FREE, now, now))))
                .doOnNext(f -> log.info("[baymax] family created id={} plan={}", f.id(), f.plan().dbValue()));
    }

    @Override
    public Mono<PatientProfile> addPatient(UUID familyId, AddPatientCommand command) {
        if (!command.proxyConsent()) {
            return Mono.error(BaymaxException.badRequest("proxy_consent_required", "proxy_consent must be true"));
        }
        String name;
        int age;
        PatientProfile.Sex sex;
        List<String> flags;
        try {
            name = requireText(command.name(), "name", 120);
            age = requireAge(command.age());
            sex = parseSex(command.sex());
            flags = normalizeFlags(command.chronicFlags());
        } catch (IllegalArgumentException e) {
            return Mono.error(BaymaxException.badRequest("invalid_request", e.getMessage()));
        }
        Instant now = clock.instant();
        return requireFamily(familyId)
                .flatMap(family -> freeTier.checkCanAddPatient(family).thenReturn(family))
                .flatMap(family -> records.createPatient(
                        new PatientProfile(null, family.id(), name, age, sex, flags, now, now)))
                .doOnNext(p -> log.info("[baymax] patient created id={} familyId={}", p.id(), p.familyId()));
    }

    @Override
    public Mono<ShareMember> addShareMember(UUID patientId, String whatsappNumber) {
        String number;
        try {
            number = normalizeNumber(whatsappNumber);
        } catch (IllegalArgumentException e) {
            return Mono.error(BaymaxException.badRequest("invalid_request", e.getMessage()));
        }
        return requirePatient(patientId)
                .flatMap(patient -> records.countShareMembers(patient.id()))
                .flatMap(count -> count >= 1
                        ? Mono.<ShareMember>error(BaymaxException.conflict("share_member_exists",
                                "this patient already has a share member (one per patient in v1)"))
                        : records.addShareMember(new ShareMember(null, patientId, number, clock.instant(), null)))
                .doOnNext(m -> log.info("[baymax] share member added id={} patientId={}", m.id(), m.patientId()));
    }

    @Override
    public Mono<DeletionReceipt> deletePatient(UUID patientId) {
        return requirePatient(patientId).flatMap(patient ->
                records.documentIdsOfPatient(patientId).collectList().flatMap(documentIds ->
                        // rows first, in one transaction; images only once that has committed (see deleteFamily)
                        records.deletePatient(patientId, documentIds).flatMap(counts ->
                                deleteObjects(storage.deletePatient(patient.familyId(), patientId), "patient", patientId)
                                        .map(objects -> receipt(counts, 0, objects)))))
                .doOnNext(r -> log.info("[baymax] patient deleted id={} documents={} objects={}",
                        patientId, r.documents(), r.objects()));
    }

    /**
     * Rows are deleted and committed FIRST; the bucket is emptied afterwards.
     *
     * <p>The reverse order destroyed images and then rolled the SQL back when the delete failed, leaving records
     * that claimed images existed which were already gone — silent and unrecoverable. This way round the only
     * failure mode is images outliving their rows: visible in the bucket, recoverable by a sweep, and invisible
     * to the family, whose record is gone — which is what they asked for under §6.3. A storage failure after the
     * commit is therefore logged with its keys for retry and does NOT fail the request.
     */
    @Override
    public Mono<DeletionReceipt> deleteFamily(UUID familyId) {
        return requireFamily(familyId).flatMap(family ->
                records.documentIdsOf(familyId).collectList().flatMap(documentIds ->
                        records.deleteFamily(familyId, documentIds).flatMap(counts ->
                                deleteObjects(storage.deleteFamily(familyId), "family", familyId)
                                        .map(objects -> receipt(counts, 1, objects)))))
                .doOnNext(r -> log.info("[baymax] family deleted id={} patients={} documents={} objects={}",
                        familyId, r.patients(), r.documents(), r.objects()));
    }

    /**
     * The rows are already gone, so a storage failure cannot undo the delete — it is recorded, not propagated.
     * The prefix is logged at ERROR with the marker {@code orphaned_objects} so a sweep can find and retry it;
     * the family still gets their confirmation.
     */
    private Mono<Long> deleteObjects(Mono<DeletionReport> deletion, String scope, UUID id) {
        return deletion.map(DeletionReport::objectsDeleted)
                .onErrorResume(e -> {
                    log.error("[baymax] orphaned_objects scope={} id={} — rows are deleted and committed, bucket "
                            + "objects under that prefix survive and need a sweep: {}", scope, id, e.toString());
                    return Mono.just(0L);
                });
    }

    private DeletionReceipt receipt(DeletionCounts counts, long families, long objects) {
        log.info("[baymax] deletion rows {}", counts.describe());
        return new DeletionReceipt(clock.instant(), families, counts.of("patient_profile"),
                counts.of("document"), objects);
    }

    private Mono<FamilyAccount> requireFamily(UUID familyId) {
        return records.findFamily(familyId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("family_not_found", "no family with id " + familyId)));
    }

    private Mono<PatientProfile> requirePatient(UUID patientId) {
        return records.findPatient(patientId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + patientId)));
    }

    /** Public since BMX-5: OTP login normalises the same way before hashing. */
    public static String normalizeNumber(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("whatsapp_number is required");
        }
        // Bangla digits fold to ASCII, and the two ways a Bangladeshi number is actually written are accepted:
        // 01XXXXXXXXX (11 digits, how everyone types it) and 8801XXXXXXXXX (no plus). Found on the first real
        // walkthrough: the login form said "a code is on its way" to a 017… entry and issued nothing.
        StringBuilder folded = new StringBuilder();
        for (char c : raw.toCharArray()) {
            folded.append(Character.isDigit(c) ? (char) ('0' + Character.digit(c, 10)) : c);
        }
        String number = folded.toString().replaceAll("[\\s\\-()]", "");
        if (number.matches("^01\\d{9}$")) {
            number = "+88" + number;
        } else if (number.matches("^8801\\d{9}$")) {
            number = "+" + number;
        }
        if (!E164.matcher(number).matches()) {
            throw new IllegalArgumentException("whatsapp_number must be E.164, e.g. +8801XXXXXXXXX");
        }
        return number;
    }

    static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters");
        }
        return trimmed;
    }

    static int requireAge(Integer age) {
        if (age == null || age < 0 || age > 130) {
            throw new IllegalArgumentException("age must be between 0 and 130");
        }
        return age;
    }

    static PatientProfile.Sex parseSex(String sex) {
        if (sex == null || sex.isBlank()) {
            throw new IllegalArgumentException("sex is required: male | female | other | unknown");
        }
        try {
            return PatientProfile.Sex.valueOf(sex.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("sex must be one of male | female | other | unknown");
        }
    }

    public static List<String> normalizeFlags(List<String> flags) {
        if (flags == null) {
            return List.of();
        }
        if (flags.size() > MAX_CHRONIC_FLAGS) {
            throw new IllegalArgumentException("at most " + MAX_CHRONIC_FLAGS + " chronic_flags");
        }
        return flags.stream()
                .filter(f -> f != null && !f.isBlank())
                .map(f -> f.trim().toLowerCase(Locale.ROOT))
                .peek(f -> {
                    if (f.length() > MAX_FLAG_LENGTH) {
                        throw new IllegalArgumentException("chronic_flags entries must be at most " + MAX_FLAG_LENGTH + " characters");
                    }
                })
                .distinct()
                .toList();
    }
}
