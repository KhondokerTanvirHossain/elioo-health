package com.elioo.baymax.family.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.family.application.port.in.FreeTierUseCase;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

/** Free tier: {@code baymax.free.max-patients} profiles and {@code baymax.free.max-docs-per-month} documents per UTC calendar month. */
@Service
@RequiredArgsConstructor
public class FreeTierService implements FreeTierUseCase {

    private final HealthRecordPort records;
    private final DocumentRecordPort documents;
    private final BaymaxProperties properties;
    private final Clock clock;

    @Override
    public Mono<Void> checkCanAddPatient(FamilyAccount family) {
        if (!family.isFree()) {
            return Mono.empty();
        }
        int max = properties.getFree().getMaxPatients();
        return records.countPatients(family.id())
                .flatMap(count -> count >= max
                        ? Mono.error(new FreeTierExceededException(FreeTierExceededException.PATIENTS,
                                "free plan allows " + max + " patient profile(s)"))
                        : Mono.empty());
    }

    @Override
    public Mono<DocumentAllowance> documentAllowance(FamilyAccount family) {
        YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        if (!family.isFree()) {
            return Mono.just(DocumentAllowance.unlimited(month));
        }
        int max = properties.getFree().getMaxDocsPerMonth();
        // Counted from the document table (BMX-2), DONE only (DR-24, PostgresDocumentRecordAdapter.countInMonth).
        return documents.countInMonth(family.id(), month).map(used -> new DocumentAllowance(true, max, used, month));
    }

    @Override
    public Mono<DocumentAllowance> documentAllowance(UUID familyId) {
        return family(familyId).flatMap(this::documentAllowance);
    }

    @Override
    public Mono<Void> checkCanUploadDocuments(FamilyAccount family, int count) {
        return documentAllowance(family).flatMap(a -> a.allows(count) ? Mono.<Void>empty()
                : Mono.error(new FreeTierExceededException(FreeTierExceededException.DOCUMENTS,
                        "free plan allows " + a.max() + " document(s) per calendar month")));
    }

    @Override
    public Mono<Void> checkCanUploadDocuments(UUID familyId, int count) {
        return family(familyId).flatMap(f -> checkCanUploadDocuments(f, count));
    }

    @Override
    public Mono<Void> checkCanUploadDocument(FamilyAccount family) {
        return checkCanUploadDocuments(family, 1);
    }

    @Override
    public Mono<Void> checkCanUploadDocument(UUID familyId) {
        return family(familyId).flatMap(this::checkCanUploadDocument);
    }

    private Mono<FamilyAccount> family(UUID familyId) {
        return records.findFamily(familyId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("family_not_found", "no family with id " + familyId)));
    }
}
